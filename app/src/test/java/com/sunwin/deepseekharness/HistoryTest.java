package com.sunwin.deepseekharness;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE, application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class HistoryTest {
    @Test public void restartRetainsSessionImageAndPartialReply() {
        Application ctx=RuntimeEnvironment.getApplication();
        History first=new History(ctx); long id=first.create();
        first.link(id,"dsh-session"); long answer=first.begin(id,"看图","photos/example.jpg");
        first.update(answer,"第一段","pending"); first.close();
        History second=new History(ctx); second.recover();
        List<History.Message> messages=second.messages(id);
        assertEquals("dsh-session",second.chat(id).remote); assertEquals("看图",second.list().get(0).title);
        assertEquals("photos/example.jpg",messages.get(0).photo);
        assertEquals("interrupted",messages.get(1).state); assertEquals("第一段",messages.get(1).text);
        assertEquals("应用进程已重启",messages.get(1).error);
        long follow=second.begin(id,"继续",null); second.update(follow,"尾声","done"); second.recover();
        assertEquals(4,second.messages(id).size()); assertEquals("done",second.messages(id).get(3).state);
        long blank=second.create(); assertTrue(second.messages(blank).isEmpty()); second.close();
    }
    @Test public void upgradesInstalledHistoryAndKeepsFailureSeparateFromPartialText() {
        Application ctx=RuntimeEnvironment.getApplication();
        try (SQLiteDatabase v1=ctx.openOrCreateDatabase("chats.db",0,null)) {
            v1.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY,title TEXT NOT NULL,remote TEXT UNIQUE,updated INTEGER NOT NULL)");
            v1.execSQL("CREATE TABLE message(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL REFERENCES chat(id),role TEXT NOT NULL,body TEXT NOT NULL,photo TEXT,state TEXT NOT NULL)");
            v1.execSQL("INSERT INTO chat VALUES(1,'你好','s',0)");
            v1.execSQL("INSERT INTO message VALUES(1,1,'assistant','旧正文',NULL,'interrupted')");
            v1.setVersion(1);
        }
        try (History db=new History(ctx)) {
            assertNull(db.messages(1).get(0).error);
            assertEquals("旧正文",db.messages(1).get(0).text);
            long row=db.begin(1,"继续",null);
            db.update(row,"第一段","interrupted","连接已断开");
            assertEquals(0,db.recover());
            History.Message reply=db.messages(1).get(2);
            assertEquals("第一段",reply.text); assertEquals("连接已断开",reply.error);
        }
    }
    @Test @Config(application=HarnessApp.class)
    public void sendPersistsTheDisconnectedServiceReason() throws Exception {
        HarnessApp app=(HarnessApp)RuntimeEnvironment.getApplication();
        long chat=app.history.create();
        app.api=new Loopback(1,app.log);
        app.account.stored=true; app.account.selected="deepseek-flash";
        app.api.close();
        assertTrue(app.chat.send(chat,"你好",null));
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (app.chat.busy() && System.nanoTime()<deadline) Thread.sleep(10);
        assertFalse(app.chat.busy());
        History.Message answer=app.history.messages(chat).get(1);
        assertEquals("interrupted",answer.state); assertEquals("",answer.text);
        assertEquals("DSH 服务已停止",answer.error);
    }
    @Test public void versionTwoTranscriptAndMeterSurviveReopenIndependently() {
        Application ctx=RuntimeEnvironment.getApplication();
        try (SQLiteDatabase db=ctx.openOrCreateDatabase("chats.db",0,null)) {
            db.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY,title TEXT NOT NULL,remote TEXT UNIQUE,updated INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE message(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL REFERENCES chat(id),role TEXT NOT NULL,body TEXT NOT NULL,photo TEXT,state TEXT NOT NULL,error TEXT)");
            db.execSQL("INSERT INTO chat VALUES(1,'旧对话','remote',0)");
            db.execSQL("INSERT INTO message VALUES(1,1,'user','原始照片','photos/old.jpg','done',NULL)");
            db.setVersion(2);
        }
        try (History db=new History(ctx)) {
            assertTrue(db.meter(1,"deepseek-flash").used>=ContextUsage.estimate("原始照片",true));
            db.meter(1,"deepseek-flash",new ContextUsage(77,1048576,true));
        }
        try (History db=new History(ctx)) {
            assertEquals(77,db.meter(1,"deepseek-flash").used);
            assertEquals("remote",db.chat(1).remote);
            assertEquals("原始照片",db.messages(1).get(0).text);
            assertEquals("photos/old.jpg",db.messages(1).get(0).photo);
        }
    }
}
