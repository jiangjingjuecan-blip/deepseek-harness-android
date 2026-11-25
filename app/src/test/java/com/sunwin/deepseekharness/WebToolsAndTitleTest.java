package com.sunwin.deepseekharness;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE, application=Application.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class WebToolsAndTitleTest {

    @Test public void modelRoutingAndModesFollowWebSearchSetting() {
        List<String> models=Arrays.asList("deepseek-flash","deepseek-v4-pro");
        assertEquals("deepseek-flash",WebTools.effectiveModel("deepseek-flash",models,true));
        assertEquals("deepseek-flash",WebTools.effectiveModel("deepseek-v4-pro",models,true));
        assertEquals("deepseek-v4-pro",WebTools.effectiveModel("deepseek-v4-pro",models,false));
        assertEquals("deepseek-flash",WebTools.effectiveModel("deepseek-flash",models,false));
        assertEquals("deepseek-v4-pro",WebTools.effectiveModel("deepseek-v4-pro",Collections.singletonList("deepseek-v4-pro"),true));

        assertEquals("search",WebTools.mode("deepseek-flash",true));
        assertEquals("read",WebTools.mode("deepseek-flash",false));
        assertEquals("off",WebTools.mode("deepseek-v4-pro",false));
        assertEquals("off",WebTools.mode("deepseek-v4-pro",true));
    }

    @Test public void activityLabelsUrlsAndSourcesAreSanitizedAndDeduped() {
        assertEquals("搜索 冰岛人口 2025、Statistics Iceland",
                WebTools.activityLabel("search","search",Arrays.asList("冰岛人口 2025","Statistics Iceland"),"",""));
        assertEquals("识图搜索",WebTools.activityLabel("search","image_search",Collections.emptyList(),"",""));
        assertEquals("阅读 toureiffel.paris",
                WebTools.activityLabel("open_page","deep_search",Collections.emptyList(),"https://www.toureiffel.paris/en/key-figures",""));
        assertEquals("阅读 example.com",
                WebTools.activityLabel("read_link","read_link",Collections.emptyList(),"https://example.com/a",""));
        assertEquals("查找 en.tokyotower.co.jp · 333",
                WebTools.activityLabel("find_in_page","deep_search",Collections.emptyList(),"https://en.tokyotower.co.jp/","333"));

        assertNotNull(WebTools.safeHttpUrl("https://example.com/path?a=1"));
        assertNotNull(WebTools.safeHttpUrl("http://example.com"));
        assertNull(WebTools.safeHttpUrl("javascript:alert(1)"));
        assertNull(WebTools.safeHttpUrl("file:///etc/passwd"));
        assertNull(WebTools.safeHttpUrl(""));

        List<WebTools.Source> sources=Arrays.asList(
                new WebTools.Source("https://www.huaon.com/a","2024 冰岛人口","华经情报网"),
                new WebTools.Source("https://www.huaon.com/a","duplicate","dup"),
                new WebTools.Source("javascript:bad","bad","bad"),
                new WebTools.Source("https://example.com/page","","")
        );
        String json=WebTools.serializeSources(sources);
        List<WebTools.Source> parsed=WebTools.parseSources(json);
        assertEquals(2,parsed.size());
        assertEquals("华经情报网",parsed.get(0).label());
        assertEquals("example.com",parsed.get(1).label());

        assertEquals("联网搜索失败",WebTools.toolErrorLabel("unavailable","search"));
        assertEquals("网页无法访问",WebTools.toolErrorLabel("url_not_accessible","read_link"));
        assertEquals("网页不允许访问",WebTools.toolErrorLabel("url_not_allowed","read_link"));
    }

    @Test public void titleSanitizationAndRefreshCadenceWorkAsSpecified() {
        assertEquals("冰岛人口统计",ChatTitle.sanitize("  「冰岛人口统计。」\n第二行忽略"));
        assertEquals("DeepSeek V4.1 新特性",ChatTitle.sanitize("\"DeepSeek V4.1 新特性！\""));
        assertEquals("一二三四五六七八九十甲乙",ChatTitle.sanitize("一二三四五六七八九十甲乙丙丁戊"));
        assertEquals("How to factor x5 plus one",ChatTitle.sanitize("  \"How to factor x5 plus one two three.\"  "));
        assertEquals("",ChatTitle.sanitize("   \"\"  "));

        Application ctx=RuntimeEnvironment.getApplication();
        try (History db=new History(ctx)) {
            long id=db.create();
            // Turn 1: should request title
            assertTrue(ChatTitle.shouldRequest(db.chat(id),0));
            db.setAiTitle(id,"首轮标题",1);
            assertEquals("首轮标题",db.chat(id).title);
            assertEquals(ChatTitle.SOURCE_AI,db.chat(id).titleSource);
            assertEquals(1,db.chat(id).titleTurns);

            // Turn 2: skip
            assertFalse(ChatTitle.shouldRequest(db.chat(id),1));
            // Turn 3: refresh!
            assertTrue(ChatTitle.shouldRequest(db.chat(id),2));
            db.setAiTitle(id,"三轮更新标题",3);
            assertEquals("三轮更新标题",db.chat(id).title);
            // Turn 4+: skip
            assertFalse(ChatTitle.shouldRequest(db.chat(id),3));
            assertFalse(ChatTitle.shouldRequest(db.chat(id),4));

            // User rename must never be overwritten by AI title
            db.rename(id,"我的自定义标题");
            assertEquals("我的自定义标题",db.chat(id).title);
            assertEquals(ChatTitle.SOURCE_USER,db.chat(id).titleSource);
            assertFalse(ChatTitle.shouldRequest(db.chat(id),0));
            assertFalse(ChatTitle.shouldRequest(db.chat(id),2));
            db.setAiTitle(id,"企图覆盖",3);
            assertEquals("我的自定义标题",db.chat(id).title);
        }
    }

    @Test public void upgradesVersionEightDatabaseToNineAndPersistsWebMetadata() throws Exception {
        Application ctx=RuntimeEnvironment.getApplication();
        try (SQLiteDatabase v8=ctx.openOrCreateDatabase("chats.db",0,null)) {
            v8.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY,title TEXT NOT NULL,remote TEXT UNIQUE,updated INTEGER NOT NULL)");
            v8.execSQL("CREATE TABLE message(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL REFERENCES chat(id),role TEXT NOT NULL,body TEXT NOT NULL,photo TEXT,state TEXT NOT NULL,error TEXT,home INTEGER,family INTEGER,anchor_remote TEXT,anchor_seq INTEGER,file_bytes INTEGER,image_slots INTEGER)");
            v8.execSQL("CREATE TABLE branch(id INTEGER PRIMARY KEY AUTOINCREMENT,chat INTEGER NOT NULL REFERENCES chat(id) ON DELETE CASCADE,number INTEGER NOT NULL,draft TEXT NOT NULL DEFAULT '',selected INTEGER NOT NULL DEFAULT 0,remote TEXT UNIQUE,dirty INTEGER NOT NULL DEFAULT 0,UNIQUE(chat,number))");
            v8.execSQL("CREATE TABLE chat_session(remote TEXT PRIMARY KEY NOT NULL,chat INTEGER NOT NULL REFERENCES chat(id) ON DELETE CASCADE)");
            v8.execSQL("CREATE TABLE branch_message(branch INTEGER NOT NULL REFERENCES branch(id) ON DELETE CASCADE,message INTEGER NOT NULL REFERENCES message(id) ON DELETE CASCADE,PRIMARY KEY(branch,message))");
            v8.execSQL("CREATE TABLE save_point(branch INTEGER NOT NULL,message INTEGER NOT NULL,PRIMARY KEY(branch,message))");
            v8.execSQL("CREATE TABLE version_visit(message INTEGER PRIMARY KEY,branch INTEGER NOT NULL)");
            v8.execSQL("CREATE TABLE attachment(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL REFERENCES chat(id) ON DELETE CASCADE,message INTEGER REFERENCES message(id) ON DELETE CASCADE,kind TEXT NOT NULL,name TEXT NOT NULL,bytes INTEGER NOT NULL,draft_branch INTEGER)");
            v8.execSQL("CREATE TABLE attachment_file(attachment INTEGER NOT NULL REFERENCES attachment(id) ON DELETE CASCADE,position INTEGER NOT NULL,path TEXT NOT NULL,text_offset INTEGER,PRIMARY KEY(attachment,position))");
            v8.execSQL("CREATE TABLE context_meter(branch INTEGER PRIMARY KEY REFERENCES branch(id) ON DELETE CASCADE,chat INTEGER NOT NULL REFERENCES chat(id) ON DELETE CASCADE,model TEXT NOT NULL,used INTEGER NOT NULL,capacity INTEGER NOT NULL)");
            v8.execSQL("CREATE TABLE photo_cleanup(photo TEXT PRIMARY KEY NOT NULL)");
            v8.execSQL("CREATE TABLE message_import(message INTEGER NOT NULL REFERENCES message(id) ON DELETE CASCADE,position INTEGER NOT NULL,uri TEXT NOT NULL,document INTEGER NOT NULL,PRIMARY KEY(message,position),UNIQUE(message,uri))");
            v8.execSQL("INSERT INTO chat VALUES(1,'升级前旧标题',NULL,1000)");
            v8.execSQL("INSERT INTO branch(id,chat,number,selected,remote) VALUES(1,1,1,1,'old-v8')");
            v8.execSQL("INSERT INTO message(id,chat,role,body,state,home,family,anchor_remote,anchor_seq) VALUES(1,1,'user','旧问题','done',1,1,'old-v8',2),(2,1,'assistant','旧回答','done',1,2,'old-v8',5)");
            v8.execSQL("INSERT INTO branch_message VALUES(1,1),(1,2)");
            v8.setVersion(8);
        }
        String blocksJson="[{\"slot\":0,\"block\":{\"type\":\"server_tool_use\",\"id\":\"srv1\",\"name\":\"deepseek_read_url\"}}]";
        String citesJson=WebTools.serializeSources(Collections.singletonList(new WebTools.Source("https://example.com","Example","Example")));
        try (History db=new History(ctx)) {
            assertEquals(9,db.getReadableDatabase().getVersion());
            History.Chat migrated=db.chat(1);
            assertEquals("升级前旧标题",migrated.title);
            assertEquals(ChatTitle.SOURCE_AUTO,migrated.titleSource);
            assertEquals(0,migrated.titleTurns);
            // Migrated chat lazily qualifies for AI title on its next turn
            assertTrue(ChatTitle.shouldRequest(migrated,1));
            long row=db.begin(1,"新问题",Collections.emptyList());
            db.update(row,"带来源回答","done",null,blocksJson,citesJson);
            long edited=db.edit(1,row,"编辑后回答");
            assertEquals(blocksJson,db.at(db.chat(1).branch,edited).webBlocks);
            assertEquals(1,db.at(db.chat(1).branch,edited).sources.size());
        }
        try (History reopened=new History(ctx)) {
            List<History.Message> rows=reopened.messages(1);
            History.Message tail=rows.get(rows.size()-1);
            assertEquals("编辑后回答",tail.text);
            assertEquals(blocksJson,tail.webBlocks);
            assertEquals("https://example.com",tail.sources.get(0).url);
        }
    }

    @Test public void replyParsesWebHooksAndCollapsesStatusOnFirstDelta() throws Exception {
        Reply r=new Reply("req-1");
        r.accept(Wire.obj("type","event","event",Wire.obj("type","agent/inbox/spliced","data",
                Wire.obj("target","next-turn","inserted",new JSONArray().put(Wire.obj("source",Wire.obj("kind","user","rpcId","req-1")))))));
        r.accept(Wire.obj("type","event","event",Wire.obj("type","turn/start","data",Wire.obj("turn",1))));
        r.accept(Wire.obj("type","assistant-stream","frame",Wire.obj("type","start","turn",1,"attemptId","att-1")));

        assertTrue(r.accept(hook(1,"phone-web:activity",Wire.obj("id","s1","action","search","kind","search",
                "queries",new JSONArray().put("Iceland 2025")))));
        assertEquals("搜索 Iceland 2025",r.webStatus);

        assertTrue(r.accept(hook(1,"phone-web:citation",Wire.obj("url","https://example.com/a","title","A","site","SiteA"))));
        assertEquals(1,r.sources().size());
        assertEquals("SiteA",r.sources().get(0).label());

        assertTrue(r.accept(Wire.obj("type","assistant-stream","frame",Wire.obj("type","chunk","attemptId","att-1",
                "chunk",Wire.obj("type","text-delta","text","回答正文")))));
        assertEquals("",r.webStatus);

        r.accept(hook(1,"phone-web:title",Wire.obj("title","「冰岛数据。」")));
        assertEquals("冰岛数据",r.title);

        r.accept(hook(1,"phone-web:blocks",Wire.obj("blocks",new JSONArray().put(Wire.obj("slot",0,"block",Wire.obj("type","server_tool_use"))),
                "citations",new JSONArray(),"errors",new JSONArray().put(Wire.obj("errorCode","unavailable","kind","search")))));
        assertNotNull(r.webBlocksJson);
        assertEquals("联网搜索失败",r.webError);

        assertEquals("内容安全未通过",ChatFailure.remote(Wire.obj("code","CONTENT_REFUSAL","message","refused"),0,"").getMessage());
        assertEquals("HTTP 400 · 模型拒绝了请求 · 内容安全未通过",
                ChatFailure.remote(Wire.obj("code","INVALID_REQUEST","message","Content Exists Risk"),400,"").getMessage());
    }

    private static JSONObject hook(int turn,String point,JSONObject decision) {
        return Wire.obj("type","event","event",Wire.obj("type","hook/result","data",
                Wire.obj("turn",turn,"point",point,"handlerId","phone-web","decision",decision.toString(),"durationMs",0)));
    }
}
