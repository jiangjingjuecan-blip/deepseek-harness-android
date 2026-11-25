package com.sunwin.deepseekharness;

import android.app.Application;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE, application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class AdventureHistoryTest {
    @Test public void realV6RowsMigrateToOneLineAndDeleteAcrossAllVersions() throws Exception {
        Application ctx=RuntimeEnvironment.getApplication();
        Path docs=Files.createDirectories(ctx.getFilesDir().toPath().resolve("documents"));
        Path photo=Files.createDirectories(ctx.getFilesDir().toPath().resolve("photos")).resolve("old.jpg");
        Files.write(photo,new byte[]{1,2,3}); Files.write(docs.resolve("old.txt"),"甲[图 1]乙".getBytes("UTF-8"));
        try (SQLiteDatabase v6=ctx.openOrCreateDatabase("chats.db",0,null)) {
            v6.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY,title TEXT NOT NULL,remote TEXT UNIQUE,updated INTEGER NOT NULL)");
            v6.execSQL("CREATE TABLE message(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL REFERENCES chat(id),role TEXT NOT NULL,body TEXT NOT NULL,photo TEXT,state TEXT NOT NULL,error TEXT)");
            v6.execSQL("CREATE TABLE context_meter(chat INTEGER PRIMARY KEY REFERENCES chat(id) ON DELETE CASCADE,model TEXT NOT NULL,used INTEGER NOT NULL,capacity INTEGER NOT NULL)");
            v6.execSQL("CREATE TABLE photo_cleanup(photo TEXT PRIMARY KEY NOT NULL)");
            v6.execSQL("CREATE TABLE attachment(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL REFERENCES chat(id) ON DELETE CASCADE,message INTEGER REFERENCES message(id) ON DELETE CASCADE,kind TEXT NOT NULL,name TEXT NOT NULL,bytes INTEGER NOT NULL)");
            v6.execSQL("CREATE TABLE attachment_file(attachment INTEGER NOT NULL REFERENCES attachment(id) ON DELETE CASCADE,position INTEGER NOT NULL,path TEXT NOT NULL,text_offset INTEGER,PRIMARY KEY(attachment,position))");
            v6.execSQL("INSERT INTO chat VALUES(8,'旧文游','original',1234)");
            v6.execSQL("INSERT INTO message VALUES(12,8,'user','原问',NULL,'done',NULL),(17,8,'assistant','原答',NULL,'done',NULL)");
            v6.execSQL("INSERT INTO context_meter VALUES(8,'deepseek-flash',50,1048576)");
            v6.execSQL("INSERT INTO attachment VALUES(1,8,12,'TEXT','旧卷',13),(2,8,NULL,'PHOTO','草稿',0)");
            v6.execSQL("INSERT INTO attachment_file VALUES(1,0,'documents/old.txt',NULL),(1,1,'photos/old.jpg',1),(2,0,'photos/old.jpg',NULL)");
            v6.setVersion(6);
        }
        long child,edited;
        try (History db=new History(ctx)) {
            assertEquals(8,db.getReadableDatabase().getVersion());
            assertEquals(1,db.branches(8).size()); assertEquals("original",db.chat(8).remote);
            assertEquals(50,db.meter(8,"deepseek-flash").used); assertEquals(1,db.draft(8).size());
            long main=db.chat(8).branch;
            db.bookmark(main,12,true); db.textDraft(main,"主线草稿");
            db.roll(8,Dice.roll("2d8-1"));
            edited=db.edit(8,12,"改问"); child=db.chat(8).branch;
            assertNull(db.chat(8).remote); assertTrue(db.draft(8).isEmpty()); assertEquals("",db.textDraft(child));
            Attachment kept=db.messages(8).get(0).attachments.get(0);
            assertEquals(Arrays.asList("documents/old.txt","photos/old.jpg"),kept.paths);
            assertEquals(Collections.singletonList(1),kept.imageOffsets);
            assertEquals(2,db.at(child,edited).version);
            db.link(8,"edited"); db.meter(8,"deepseek-flash",new ContextUsage(75,1048576,false));
            db.bookmark(child,edited,true);
            assertEquals(12,db.switchVersion(8,edited,-1)); assertEquals(main,db.chat(8).branch);
            assertEquals(3,db.messages(8).size()); assertEquals("主线草稿",db.textDraft(main));
            assertTrue(db.at(main,12).saved); assertEquals(50,db.meter(8,"deepseek-flash").used);
            long fork=db.fork(8,17);
            assertTrue(db.at(fork,12).saved);
            db.bookmark(fork,12,false); assertTrue(db.at(main,12).saved);
            assertEquals("",db.diceBefore(fork,Long.MAX_VALUE));
            db.selectBranch(8,child);
        }
        try (History db=new History(ctx)) {
            assertEquals(child,db.chat(8).branch); assertTrue(db.at(child,edited).saved);
            assertEquals(75,db.meter(8,"deepseek-flash").used);
            assertEquals(new HashSet<>(Arrays.asList("original","edited")),new HashSet<>(db.sessions(8)));
            long kept=db.create(); assertEquals(kept,db.delete(8,kept,new Diagnostics(ctx.getNoBackupFilesDir())));
            SQLiteDatabase sql=db.getReadableDatabase();
            for (String table:Arrays.asList("message","branch_message","save_point","version_visit","attachment","attachment_file","context_meter","chat_session","photo_cleanup"))
                assertEquals(table,0,DatabaseUtils.queryNumEntries(sql,table));
            assertEquals(1,DatabaseUtils.queryNumEntries(sql,"branch"));
            assertFalse(Files.exists(photo)); assertFalse(Files.exists(docs.resolve("old.txt")));
            try (android.database.Cursor c=sql.rawQuery("PRAGMA foreign_key_check",null)) { assertFalse(c.moveToFirst()); }
        }
    }
    @Test public void versionArrowsReturnToTheForkWhereAnInheritedReplyWasEdited() {
        Application ctx=RuntimeEnvironment.getApplication();
        try (History db=new History(ctx)) {
            long chat=db.create();
            long answer=db.begin(chat,"点灯",Collections.emptyList()); db.update(answer,"蓝灯","done");
            long fork=db.fork(chat,answer);
            long next=db.begin(chat,"拾起",Collections.emptyList()); db.update(next,"照亮山洞","done");
            long edited=db.edit(chat,answer,"绿灯");
            assertEquals(answer,db.switchVersion(chat,edited,-1));
            assertEquals(fork,db.chat(chat).branch);
            assertEquals("照亮山洞",db.messages(chat).get(3).text);
        }
    }
    @Test public void pendingRollsAndDraftOwnersFollowTheBranchOnRetry() {
        Application ctx=RuntimeEnvironment.getApplication();
        try (History db=new History(ctx)) {
            long chat=db.create(), main=db.chat(chat).branch;
            Dice roll=Dice.roll("d4"); long first=db.roll(chat,roll);
            assertTrue(db.addDraft(chat,Attachment.photo("photos/shared.jpg")));
            long child=db.fork(chat,first); Dice second=Dice.roll("2d20"); db.roll(chat,second);
            long answer=db.begin(chat,"观察",Collections.emptyList());
            long user=db.last(child,answer);
            assertEquals(roll.block()+"\n"+second.block()+"\n观察",db.promptText(child,db.at(child,user)));
            assertEquals("",db.diceBefore(child,Long.MAX_VALUE));
            assertThrows(SQLiteException.class,()->db.fork(chat,answer));
            db.update(answer,"天晴","done"); db.selectBranch(chat,main);
            assertEquals(roll.block(),db.diceBefore(main,Long.MAX_VALUE));
            List<Attachment> draft=db.draft(chat); assertEquals(1,draft.size());
            db.selectBranch(chat,child);
            assertThrows(SQLiteException.class,()->db.begin(chat,"错误草稿",draft));
            assertEquals(4,db.messages(chat).size()); assertEquals(1,db.branchDraft(main).size());
        }
    }
}
