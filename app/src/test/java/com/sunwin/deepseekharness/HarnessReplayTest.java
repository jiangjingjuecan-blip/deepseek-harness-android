package com.sunwin.deepseekharness;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE, application=HarnessApp.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class HarnessReplayTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();

    @Test(timeout=180000) public void officialWebImageStreamingAndContinuation() throws Exception {
        String abi=System.getenv("HARNESS_REPLAY");
        Assume.assumeTrue("opt-in native runtime replay",abi!=null && !abi.isEmpty());
        HarnessApp app=(HarnessApp)RuntimeEnvironment.getApplication();
        Path script=Paths.get("../tools/replay.py").toAbsolutePath().normalize();
        Process p=new ProcessBuilder("python3",script.toString(),abi,folder.getRoot().getPath()).redirectError(new File("/dev/null")).start();
        Loopback api=null;
        try {
            String launch=new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            Matcher ready=Pattern.compile("dsh web: http://127\\.0\\.0\\.1:(\\d+)(/\\?token=\\S+)").matcher(launch==null?"":launch);
            assertTrue("local process ready",ready.matches());
            api=new Loopback(Integer.parseInt(ready.group(1)),app.log);
            api.bootstrap(ready.group(2));
            app.connected(api);
            await("account details",()->app.account.confirmed && !app.account.balance.isEmpty());
            assertTrue(app.account.stored);
            assertEquals("deepseek-flash",app.account.selected);
            assertEquals("¥3.25",app.account.balance);

            // The Linux fixture and Android guest have different private working directories.
            ChatEngine chat=new ChatEngine(app,folder.getRoot().getPath());
            long id=app.history.create();
            String photo="pixel.jpg";
            Files.copy(Paths.get("src/test/resources/photo.jpg"),new File(app.getFilesDir(),photo).toPath());
            String[] prompts={"看图","继续","额度回放","额度回放看图"};
            for (int turn=0;turn<prompts.length;turn++) {
                String image=(turn==0 || turn==3) ? photo : null;
                assertTrue(chat.send(id,prompts[turn],image));
                if (turn==0) {
                    await("first delta written to SQLite",()->!chat.busy || "紫".equals(app.history.messages(id).get(1).text));
                    assertEquals("紫",app.history.messages(id).get(1).text);
                    command(p,"advance");
                }
                await("reply finished",()->!chat.busy);
                History.Message answer=app.history.messages(id).get(2*turn+1);
                if (turn>=2) {
                    assertEquals("interrupted",answer.state); assertEquals("",answer.text);
                    assertEquals("HTTP 402 · 账号额度不足",answer.error);
                } else {
                    assertEquals("done",answer.state); assertNull(answer.error);
                    assertEquals(turn==0 ? "紫色" : "第二轮",answer.text);
                }
            }
            assertNotNull(app.history.chat(id).remote);
            ByteArrayOutputStream exported=new ByteArrayOutputStream();
            app.log.export(exported);
            assertTrue(exported.toString("UTF-8").contains("CHAT_INTERRUPTED REPLY HTTP 402 · 账号额度不足 [ACCOUNT_QUOTA]"));
            app.account.signOut();
            await("fixture sign-out",()->!app.account.loading && !app.account.stored);
            command(p,"done");
            assertTrue(p.waitFor(8,TimeUnit.SECONDS));
            assertEquals("model-side fixture observations",0,p.exitValue());
        } finally {
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
        p.getOutputStream().write((value+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        p.getOutputStream().flush();
    }
}
