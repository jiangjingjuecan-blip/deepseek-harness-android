package com.sunwin.deepseekharness;

import android.net.Uri;
import okhttp3.mockwebserver.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,application=HarnessApp.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class SendPathTest {
    private HarnessApp app;
    private History h;
    private ChatEngine engine;
    private Loopback api;
    private MockWebServer server;
    private long chat,branch;
    private final CountDownLatch create=new CountDownLatch(1);
    private volatile boolean hold;
    @Before public void open() throws Exception {
        app=(HarnessApp)RuntimeEnvironment.getApplication();
        h=app.history; engine=app.chat; chat=h.create(); branch=h.chat(chat).branch;
        server=new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            public MockResponse dispatch(RecordedRequest request) {
                if ("/bootstrap".equals(request.getPath())) return new MockResponse().setResponseCode(303).setHeader("Set-Cookie","fixture="+UUID.randomUUID());
                create.countDown();
                return hold ? new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) : new MockResponse().setResponseCode(503);
            }
        });
        api=new Loopback(server.getPort(),app.log); api.bootstrap("/bootstrap");
        app.api=api; app.account.stored=true; app.account.selected="deepseek-flash";
    }
    @After public void close() throws Exception { api.close(); server.shutdown(); }
    private void idle() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while (engine.busy() && System.nanoTime()<deadline) Thread.sleep(5);
        assertFalse(engine.busy());
    }
    @Test public void reservationPrecedesIOAndBothFailuresReuseTextPhotoAndRow() throws Exception {
        assertTrue(h.addDraft(chat,Attachment.photo("photos/kept.jpg")));
        h.textDraft(branch,"一条消息");
        ChatEngine.Work w=engine.reserve(h.chat(chat),"一条消息",h.draft(chat),null,0,false);
        assertNotNull(w); assertTrue(w.localUser<0); assertEquals(1,w.attachments().size());
        assertEquals("一条消息",w.text); assertEquals(1,engine.outbox(branch).size());
        assertTrue(h.messages(chat).isEmpty()); assertEquals("一条消息",h.textDraft(branch)); assertEquals(1,h.draft(chat).size());
        h.getWritableDatabase().execSQL("CREATE TRIGGER denied BEFORE INSERT ON message BEGIN SELECT RAISE(ABORT,'fixture'); END");
        engine.start(w); idle();
        assertEquals(w,engine.outbox(branch).get(0)); assertNotNull(w.error); assertEquals(0,w.row);
        h.getWritableDatabase().execSQL("DROP TRIGGER denied");
        assertTrue(engine.retryLocal(w)); idle();
        long row=w.row,user=w.user;
        assertEquals("",h.textDraft(branch)); assertTrue(h.draft(chat).isEmpty());
        assertEquals("interrupted",h.at(branch,row).state);
        assertTrue(engine.retry(chat,user,row)); idle();
        List<History.Message> rows=h.messages(chat);
        assertEquals(2,rows.size()); assertEquals(user,rows.get(0).id); assertEquals(row,rows.get(1).id);
        assertEquals("一条消息",rows.get(0).text); assertEquals(1,rows.get(0).attachments.size());
        assertEquals("photos/kept.jpg",rows.get(0).attachments.get(0).paths.get(0));
    }
    @Test public void offlineTapStillCommitsTheQuestionAndCanRetryAfterReconnect() throws Exception {
        app.api=null;
        ChatEngine.Work w=engine.reserve(h.chat(chat),"离线草稿",Collections.emptyList(),null,0,false);
        assertNotNull(w); assertTrue(h.messages(chat).isEmpty());
        engine.start(w); idle();
        assertEquals("未连接",h.at(branch,w.row).error);
        app.api=api; assertTrue(engine.retry(chat,w.user,w.row)); idle();
        assertEquals(2,h.messages(chat).size()); assertEquals("离线草稿",h.at(branch,w.user).text);
    }
    @Test public void failedFinalWriteCanRepairTheSamePendingRow() throws Exception {
        h.getWritableDatabase().execSQL("CREATE TRIGGER no_final BEFORE UPDATE OF state ON message WHEN NEW.state='interrupted' "+
                "BEGIN SELECT RAISE(ABORT,'fixture'); END");
        assertTrue(engine.send(chat,"写入重试",Collections.emptyList())); idle();
        ChatEngine.Work w=engine.latest;
        assertTrue(w.unsaved); assertEquals("pending",h.at(branch,w.row).state);
        h.getWritableDatabase().execSQL("DROP TRIGGER no_final");
        assertTrue(engine.retry(chat,w.user,w.row)); idle();
        assertEquals(2,h.messages(chat).size()); assertEquals("interrupted",h.at(branch,w.row).state);
        assertTrue(engine.outbox(branch).isEmpty());
    }
    @Test public void stopCancelsBlockedRpcAndReleasesTheNextSend() throws Exception {
        hold=true;
        assertTrue(engine.send(chat,"停止试验",Collections.emptyList()));
        assertTrue(create.await(5,TimeUnit.SECONDS)); assertNotNull(engine.stoppingTarget());
        engine.interrupt(); idle();
        assertNull(engine.stoppingTarget());
        History.Message reply=h.messages(chat).get(1);
        assertEquals("interrupted",reply.state); assertEquals("已停止",reply.error);
        hold=false; assertTrue(engine.send(chat,"下一句",Collections.emptyList())); idle();
        assertEquals(4,h.messages(chat).size());
    }
    @Test public void branchSwitchAndLaterDraftArePreservedDuringFirstPaint() throws Exception {
        long anchor=h.begin(chat,"开场",Collections.emptyList()); h.update(anchor,"旧回复","done");
        ChatEngine.Work w=engine.reserve(h.chat(chat),"主线消息",Collections.emptyList(),null,0,false);
        engine.savingDraft(branch); h.textDraft(branch,"下条草稿");
        long other=h.fork(chat,anchor); engine.start(w); idle();
        assertEquals("下条草稿",h.textDraft(branch)); assertEquals(2,h.messages(chat).size());
        assertEquals(other,h.chat(chat).branch); assertEquals("主线消息",h.at(branch,w.user).text);
    }
    @Test public void pendingPickerSourceSurvivesStorageAndRetryAfterFailedCopy() throws Exception {
        File source=new File(app.getCacheDir(),"恢复.txt"); Uri uri=Uri.fromFile(source);
        DraftImporter.Batch b=new DraftImporter.Batch(chat,branch,Collections.singletonList(uri),true,null);
        ChatEngine.Work w=engine.reserve(h.chat(chat),"带文件",Collections.emptyList(),b,0,false); engine.start(w);
        b.done.countDown(); idle();
        assertEquals("interrupted",h.at(branch,w.row).state); assertTrue(h.needsUri(uri));
        assertEquals("带文件",h.at(branch,w.user).text); assertEquals(1,h.at(branch,w.user).missing);
        Files.write(source.toPath(),"恢复的文件".getBytes("UTF-8"));
        assertTrue(engine.retry(chat,w.user,w.row)); idle();
        History.Message user=h.at(branch,w.user);
        assertEquals(2,h.messages(chat).size()); assertEquals(1,user.attachments.size()); assertEquals(0,user.missing);
        assertFalse(h.needsUri(uri)); assertEquals(1,user.attachments.get(0).paths.size());
    }
    @Test public void retryOfSharedAnswerLeavesCompletedBranchPrefixIntact() {
        long row=h.begin(chat,"分支",Collections.emptyList()); h.update(row,"片段","interrupted","断线");
        long user=h.messages(chat).get(0).id, sibling=h.fork(chat,row);
        h.selectBranch(chat,branch);
        long retry=h.retry(chat,user,row,false);
        assertNotEquals(row,retry); assertEquals(user,h.messages(chat).get(0).id);
        assertEquals("pending",h.messages(chat).get(1).state); assertEquals("片段",h.at(sibling,row).text);
    }
}
