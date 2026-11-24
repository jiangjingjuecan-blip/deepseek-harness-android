package com.sunwin.deepseekharness;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.json.*;
import java.io.*;
import static org.junit.Assert.*;

public class CoreTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void onlyTypedDiagnosticEventsReachExport() throws Exception {
        Diagnostics log=new Diagnostics(temp.getRoot());
        for (String line:new String[]{"Cookie: opaque-test","Authorization: Bearer opaque-test","password=opaque-test",
                "dsh web: http://127.0.0.1:80/?token=opaque-test","GET /oauth/callback?code=opaque-test",
                "proot error: Permission denied opaque-test","CANNOT LINK EXECUTABLE opaque-test",
                "proot info: pid 21: terminated with signal 9 opaque-test","x-api-key: opaque-test"})
            log.childLine(line);
        log.add(Diagnostics.Event.HTTP_ACCOUNT,401);
        ByteArrayOutputStream out=new ByteArrayOutputStream(); log.export(out); String text=out.toString("UTF-8");
        assertFalse(text.contains("opaque")); assertFalse(text.contains("callback"));
        assertTrue(text.contains("PERMISSION_ERROR")); assertTrue(text.contains("NATIVE_SIGNAL 9")); assertTrue(text.contains("HTTP_ACCOUNT 401"));
    }
    @Test public void strictAuthOriginAndSameTurnImage() throws Exception {
        assertNotNull(Wire.authorization("https://platform.deepseek.com/dsh/authorize?state=test"));
        assertNull(Wire.authorization("https://platform.deepseek.com@example.com/dsh/authorize"));
        assertNull(Wire.authorization("http://platform.deepseek.com/dsh/authorize"));
        JSONArray parts=Wire.prompt("s","m","看图","base64").getJSONObject("request").getJSONArray("content");
        assertEquals(2,parts.length()); assertEquals("看图",parts.getJSONObject(0).getString("text"));
        assertEquals("image/jpeg",parts.getJSONObject(1).getString("mediaType"));
    }
    @Test public void onlyAccountModelsAndSeparateCurrencyTotals() throws Exception {
        JSONObject c=new JSONObject("{groups:[{id:'api',models:[{id:'other'}]},{id:'deepseek-account',models:[{id:'other-account'},{id:'deepseek-flash'}]}]}");
        assertEquals(java.util.Arrays.asList("deepseek-flash","other-account"),Wire.models(c));
        JSONObject b=new JSONObject("{status:'ready',value:[{currency:'CNY',balance:'0.12'},{currency:'USD',balance:'4'}],bonusWallets:[{currency:'CNY',balance:'0.30'}]}");
        assertEquals("¥0.42  ·  US$4",Wire.balance(b)); assertEquals("",Wire.balance(null));
    }
    private static JSONObject event(String type,JSONObject data) {
        return Wire.obj("type","event","event",Wire.obj("type",type,"surfaceOp","append","data",data));
    }
    private static JSONObject enqueue(String id) {
        return event("agent/inbox/spliced",Wire.obj("target","next-turn","inserted",new JSONArray().put(
                Wire.obj("source",Wire.obj("kind","user","rpcId",id)))));
    }
    private static JSONObject end(int turn,String kind) {
        return event("turn/end",Wire.obj("turn",turn,"reason",Wire.obj("kind",kind)));
    }
    @Test public void queuedReplyIgnoresPreviousTurnAndUsesDurableText() throws Exception {
        Reply r=new Reply("current");
        r.accept(enqueue("previous"));
        r.accept(event("turn/start",Wire.obj("turn",1)));
        r.accept(enqueue("current"));
        r.accept(end(1,"interrupted"));
        assertFalse(r.ended); assertFalse(r.active());
        r.accept(event("turn/start",Wire.obj("turn",2)));
        r.accept(new JSONObject("{type:'assistant-stream',frame:{type:'start',turn:2,attemptId:'a'}}"));
        assertFalse(r.accept(new JSONObject("{type:'assistant-stream',frame:{type:'chunk',attemptId:'old',chunk:{type:'text-delta',text:'旧'}}}")));
        assertTrue(r.accept(new JSONObject("{type:'assistant-stream',frame:{type:'chunk',attemptId:'a',chunk:{type:'text-delta',text:'你'}}}")));
        assertEquals("你",r.body());
        r.accept(event("assistant/message",new JSONObject("{turn:2,message:{content:[{type:'text',text:'你好'}]}}")));
        r.accept(end(2,"completed"));
        assertEquals("你好",r.body()); assertTrue(r.ended && r.completed);
    }
    @Test public void emptyAndFailedTurnsHaveDistinctSafeReasons() throws Exception {
        Reply empty=new Reply("queued");
        empty.accept(enqueue("queued")); empty.accept(event("turn/start",Wire.obj("turn",1)));
        empty.accept(end(1,"completed"));
        assertTrue(empty.ended); assertFalse(empty.completed);
        assertEquals("回合结束但没有正文",empty.failure.getMessage());
        JSONObject error=Wire.obj("code","session/attachment-invalid","message","private fixture text",
                "details",Wire.obj("reason","MODEL_DOES_NOT_SUPPORT_IMAGES"));
        ChatFailure rejected=ChatFailure.remote(error,0,"DSH 拒绝请求");
        Diagnostics log=new Diagnostics(temp.getRoot());
        log.interrupted(ChatFailure.Stage.PROMPT,rejected);
        log.interrupted(ChatFailure.Stage.FOLLOW,ChatFailure.wsClosed(1008,"private fixture text"));
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        log.export(out);
        String text=out.toString("UTF-8");
        assertTrue(text.contains("PROMPT") && text.contains("session/attachment-invalid"));
        assertTrue(text.contains("MODEL_DOES_NOT_SUPPORT_IMAGES") && text.contains("1008"));
        assertFalse(text.contains("private fixture text"));
    }
    @Test public void rpcAndMuxErrorsRetainTheirSafeCause() throws Exception {
        JSONObject response=Wire.obj("rpcId","r","result",Wire.obj("ok",false,
                "error",Wire.obj("code","gateway/internal","message","EACCES private fixture text")));
        assertEquals("gateway/internal · EACCES",assertThrows(ChatFailure.class,()->Wire.rpcValue("r",response)).getMessage());
        assertEquals("HTTP 403",ChatFailure.remote(null,403,"本机 HTTP 请求失败").getMessage());
        JSONObject frame=Wire.obj("streamId","reply","type","error","error",Wire.obj("code","session/not-found"));
        assertEquals("DSH 会话不存在",assertThrows(ChatFailure.class,()->Wire.followItem(frame)).getMessage());
        frame.put("type","end");
        assertEquals("订阅提前结束",assertThrows(ChatFailure.class,()->Wire.followItem(frame)).getMessage());
    }
    @Test public void lastAttemptSuppliesMissingErrorButExplicitEndReasonWins() throws Exception {
        Reply reply=new Reply("mine");
        reply.accept(enqueue("mine")); reply.accept(event("turn/start",Wire.obj("turn",1)));
        reply.accept(new JSONObject("{type:'assistant-stream',frame:{type:'start',turn:1,attemptId:'a'}}"));
        reply.accept(new JSONObject("{type:'assistant-stream',frame:{type:'chunk',attemptId:'a',chunk:{type:'finish',reason:{failure:{code:'TRANSPORT',message:'connect ENETUNREACH'}}}}}"));
        assertEquals("模型连接失败 · ENETUNREACH",reply.attemptFailure.getMessage());
        assertEquals("HTTP 402 · 账号额度不足",ChatFailure.turn(new JSONObject("{kind:'error',error:{code:'ACCOUNT_QUOTA',status:402}}"),reply.attemptFailure).getMessage());
        reply.accept(end(1,"error"));
        assertSame(reply.attemptFailure,reply.failure);
    }
    @Test public void catalogFallbackIsDistinctFromALiveListingAndProfile() throws Exception {
        Account account=new Account(null);
        try (Loopback api=new Loopback(1,new Diagnostics(temp.getRoot()))) {
            account.stored=true; account.selected="deepseek-flash"; account.confirmed=true;
            assertEquals("模型目录待确认",account.modelStatus(api));
            api.listing=Account.Listing.fromLine("[llm-deepseek-account] model listing from local failed (failure); using the bundled catalog");
            assertEquals("内置模型目录",account.modelStatus(api));
            api.listing=Account.Listing.fromLine("[llm-deepseek-account] model listing from local failed (failure); keeping the previous catalog");
            assertEquals("上次的模型目录",account.modelStatus(api));
            api.listing=Account.Listing.fromLine("[llm-deepseek-account] model listing from local loaded 2 model(s), skipped 0 row(s)");
            account.confirmed=false;
            assertEquals("已保存登录状态",account.modelStatus(api));
            account.confirmed=true; assertEquals("deepseek-flash",account.modelStatus(api));
            assertNull(Account.Listing.fromLine("[llm-deepseek] model listing from local failed; using the bundled catalog"));
            JSONObject failed=new JSONObject("{groups:[],failures:[{id:'deepseek-account',message:'lookup EAI_AGAIN'}]}");
            assertEquals("EAI_AGAIN",assertThrows(ChatFailure.class,()->Wire.models(failed)).getMessage());
        }
    }
}
