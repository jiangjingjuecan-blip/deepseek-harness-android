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
    @Test public void finalTextReplacesDeltas() throws Exception {
        Reply r=new Reply();
        assertTrue(r.accept(new JSONObject("{type:'assistant-stream',frame:{type:'chunk',chunk:{type:'text-delta',text:'你'}}}")));
        assertEquals("你",r.body());
        r.accept(new JSONObject("{type:'event',event:{type:'assistant/message',data:{message:{content:[{type:'text',text:'你好'}]}}}}"));
        r.accept(new JSONObject("{type:'event',event:{type:'turn/end',data:{reason:{kind:'completed'}}}}"));
        assertEquals("你好",r.body()); assertTrue(r.ended && r.completed);
    }
}
