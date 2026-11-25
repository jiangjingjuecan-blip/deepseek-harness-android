package com.sunwin.deepseekharness;

import java.util.*;

import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteException;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE, application=HarnessApp.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class HistoryDeletionTest {
    private HarnessApp app;
    private History history;
    private Path files;

    @Before public void setUp() throws Exception {
        app=(HarnessApp)RuntimeEnvironment.getApplication();
        history=app.history;
        files=app.getFilesDir().toPath();
        Files.createDirectories(files.resolve("photos"));
    }
    private long meterRows(long id) {
        return DatabaseUtils.longForQuery(history.getReadableDatabase(),
                "SELECT COUNT(*) FROM context_meter WHERE chat=?",new String[]{Long.toString(id)});
    }
    @Test public void removesOnlyOwnedRowsAndFilesWhenDshIsOffline() throws Exception {
        long removed=history.create(), kept=history.create();
        history.link(removed,"offline-session");
        history.link(kept,"kept-session");
        for (String name:new String[]{"photos/owned.jpg","photos/shared.jpg","photos/kept.jpg","private.txt"})
            Files.write(files.resolve(name),new byte[]{1,2,3});
        for (String path:new String[]{"photos/owned.jpg","photos/owned.jpg","photos/shared.jpg","photos/../private.txt"})
            history.begin(removed,"删除",Collections.singletonList(Attachment.photo(path)));
        history.begin(kept,"保留",Collections.singletonList(Attachment.photo("photos/./shared.jpg")));
        history.begin(kept,"另一张",Collections.singletonList(Attachment.photo("photos/kept.jpg")));
        history.meter(removed,"flash",new ContextUsage(80,100,true));
        history.meter(kept,"flash",new ContextUsage(23,100,true));

        assertEquals(kept,app.chat.delete(removed,kept));
        assertEquals(1,history.list().size());
        assertNull(history.chat(removed));
        assertTrue(history.messages(removed).isEmpty());
        assertEquals(0,meterRows(removed));
        assertFalse(Files.exists(files.resolve("photos/owned.jpg")));
        assertEquals("kept-session",history.chat(kept).remote);
        assertEquals(4,history.messages(kept).size());
        assertEquals(23,history.meter(kept,"flash").used);
        for (String path:new String[]{"photos/shared.jpg","photos/kept.jpg","private.txt"})
            assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(files.resolve(path)));
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        app.log.export(output);
        assertTrue(output.toString("UTF-8").contains("SESSION_ARCHIVE_OFFLINE"));
        assertEquals(kept,app.chat.delete(removed,kept));
    }
    @Test public void deletingCurrentSelectsFreshEmptyChatEvenWhenOthersRemain() {
        long kept=history.create(), old=history.create();
        history.begin(old,"最后一条",Collections.emptyList());
        long current=app.chat.delete(old,old);
        assertNotEquals(kept,current);
        assertNotEquals(old,current);
        assertNull(history.chat(old));
        assertEquals(2,history.list().size());
        assertEquals("新对话",history.chat(current).title);
        assertNull(history.chat(current).remote);
        assertTrue(history.messages(current).isEmpty());
        assertEquals(0,meterRows(current));
        assertEquals(0,history.meter(current,"").used);
    }
    @Test public void failedTransactionKeepsOriginalPhotoMessagesAndSelection() throws Exception {
        long id=history.create();
        Path photo=Files.createFile(files.resolve("photos/intact.jpg"));
        history.begin(id,"原文",Collections.singletonList(Attachment.photo("photos/intact.jpg")));
        history.meter(id,"flash",new ContextUsage(50,100,true));
        history.getWritableDatabase().execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON chat BEGIN SELECT RAISE(ABORT,'fixture'); END");
        assertThrows(SQLiteException.class,()->app.chat.delete(id,id));
        assertEquals(1,history.list().size());
        assertEquals(id,history.list().get(0).id);
        assertEquals(2,history.messages(id).size());
        assertEquals(1,meterRows(id));
        assertTrue(Files.exists(photo));
    }
    @Test public void restartFinishesCommittedPhotoCleanupAndRechecksOwnership() throws Exception {
        Path orphan=Files.createFile(files.resolve("photos/orphan.jpg"));
        Path shared=Files.createFile(files.resolve("photos/shared.jpg"));
        Path draft=Files.createFile(files.resolve("photos/draft.jpg"));
        for (String ref:new String[]{"photos/orphan.jpg","photos/shared.jpg","photos/already-gone.jpg"})
            history.getWritableDatabase().execSQL("INSERT INTO photo_cleanup(photo) VALUES(?)",new Object[]{ref});
        history.begin(history.create(),"引用照片",Collections.singletonList(Attachment.photo("photos/./shared.jpg")));
        history.close();
        try (History reopened=new History(app)) {
            reopened.cleanDeletedPhotos(app.log);
            assertFalse(Files.exists(orphan));
            assertTrue(Files.exists(shared));
            assertTrue(Files.exists(draft));
            assertEquals(0,DatabaseUtils.queryNumEntries(reopened.getReadableDatabase(),"photo_cleanup"));
        }
    }
    @Test public void sessionCreatedAfterDeletionIsArchivedWithoutTouchingReusedRowIds() throws Exception {
        long kept=history.create(), removed=history.create();
        String remote=UUID.randomUUID().toString();
        try (MockWebServer fixture=new MockWebServer(); Loopback api=new Loopback(fixture.getPort(),app.log)) {
            fixture.enqueue(new MockResponse().setResponseCode(303).setHeader("Set-Cookie","fixture="+UUID.randomUUID()));
            api.bootstrap("/bootstrap");
            assertEquals("/bootstrap",fixture.takeRequest(5,TimeUnit.SECONDS).getPath());
            app.api=api; app.account.stored=true; app.account.selected="deepseek-flash";
            assertTrue(app.chat.send(removed,"旧对话",Collections.emptyList()));
            JSONObject create=takeRpc(fixture,"session/create");
            assertEquals(kept,app.chat.delete(removed,kept));
            long reused=history.create();
            assertEquals(removed,reused);
            history.begin(reused,"新消息",Collections.emptyList());
            respond(fixture,create,Wire.obj("sessionId",remote));
            JSONObject archive=takeRpc(fixture,"workspace/archiveSession");
            JSONObject request=archive.getJSONObject("payload").getJSONObject("args").getJSONObject("request");
            assertEquals(remote,request.getString("sessionId"));
            assertTrue(request.getBoolean("stopActivity"));
            respond(fixture,archive,Wire.obj("archivedSessionIds",new org.json.JSONArray().put(remote)));
            assertNull(history.chat(reused).remote);
            assertEquals("pending",history.messages(reused).get(1).state);
            assertEquals(0,meterRows(reused));
        }
    }
    private JSONObject takeRpc(MockWebServer fixture,String method) throws Exception {
        RecordedRequest request=fixture.takeRequest(10,TimeUnit.SECONDS);
        assertNotNull(method,request);
        assertEquals("/api/"+method,request.getPath());
        return new JSONObject(request.getBody().readUtf8());
    }
    private void respond(MockWebServer fixture,JSONObject rpc,JSONObject value) throws Exception {
        fixture.enqueue(new MockResponse().setBody(Wire.obj("rpcId",rpc.getString("rpcId"),
                "result",Wire.obj("ok",true,"value",value)).toString()));
    }
}
