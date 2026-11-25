package com.sunwin.deepseekharness;

import org.json.*;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE, application=HarnessApp.class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AdventureReplayTest {
    @Rule public TemporaryFolder folder=new TemporaryFolder();
    private Process fixture;
    private ChatEngine engine;
    private Loopback api;
    private History db;
    private long chat;

    @Before public void open() throws Exception {
        String abi=System.getenv("HARNESS_REPLAY");
        Assume.assumeTrue(abi!=null && !abi.isEmpty());
        HarnessApp app=(HarnessApp)RuntimeEnvironment.getApplication();
        Path script=Paths.get("../tools/replay.py").toAbsolutePath();
        fixture=new ProcessBuilder("python3",script.toString(),abi,folder.getRoot().getPath(),"adventure")
                .redirectError(new File("/dev/null")).start();
        String ready=new BufferedReader(new InputStreamReader(fixture.getInputStream())).readLine();
        Matcher m=Pattern.compile("dsh web: http://127\\.0\\.0\\.1:(\\d+)(/\\?token=\\S+)").matcher(ready==null?"":ready);
        assertTrue("fixture ready",m.matches());
        api=new Loopback(Integer.parseInt(m.group(1)),app.log); api.bootstrap(m.group(2)); app.connected(api);
        await(()->app.account.confirmed && !app.account.selected.isEmpty());
        engine=new ChatEngine(app,folder.getRoot().getPath()); db=app.history; chat=db.create();
    }
    @After public void close() throws Exception {
        if (api!=null) api.close();
        if (fixture!=null) {
            fixture.getOutputStream().close();
            if (!fixture.waitFor(8,TimeUnit.SECONDS)) fixture.destroyForcibly();
        }
    }
    private void pipe(String value) throws IOException {
        fixture.getOutputStream().write((value+"\n").getBytes(StandardCharsets.UTF_8)); fixture.getOutputStream().flush();
    }
    private void finish() throws Exception {
        pipe("done"); assertTrue(fixture.waitFor(15,TimeUnit.SECONDS)); assertEquals(0,fixture.exitValue());
    }
    private static void await(BooleanSupplier check) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        while (!check.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(15);
        assertTrue("fixture operation finished",check.getAsBoolean());
    }
    private void expect(String answer,String... turns) throws Exception {
        JSONArray messages=new JSONArray();
        for (int i=0;i<turns.length;i++) messages.put(new JSONArray().put(i%2==0?"user":"assistant").put(turns[i]));
        pipe(Wire.obj("answer",answer,"messages",messages).toString());
    }
    private History.Message answer(String text) throws Exception {
        await(()->!engine.busy());
        List<History.Message> rows=db.messages(chat);
        History.Message m=rows.get(rows.size()-1);
        assertEquals(text+" / "+m.error,"done",m.state); assertEquals(text,m.text);
        assertTrue("durable event boundary",m.seq>=0); return m;
    }
    private void send(String text) { assertTrue(engine.send(chat,text,Collections.emptyList())); }
    @Test(timeout=240000) public void providerReceivesOnlyChosenVersionsAndRolls() throws Exception {
        long main=db.chat(chat).branch;
        expect("青门","开场"); send("开场"); History.Message first=answer("青门");
        db.bookmark(main,first.id,true);
        Dice dice=Dice.roll("2d6+1"); db.roll(chat,dice);
        String question=dice.block()+"\n推门";
        expect("白兔","开场","青门",question); send("推门"); History.Message one=answer("白兔");
        long originalUser=db.last(main,one.id);
        assertTrue(db.at(main,originalUser).seq>=0);
        expect("红狐","开场","青门",question); assertTrue(engine.regenerate(chat,one.id)); History.Message two=answer("红狐");
        expect("银鹿","开场","青门",question); assertTrue(engine.regenerate(chat,two.id)); History.Message three=answer("银鹿");
        assertEquals(3,three.version); assertEquals(3,three.versions);
        long threeBranch=db.chat(chat).branch;
        assertEquals(two.id,db.switchVersion(chat,three.id,-1));
        assertEquals(one.id,db.switchVersion(chat,two.id,-1)); assertEquals(main,db.chat(chat).branch);
        assertEquals(two.id,db.switchVersion(chat,one.id,1));
        assertEquals(three.id,db.switchVersion(chat,two.id,1)); assertEquals(threeBranch,db.chat(chat).branch);

        long edited=db.edit(chat,three.id,"石碑");
        expect("月亮","开场","青门",question,"石碑","读碑"); send("读碑"); answer("月亮");
        assertEquals(4,db.at(db.chat(chat).branch,edited).version);
        assertEquals(three.id,db.switchVersion(chat,edited,-1));
        expect("鹿鸣","开场","青门",question,"银鹿","跟随"); send("跟随"); answer("鹿鸣");

        long user=db.edit(chat,originalUser,"关门");
        expect("门关了","开场","青门",dice.block()+"\n关门"); assertTrue(engine.answerUser(chat,user)); answer("门关了");
        db.selectBranch(chat,main); assertEquals(one.id,db.messages(chat).get(4).id);
        // A migrated v6 line has its old DSH ID but no per-row event cuts.
        String originalSession=db.chat(chat).remote;
        db.getWritableDatabase().execSQL("UPDATE message SET anchor_seq=NULL WHERE id<=? AND chat=?",new Object[]{first.id,chat});
        db.fork(chat,first.id);
        assertTrue(db.at(db.chat(chat).branch,first.id).saved);
        expect("树林","开场","青门","绕行"); send("绕行"); answer("树林");
        assertEquals(originalSession,db.at(main,first.id).session); assertTrue(db.at(main,first.id).seq>=0);
        Set<String> sessions=new HashSet<>();
        for (History.Branch branch:db.branches(chat)) {
            db.selectBranch(chat,branch.id);
            assertTrue("one private session per line",sessions.add(db.chat(chat).remote));
        }
        assertEquals(6,sessions.size()); finish();
    }
    @Test(timeout=240000) public void nativeForkCompactsOnlyItsOwnJournal() throws Exception {
        long main=db.chat(chat).branch;
        List<String> transcript=new ArrayList<>();
        String old="旧城"+String.join("",Collections.nCopies(400,"旧城"));
        History.Message last=null;
        for (String q:Arrays.asList("起点",old,"第三幕","第四幕","第五幕","第六幕")) {
            String a=q.equals(old)?"旧信":"答"+q;
            transcript.add(q); expect(a,transcript.toArray(new String[0])); send(q); last=answer(a); transcript.add(a);
        }
        long fork=db.fork(chat,last.id);
        pipe(Wire.obj("answer","城中摘要","summary",true,"include",new JSONArray().put(old).put("旧信"),
                "exclude",new JSONArray().put("第六幕")).toString());
        engine.compact(chat); await(()->!engine.busy());
        assertNotNull(db.chat(chat).remote);
        pipe(Wire.obj("answer","支线尾声","include",new JSONArray().put("起点").put("城中摘要").put("第六幕").put("支线问题"),
                "exclude",new JSONArray().put(old)).toString());
        send("支线问题"); answer("支线尾声");
        long used=db.meter(chat,"deepseek-flash").used;
        db.selectBranch(chat,main); transcript.add("主线问题");
        expect("主线尾声",transcript.toArray(new String[0])); send("主线问题"); answer("主线尾声");
        db.selectBranch(chat,fork); assertEquals(used,db.meter(chat,"deepseek-flash").used);
        assertEquals(14,db.messages(chat).size()); finish();
    }
    @Test(timeout=180000) public void regenerateAndEditAttachmentQuestionResendTheOriginalPixels() throws Exception {
        HarnessApp app=(HarnessApp)RuntimeEnvironment.getApplication();
        Path photo=app.getFilesDir().toPath().resolve("photos/pixel.jpg");
        Files.createDirectories(photo.getParent()); Files.copy(Paths.get("src/test/resources/photo.jpg"),photo);
        expectPhoto("识物","玉石");
        assertTrue(engine.send(chat,"识物",Collections.singletonList(Attachment.photo("photos/pixel.jpg"))));
        long first=answer("玉石").id;
        expectPhoto("识物","青玉"); assertTrue(engine.regenerate(chat,first)); long second=answer("青玉").id;
        long user=db.last(db.chat(chat).branch,second);
        long edit=db.edit(chat,user,"辨色");
        expectPhoto("辨色","青色"); assertTrue(engine.answerUser(chat,edit)); answer("青色"); finish();
    }
    private void expectPhoto(String question,String answer) throws Exception {
        pipe(Wire.obj("answer",answer,"photoQuestion",question,"images",new JSONArray().put("file-pixel")).toString());
    }
    @Test(timeout=120000) public void localPrefixWithoutEventIdsRestoresRoleAndDocumentPositions() throws Exception {
        HarnessApp app=(HarnessApp)RuntimeEnvironment.getApplication();
        Path file=Paths.get("src/test/resources/pictures.docx");
        Attachment doc=Documents.importFile(app,android.net.Uri.fromFile(file.toFile()),5);
        long row=db.begin(chat,"阅读",Collections.singletonList(doc)); db.update(row,"笔记","done");
        db.link(chat,"missing-v6-fixture");
        db.getWritableDatabase().execSQL("UPDATE message SET anchor_remote=? WHERE chat=?",new Object[]{"missing-v6-fixture",chat});
        db.fork(chat,row);
        expect("续写完成","阅读[文件：pictures.docx]\n旧文件正文[图 1]原文|[图 1][图片：pictures.docx [图 1].jpg]|中段|[图 2][图片：pictures.docx [图 2].jpg]|段尾\n","笔记","续写");
        send("续写"); answer("续写完成"); finish();
    }
}
