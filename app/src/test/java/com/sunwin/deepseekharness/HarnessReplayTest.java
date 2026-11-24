package com.sunwin.deepseekharness;

import org.json.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import okhttp3.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import static org.junit.Assert.*;

public class HarnessReplayTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    @Test(timeout=180000) public void officialWebImageStreamingAndContinuation() throws Exception {
        String abi=System.getenv("HARNESS_REPLAY");
        Assume.assumeTrue("opt-in native runtime replay",abi!=null && !abi.isEmpty());
        Path script=Paths.get("../tools/replay.py").toAbsolutePath().normalize();
        Process p=new ProcessBuilder("python3",script.toString(),abi,folder.getRoot().getPath()).redirectError(new File("/dev/null")).start();
        Loopback api=null;
        try {
            String launch=new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            Matcher ready=Pattern.compile("dsh web: http://127\\.0\\.0\\.1:(\\d+)(/\\?token=\\S+)").matcher(launch==null?"":launch);
            assertTrue("local process ready",ready.matches());
            api=new Loopback(Integer.parseInt(ready.group(1)),new Diagnostics(folder.getRoot()));
            api.bootstrap(ready.group(2));
            assertEquals("credential-stored",api.rpc("account/getState",Wire.obj()).getString("status"));
            assertEquals("deepseek-flash",Wire.models(api.rpc("session/modelCatalog",Wire.obj())).get(0));
            assertEquals("¥3.25",Wire.balance(api.rpc("account/getBalance",Wire.obj("client",Wire.client()))));
            String sid=api.rpc("session/create",Wire.request(Wire.obj("agentPreset","phone-chat"))).getString("sessionId");
            api.rpc("session/selectModel",Wire.request(Wire.obj("sessionId",sid,"provider","deepseek-account","model","deepseek-flash")));
            String jpeg=Base64.getEncoder().encodeToString(Files.readAllBytes(Paths.get("src/test/resources/photo.jpg")));
            for (int turn=0;turn<2;turn++) {
                BlockingQueue<String> q=new LinkedBlockingQueue<>();
                WebSocket ws=api.follow(new WebSocketListener() {
                    @Override public void onOpen(WebSocket socket,Response response) { socket.send(Wire.follow(sid).toString()); }
                    @Override public void onMessage(WebSocket socket,String body) { q.add(body); }
                    @Override public void onFailure(WebSocket socket,Throwable e,Response response) { q.add("{}"); }
                });
                try {
                    assertEquals("snapshot",next(q).getString("type"));
                    assertTrue(api.rpc("session/prompt",Wire.prompt(sid,UUID.randomUUID().toString(),
                            turn==0?"看图":"继续",turn==0?jpeg:null)).getBoolean("accepted"));
                    Reply r=new Reply();
                    while (!r.ended) {
                        r.accept(next(q));
                        if (turn==0 && r.body().equals("紫")) {
                            p.getOutputStream().write("advance\n".getBytes()); p.getOutputStream().flush();
                        }
                    }
                    assertTrue("completed turn "+turn,r.completed); assertEquals(turn==0?"紫色":"第二轮",r.body());
                } finally { ws.close(1000,""); }
            }
            assertEquals("signed-out",api.rpc("account/signOut",Wire.obj("client",Wire.client())).getString("status"));
            p.getOutputStream().write("done\n".getBytes()); p.getOutputStream().flush();
            assertTrue(p.waitFor(8,TimeUnit.SECONDS)); assertEquals("model-side fixture observations",0,p.exitValue());
        } finally {
            if (api!=null) api.close();
            p.getOutputStream().close();
            if (!p.waitFor(5,TimeUnit.SECONDS)) p.destroyForcibly();
        }
    }
    private static JSONObject next(BlockingQueue<String> q) throws Exception {
        String raw=q.poll(40,TimeUnit.SECONDS); assertNotNull("stream item timeout",raw);
        JSONObject msg=new JSONObject(raw); assertEquals("item",msg.optString("type"));
        return msg.getJSONObject("value");
    }
}
