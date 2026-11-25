package com.sunwin.deepseekharness;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.*;
import static org.junit.Assert.*;

/** End-to-end replay against official DSH exercising native web search, read_url signature round-trip, errors, and AI titles. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE, application=HarnessApp.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WebToolsReplayTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test(timeout=180000) public void nativeWebSearchUrlReadingMultiTurnSignatureAndTitles() throws Exception {
        String abi=System.getenv("HARNESS_REPLAY");
        Assume.assumeTrue("opt-in native runtime replay",abi!=null && !abi.isEmpty());
        HarnessApp app=(HarnessApp)RuntimeEnvironment.getApplication();
        Path script=Paths.get("../tools/replay.py").toAbsolutePath().normalize();
        Process p=new ProcessBuilder("python3",script.toString(),abi,folder.getRoot().getPath(),"web")
                .redirectError(new File("/dev/null")).start();
        Loopback api=null;
        try {
            String launch=new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            Matcher ready=Pattern.compile("dsh web: http://127\\.0\\.0\\.1:(\\d+)(/\\?token=\\S+)").matcher(launch==null?"":launch);
            assertTrue("local process ready",ready.matches());
            api=new Loopback(Integer.parseInt(ready.group(1)),app.log);
            api.bootstrap(ready.group(2));
            app.connected(api);
            await("account ready",()->app.account.confirmed && "deepseek-flash".equals(app.account.selected));

            ChatEngine chat=new ChatEngine(app,folder.getRoot().getPath());
            History db=app.history;
            long id=db.create();

            // Turn 1: Web search ON (default). Live searching status chip appears, then collapses; citations + AI title persist.
            assertTrue(app.webSearchEnabled());
            assertTrue(chat.send(id,"冰岛人口",Collections.emptyList()));
            await("live searching status",()->chat.latest!=null && chat.latest.webStatus.contains("Iceland population 2025"));
            command(p,"advance");
            await("turn 1 done",()->!chat.busy());
            History.Message a1=db.messages(id).get(1);
            assertEquals(a1.error,"done",a1.state);
            assertNull(a1.error);
            assertEquals("冰岛人口约为 **39.4 万**，2024 年约为 **40.46 万**。",a1.text);
            assertEquals(2,a1.sources.size());
            assertEquals("华尔街见闻",a1.sources.get(0).label());
            assertEquals("华经情报网",a1.sources.get(1).label());
            assertNotNull("server tool blocks saved",a1.webBlocks);
            assertEquals("冰岛人口统计",db.chat(id).title);
            assertEquals(ChatTitle.SOURCE_AI,db.chat(id).titleSource);

            // Turn 2: Toggle web search OFF -> only deepseek_read_url declared, prior web blocks round-tripped, read_link signature saved.
            app.webSearchEnabled(false);
            assertTrue(chat.send(id,"总结 https://example.com/post",Collections.emptyList()));
            await("turn 2 done",()->!chat.busy());
            History.Message a2=db.messages(id).get(3);
            assertEquals(a2.error,"done",a2.state);
            assertEquals("该页面介绍了示例域名用途。",a2.text);
            assertEquals(1,a2.sources.size());
            assertEquals("Example Post",a2.sources.get(0).label());
            assertNotNull(a2.webBlocks);
            assertTrue(a2.webBlocks.contains("sig-example-post-v1"));
            assertEquals("冰岛人口统计",db.chat(id).title);

            // Turn 3: Multi-turn follow-up on live session sends read_link signature back & refreshes AI title at turn 3.
            assertTrue(chat.send(id,"引用第一句",Collections.emptyList()));
            await("turn 3 done",()->!chat.busy());
            History.Message a3=db.messages(id).get(5);
            assertEquals(a3.error,"done",a3.state);
            assertEquals("第一句为 Example Domain。",a3.text);
            assertEquals("冰岛与网页摘要",db.chat(id).title);

            // Turn 4: Force SQLite /phone-restore by marking the branch dirty & clearing remote anchors -> signature STILL survives!
            db.getWritableDatabase().execSQL("UPDATE message SET anchor_remote=NULL,anchor_seq=NULL WHERE chat=?",new Object[]{id});
            db.dirty(db.chat(id).branch);
            assertTrue(chat.send(id,"恢复后继续",Collections.emptyList()));
            await("turn 4 done",()->!chat.busy());
            History.Message a4=db.messages(id).get(7);
            assertEquals(a4.error,"done",a4.state);
            assertEquals("已恢复上下文。",a4.text);

            // Turn 5: Rich Markdown + LaTeX math reply
            assertTrue(chat.send(id,"公式展示",Collections.emptyList()));
            await("turn 5 done",()->!chat.busy());
            History.Message a5=db.messages(id).get(9);
            assertEquals(a5.error,"done",a5.state);
            assertTrue(a5.text.contains("\\Bigg|_{\\theta=\\pi/5,\\;3\\pi/5}"));
            assertTrue(a5.text.contains("$5 and $10"));

            // Turn 6: Search tool unavailable error -> reply still renders, error saved gracefully
            app.webSearchEnabled(true);
            assertTrue(chat.send(id,"搜索出错测试",Collections.emptyList()));
            await("turn 6 done",()->!chat.busy());
            History.Message a6=db.messages(id).get(11);
            assertEquals("done",a6.state);
            assertEquals("暂时无法联网，以下为离线回答。",a6.text);
            assertEquals("联网搜索失败",a6.error);

            // Turn 7: HTTP 400 Content Exists Risk -> graceful error styling
            assertTrue(chat.send(id,"风险测试",Collections.emptyList()));
            await("turn 7 done",()->!chat.busy());
            History.Message a7=db.messages(id).get(13);
            assertEquals("interrupted",a7.state);
            assertTrue(a7.error.contains("HTTP 400") && a7.error.contains("内容安全未通过"));

            // Non-flash model selected: with web search ON routes to deepseek-flash; with OFF uses deepseek-v4-pro + fallback title!
            long routeChat=db.create();
            app.account.selected="deepseek-v4-pro";
            app.webSearchEnabled(true);
            assertTrue(chat.send(routeChat,"切模型测试",Collections.emptyList()));
            await("flash-route done",()->!chat.busy());
            assertEquals("已切换至联网模型。",db.messages(routeChat).get(1).text);

            long proChat=db.create();
            app.webSearchEnabled(false);
            assertTrue(chat.send(proChat,"离线模型测试",Collections.emptyList()));
            await("pro-route done",()->!chat.busy());
            assertEquals("离线模型回答。",db.messages(proChat).get(1).text);
            await("fallback title applied",()->"离线模型话题".equals(db.chat(proChat).title));

            command(p,"done");
            assertTrue(p.waitFor(8,TimeUnit.SECONDS));
            assertEquals("web fixture observations",0,p.exitValue());
        } finally {
            app.webSearchEnabled(true);
            if (api!=null) api.close();
            p.getOutputStream().close();
            if (!p.waitFor(5,TimeUnit.SECONDS)) p.destroyForcibly();
        }
    }

    private static void await(String label,BooleanSupplier condition) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
        while (!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(15);
        assertTrue(label,condition.getAsBoolean());
    }
    private static void command(Process p,String value) throws IOException {
        p.getOutputStream().write((value+"\n").getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().flush();
    }
}
