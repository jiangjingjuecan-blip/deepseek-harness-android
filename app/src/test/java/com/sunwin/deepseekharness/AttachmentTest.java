package com.sunwin.deepseekharness;

import android.app.Application;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import org.robolectric.shadow.api.Shadow;
import android.net.Uri;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, application=Application.class, shadows={AttachmentTest.Renderer.class,AttachmentTest.Page.class})
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AttachmentTest {
    // The host JVM has no Android PDFium. Draw the same fixture with PDFBox here.
    // DeviceAttachments also checks the platform PdfRenderer in the installed APK.
    @Implements(PdfRenderer.class)
    public static class Renderer {
        PDDocument document;
        @Implementation protected void __constructor__(ParcelFileDescriptor fd) throws IOException {
            document=PDDocument.load(new FileInputStream(fd.getFileDescriptor()));
        }
        @Implementation protected int getPageCount() { return document.getNumberOfPages(); }
        @Implementation protected PdfRenderer.Page openPage(int index) {
            PdfRenderer.Page page=Shadow.newInstanceOf(PdfRenderer.Page.class);
            Page shadow=Shadow.extract(page); shadow.document=document; shadow.index=index;
            return page;
        }
        @Implementation protected void close() throws IOException { document.close(); }
    }
    @Implements(PdfRenderer.Page.class)
    public static class Page {
        PDDocument document; int index;
        @Implementation protected int getWidth() { return (int)document.getPage(index).getMediaBox().getWidth(); }
        @Implementation protected int getHeight() { return (int)document.getPage(index).getMediaBox().getHeight(); }
        @Implementation protected void render(Bitmap bitmap,Rect clip,Matrix matrix,int mode) throws IOException {
            com.tom_roush.pdfbox.rendering.PDFRenderer renderer=new com.tom_roush.pdfbox.rendering.PDFRenderer(document);
            renderer.renderPageToGraphics(index,new Paint(),new Canvas(bitmap),
                    (float)bitmap.getWidth()/getWidth(),(float)bitmap.getHeight()/getHeight());
        }
        @Implementation protected void close() {}
    }
    private Application ctx;
    private Path temp;
    @Before public void before() throws Exception {
        ctx=RuntimeEnvironment.getApplication();
        temp=Files.createTempDirectory(ctx.getCacheDir().toPath(),"import");
    }
    private Attachment pick(Path path,int slots) throws IOException {
        return Documents.importFile(ctx,Uri.fromFile(path.toFile()),slots);
    }
    private String local(Attachment item) throws IOException {
        return new String(Files.readAllBytes(Attachment.file(ctx,item.paths.get(0)).toPath()),StandardCharsets.UTF_8);
    }
    private Path fixture(String name) { return Paths.get("src/test/resources",name); }
    private Attachment plain(String name,String text) throws IOException {
        return pick(Files.write(temp.resolve(name),text.getBytes(StandardCharsets.UTF_8)),9);
    }
    private Path office(String name,String... parts) throws IOException {
        Path file=temp.resolve(name);
        try (ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(file))) {
            for (int i=0;i<parts.length;i+=2) {
                zip.putNextEntry(new ZipEntry(parts[i])); zip.write(parts[i+1].getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
        return file;
    }
    @Test public void plainFilesPreserveUnicodeAndFormattingAndRejectBinary() throws Exception {
        for (String ext:new String[]{"txt","md","csv","json","kt","py"})
            assertEquals("  春🌷\nA,B\t1",local(plain("测试."+ext,"  春🌷\nA,B\t1")));
        Path utf16=Files.write(temp.resolve("bom.txt"),"安卓".getBytes(StandardCharsets.UTF_16));
        assertEquals("安卓",local(pick(utf16,9)));
        assertThrows(IOException.class,()->plain("bad.txt","ok\u0000binary"));
        assertThrows(IOException.class,()->pick(Files.copy(fixture("photo.jpg"),temp.resolve("legacy.doc")),9));
        assertThrows(IOException.class,()->plain("unknown.exe","hello"));
    }
    @Test public void officeExtractsRunsParagraphsCellsAndNumberedSlides() throws Exception {
        String xml="<w:document xmlns:w='urn:w'><w:p><w:r><w:t>段落 &amp; </w:t></w:r><w:r><w:t>二</w:t></w:r></w:p></w:document>";
        assertEquals("段落 & 二\n",local(pick(office("文章.docx","word/document.xml",xml),9)));
        Attachment sheet=pick(office("表.xlsx","xl/sharedStrings.xml","<sst><si><r><t>订</t></r><r><t>单</t></r></si></sst>",
                "xl/worksheets/sheet1.xml","<worksheet><row><c t='s'><v>0</v></c><c><v>12</v></c><c t='inlineStr'><is><t>纸</t></is></c></row></worksheet>"),9);
        assertEquals("订单\t12\t纸",local(sheet).trim());
        Attachment slides=pick(office("演示.pptx","ppt/slides/slide10.xml","<p><t>末页</t></p>",
                "ppt/slides/slide2.xml","<p><t>首页</t></p>"),9);
        assertEquals("首页\n\n末页",local(slides).trim());
        String dtd="<!DOCTYPE document [<!ENTITY note 'unexpected'>]><document><t>&note;</t></document>";
        assertThrows(IOException.class,()->pick(office("invalid.docx","word/document.xml",dtd),9));
    }
    @Test public void pdfTextAndBoundedRenderedPagesUseActualStoredFiles() throws Exception {
        Attachment text=pick(fixture("text.pdf"),0);
        assertEquals(Attachment.Kind.TEXT,text.kind); assertEquals("Fixture PDF text",local(text).trim());
        Attachment scan=pick(fixture("scan.pdf"),9);
        assertEquals(Attachment.Kind.SCAN,scan.kind); assertEquals(3,scan.paths.size());
        for (String path:scan.paths) {
            Bitmap b=Photos.load(ctx,path,200);
            assertTrue(b.getWidth()>=200 && b.getWidth()<=400);
            assertNotEquals(android.graphics.Color.WHITE,b.getPixel(b.getWidth()/2,b.getHeight()/2));
        }
        assertEquals(1,pick(fixture("scan.pdf"),1).imageCount());
        assertThrows(Documents.ImageLimit.class,()->pick(fixture("scan.pdf"),0));
        JSONArray parts=Wire.prompt(ctx,"s","r","",Collections.singletonList(scan),0).getJSONObject("request").getJSONArray("content");
        assertEquals(4,parts.length()); assertTrue(parts.getJSONObject(0).getString("text").contains("scan.pdf"));
        assertEquals("scan.pdf - 3.jpg",parts.getJSONObject(3).getString("name"));
    }
    @Test public void filesShareBudgetAtUtf8BoundariesAndHistoryKeepsLongerPrefix() throws Exception {
        String many=String.join("",Collections.nCopies(70000,"🌷春"));
        Attachment large=plain("长.md",many), shortFile=plain("小.txt","短");
        assertTrue(local(large).endsWith("\n…")); assertTrue(local(large).length()<=Documents.MAX_CHARS+2);
        assertFalse(local(large).contains("�"));
        List<Attachment> docs=Arrays.asList(large,shortFile);
        long base=ContextUsage.estimate("读",docs);
        assertEquals("files "+base+" 65536 2",ContextUsage.argument(base,docs));
        JSONArray blocks=Wire.prompt(ctx,"s","r","读",docs,11).getJSONObject("request").getJSONArray("content");
        assertEquals("[文件：长.md]\n🌷春\n…",blocks.getJSONObject(1).getString("text"));
        assertEquals("[文件：小.txt]\n短",blocks.getJSONObject(2).getString("text"));
        assertTrue(local(large).startsWith("🌷春🌷春"));
        Path oversized=temp.resolve("oversize.txt");
        try (RandomAccessFile file=new RandomAccessFile(oversized.toFile(),"rw")) { file.setLength(Documents.MAX_INPUT+1L); }
        assertThrows(IOException.class,()->pick(oversized,9));
    }
    @Test public void realV4RowMigratesOnceAndThumbnailDecodesAfterReopen() throws Exception {
        Path old=ctx.getFilesDir().toPath().resolve("photos/old.jpg");
        Files.createDirectories(old.getParent()); Files.copy(fixture("photo.jpg"),old);
        try (SQLiteDatabase db=ctx.openOrCreateDatabase("chats.db",0,null)) {
            db.execSQL("CREATE TABLE chat(id INTEGER PRIMARY KEY,title TEXT NOT NULL,remote TEXT UNIQUE,updated INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE message(id INTEGER PRIMARY KEY,chat INTEGER NOT NULL REFERENCES chat(id),role TEXT NOT NULL,body TEXT NOT NULL,photo TEXT,state TEXT NOT NULL,error TEXT)");
            db.execSQL("CREATE TABLE context_meter(chat INTEGER PRIMARY KEY REFERENCES chat(id) ON DELETE CASCADE,model TEXT NOT NULL,used INTEGER NOT NULL,capacity INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE photo_cleanup(photo TEXT PRIMARY KEY NOT NULL)");
            db.execSQL("INSERT INTO chat VALUES(11,'旧图','fixture-session',100)");
            db.execSQL("INSERT INTO message VALUES(42,11,'user','图',?,'done',NULL)",new Object[]{old.toString()}); db.setVersion(4);
        }
        try (History db=new History(ctx)) { assertEquals(5,db.getReadableDatabase().getVersion()); }
        try (History db=new History(ctx)) {
            History.Message message=db.messages(11).get(0);
            assertEquals(42,message.id); assertEquals("fixture-session",db.chat(11).remote); assertEquals(1,message.attachments.size());
            assertEquals(2,Photos.load(ctx,message.attachments.get(0).paths.get(0),200).getWidth());
            assertTrue(db.draft(11).isEmpty());
        }
    }
    @Test public void draftsPersistAndOnlyUnsharedImagesPagesAndDocumentsAreRemoved() throws Exception {
        Attachment doc=plain("备忘.txt","共享"), other=plain("临时.md","独占");
        Attachment scan=pick(fixture("scan.pdf"),2);
        Diagnostics log=new Diagnostics(ctx.getNoBackupFilesDir());
        Path interrupted=Files.write(Documents.scratch(ctx).toPath().resolve("source-interrupted.part"),new byte[]{1,2,3});
        Documents.clearInterruptedImports(ctx,log);
        assertFalse(Files.exists(interrupted));
        assertEquals("共享",local(doc));
        long old,keep;
        try (History db=new History(ctx)) {
            old=db.create(); keep=db.create();
            assertTrue(db.addDraft(old,doc)); assertTrue(db.addDraft(old,scan)); assertTrue(db.addDraft(keep,doc));
        }
        try (History db=new History(ctx)) {
            List<Attachment> draft=db.draft(old);
            assertEquals(2,draft.size()); assertEquals(2,draft.get(1).imageCount());
            db.begin(old,"",draft); assertTrue(db.draft(old).isEmpty()); assertEquals(2,db.messages(old).get(0).attachments.size());
            assertTrue(db.addDraft(old,other)); db.delete(old,keep,log);
            for (String p:scan.paths) assertFalse(Attachment.file(ctx,p).exists());
            assertFalse(Attachment.file(ctx,other.paths.get(0)).exists()); assertEquals("共享",local(db.draft(keep).get(0)));
            db.removeDraft(keep,db.draft(keep).get(0).id,log); assertFalse(Attachment.file(ctx,doc.paths.get(0)).exists());
        }
    }
    @Test public void nineOrderedImagesInOnePromptAndDraftCapIncludesScanPages() throws Exception {
        Path dest=ctx.getFilesDir().toPath().resolve("photos/test.jpg");
        Files.createDirectories(dest.getParent()); Files.copy(fixture("photo.jpg"),dest);
        Attachment item=Attachment.photo("photos/test.jpg");
        try (History db=new History(ctx)) {
            long chat=db.create();
            assertTrue(db.addDraft(chat,pick(fixture("scan.pdf"),2)));
            for (int i=0;i<7;i++) assertTrue(db.addDraft(chat,item));
            assertFalse(db.addDraft(chat,item));
            List<Attachment> draft=db.draft(chat);
            assertEquals(9,Attachment.images(draft));
            JSONArray content=Wire.prompt(ctx,"s","r","",draft,0).getJSONObject("request").getJSONArray("content");
            assertEquals(10,content.length());
            for (int i=1;i<=9;i++) {
                JSONObject b=content.getJSONObject(i); assertEquals("image",b.getString("type"));
                assertEquals("image/jpeg",b.getString("mediaType")); assertFalse(b.getString("data").isEmpty());
            }
            assertEquals("照片9.jpg",content.getJSONObject(9).getString("name"));
        }
    }
}
