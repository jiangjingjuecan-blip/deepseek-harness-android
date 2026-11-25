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
            Paint background=new Paint(); background.setColor(Color.WHITE);
            renderer.renderPageToGraphics(index,background,new Canvas(bitmap),
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
        Attachment repeated=plain("repeat.txt","abc");
        JSONArray shared=prompt(Arrays.asList(repeated,repeated),3);
        assertEquals("[文件：repeat.txt]\na\n…",shared.getJSONObject(0).getString("text"));
        assertEquals("[文件：repeat.txt]\nab\n…",shared.getJSONObject(1).getString("text"));
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
        try (History db=new History(ctx)) { assertEquals(6,db.getReadableDatabase().getVersion()); }
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
    @Test public void ninePickedPhotosFitEvenAfterAttachingScanPages() throws Exception {
        Path dest=ctx.getFilesDir().toPath().resolve("photos/test.jpg");
        Files.createDirectories(dest.getParent()); Files.copy(fixture("photo.jpg"),dest);
        Attachment item=Attachment.photo("photos/test.jpg");
        try (History db=new History(ctx)) {
            long chat=db.create();
            assertTrue(db.addDraft(chat,pick(fixture("scan.pdf"),2)));
            for (int i=0;i<9;i++) assertTrue(db.addDraft(chat,item));
            assertFalse(db.addDraft(chat,item));
            List<Attachment> draft=db.draft(chat);
            assertEquals(9,Attachment.photos(draft));
            JSONArray content=Wire.prompt(ctx,"s","r","",draft,0).getJSONObject("request").getJSONArray("content");
            assertEquals(12,content.length());
            for (int i=1;i<=11;i++) {
                JSONObject b=content.getJSONObject(i); assertEquals("image",b.getString("type"));
                assertEquals("image/jpeg",b.getString("mediaType")); assertFalse(b.getString("data").isEmpty());
            }
            assertEquals("照片11.jpg",content.getJSONObject(11).getString("name"));
        }
    }
    private JSONArray prompt(List<Attachment> files,int bytes) throws Exception {
        return Wire.prompt(ctx,"s","r","",files,bytes).getJSONObject("request").getJSONArray("content");
    }
    private byte[] jpeg(Attachment file,int i) throws IOException {
        return Files.readAllBytes(Attachment.file(ctx,file.imagePaths().get(i)).toPath());
    }
    private void pictured(String ext,String first,String middle,String last) throws Exception {
        Attachment file=pick(fixture("pictures."+ext),9);
        String body=local(file);
        assertEquals(ext+" images",2,file.imageCount());
        int before=body.indexOf(first), after=body.indexOf(middle), tail=body.indexOf(last);
        assertTrue(ext+" layout: "+body,before>=0 && before+first.length()<=file.imageOffsets.get(0)
                && file.imageOffsets.get(0)<after && after<file.imageOffsets.get(1) && file.imageOffsets.get(1)<tail);
        JSONArray content=prompt(Collections.singletonList(file),65536);
        assertEquals(5,content.length());
        assertTrue(content.getJSONObject(0).getString("text").contains(first));
        for (int i=0;i<2;i++) {
            String mark=Attachment.marker(i+1);
            assertTrue(body.startsWith(mark,file.imageOffsets.get(i)));
            assertTrue(content.getJSONObject(2*i).getString("text").endsWith(mark));
            JSONObject image=content.getJSONObject(2*i+1);
            assertEquals(file.name+" "+mark+".jpg",image.getString("name"));
            assertArrayEquals(jpeg(file,i),Base64.getDecoder().decode(image.getString("data")));
            Bitmap b=Photos.load(ctx,file.imagePaths().get(i),1600);
            int rgb=b.getPixel(b.getWidth()/2,b.getHeight()/2);
            if (i==0) {
                assertTrue(Color.red(rgb)>200 && Color.blue(rgb)<70);
                if (!"pdf".equals(ext)) assertEquals(1600,b.getWidth());
            } else assertTrue(Color.blue(rgb)>180 && Color.red(rgb)<70);
            b.recycle();
        }
        assertTrue(content.getJSONObject(4).getString("text").contains(last));
    }
    @Test public void wordPicturesUseDrawingReferencesAndKeepLiteralMarkers() throws Exception {
        pictured("docx","旧文件正文[图 1]原文|","|中段","|段尾");
    }
    @Test public void slidePicturesFollowShapeAndPresentationOrder() throws Exception {
        pictured("pptx","PPT-A","PPT-B","PPT-C");
    }
    @Test public void worksheetPicturesFollowAnchorsAcrossEmptyRowsAndColumns() throws Exception {
        pictured("xlsx","XLS-A","XLS-B","XLS-C");
    }
    @Test public void pdfPicturesUsePageCoordinatesAcrossFormsAndInlineOperators() throws Exception {
        pictured("pdf","PDF-A","PDF-B","PDF-C");
        Attachment pages=pick(fixture("image-only.pdf"),3);
        assertEquals(Attachment.Kind.SCAN,pages.kind);
        assertEquals(1,pages.imageCount());
    }
    @Test public void reservesLaterPickedPhotosAndSpendsRemainingSlotsInFileOrder() throws Exception {
        Attachment first=pick(fixture("many-pictures.docx"),9), second=pick(fixture("pictures.docx"),9);
        assertEquals(Attachment.MAX_EMBEDDED_IMAGES,first.imageCount());
        assertTrue(local(first).contains("p10|p11|p12||end"));
        List<Attachment> files=new ArrayList<>(Arrays.asList(first,second));
        for (int i=0;i<9;i++) files.add(Attachment.photo(first.imagePaths().get(0)));
        JSONArray parts=prompt(files,65536);
        List<String> names=new ArrayList<>();
        for (int i=0;i<parts.length();i++) {
            JSONObject b=parts.getJSONObject(i);
            if ("image".equals(b.getString("type"))) names.add(b.getString("name"));
            else if (b.getString("text").contains("段尾")) assertFalse(b.getString("text").contains("[图 2]"));
        }
        assertEquals(20,names.size());
        for (int i=0;i<10;i++) assertEquals(first.name+" "+Attachment.marker(i+1)+".jpg",names.get(i));
        assertEquals(second.name+" [图 1].jpg",names.get(10));
        for (int i=11;i<20;i++) assertEquals("照片"+(i+1)+".jpg",names.get(i));
        assertTrue(local(second).contains("[图 2]"));
    }
    @Test public void truncatedAndUnsentPicturesLeaveNoPartialOrFalseMarker() throws Exception {
        Attachment file=pick(fixture("pictures.docx"),9);
        List<Attachment> files=Collections.singletonList(file);
        String before=local(file).substring(0,file.imageOffsets.get(0));
        int n=before.getBytes(StandardCharsets.UTF_8).length;
        for (int extra:new int[]{0,1,2,3,4,5,6}) {
            JSONArray parts=prompt(files,n+extra);
            assertEquals(1,parts.length());
            assertEquals(ContextUsage.fileLabel(file.name)+before+"\n…",parts.getJSONObject(0).getString("text"));
        }
        JSONArray tight=Wire.prompt(ctx,"s","r","",files,65536,0).getJSONObject("request").getJSONArray("content");
        assertEquals(1,tight.length());
        assertEquals(ContextUsage.fileLabel(file.name)+"旧文件正文[图 1]原文||中段||段尾\n",tight.getJSONObject(0).getString("text"));
    }
    @Test public void embeddedFilesKeepOffsetsAcrossReopenAndDeleteAfterLastReference() throws Exception {
        Attachment doc=pick(fixture("pictures.docx"),9);
        Diagnostics log=new Diagnostics(ctx.getNoBackupFilesDir());
        long chat,other;
        try (History db=new History(ctx)) {
            chat=db.create(); other=db.create(); assertTrue(db.addDraft(chat,doc));
            assertTrue(db.addDraft(other,Attachment.photo(doc.imagePaths().get(0))));
        }
        try (History db=new History(ctx)) {
            db.begin(chat,"",db.draft(chat));
            Attachment stored=db.messages(chat).get(0).attachments.get(0);
            assertEquals(doc.imageOffsets,stored.imageOffsets);
            assertEquals(prompt(Collections.singletonList(doc),65536).toString(),prompt(Collections.singletonList(stored),65536).toString());
            db.delete(chat,other,log);
            assertFalse(Attachment.file(ctx,doc.paths.get(0)).exists());
            assertFalse(Attachment.file(ctx,doc.imagePaths().get(1)).exists());
            assertTrue(Attachment.file(ctx,doc.imagePaths().get(0)).exists());
            db.delete(other,other,log);
            assertFalse(Attachment.file(ctx,doc.imagePaths().get(0)).exists());
        }
    }
    @Test public void softMaskIsFlattenedWithoutOtherPageArtwork() throws Exception {
        Attachment doc=pick(fixture("masked.pdf"),3);
        assertEquals(1,doc.imageCount());
        Bitmap image=Photos.load(ctx,doc.imagePaths().get(0),1600);
        try {
            assertTrue(Color.red(image.getPixel(20,40))>245);
            assertTrue(Color.red(image.getPixel(180,40))<70);
        } finally { image.recycle(); }
    }
    @Test public void highEntropyDocumentImageMeetsTheEncodedByteLimit() throws Exception {
        assertTrue(Documents.scratch(ctx).mkdirs() || Documents.scratch(ctx).isDirectory());
        Bitmap bitmap=Bitmap.createBitmap(1800,1600,Bitmap.Config.ARGB_8888);
        Random colors=new Random(738);
        int[] row=new int[1800];
        for (int y=0;y<1600;y++) {
            for (int x=0;x<row.length;x++) row[x]=0xff000000 | colors.nextInt(1<<24);
            bitmap.setPixels(row,0,row.length,0,y,row.length,1);
        }
        ByteArrayOutputStream source=new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,99,source)); bitmap.recycle();
        assertTrue(source.size()>768*1024);
        String path=Photos.importEmbedded(ctx,new ByteArrayInputStream(source.toByteArray()));
        File kept=Attachment.file(ctx,path);
        assertTrue(kept.length()>0 && kept.length()<=768*1024);
        Bitmap b=Photos.load(ctx,path,1600);
        assertTrue(b.getWidth()<=1600 && b.getHeight()<=1600); b.recycle();
    }
    @Test public void failedOfficeImportRemovesPicturesAlreadyWritten() throws Exception {
        Path bad=temp.resolve("broken.docx");
        try (ZipInputStream in=new ZipInputStream(Files.newInputStream(fixture("pictures.docx")));
             ZipOutputStream output=new ZipOutputStream(Files.newOutputStream(bad))) {
            for (ZipEntry e;(e=in.getNextEntry())!=null;) {
                output.putNextEntry(new ZipEntry(e.getName()));
                ByteArrayOutputStream copy=new ByteArrayOutputStream(); byte[] buf=new byte[2048]; int n;
                while ((n=in.read(buf))!=-1) copy.write(buf,0,n);
                if ("word/document.xml".equals(e.getName()))
                    output.write(copy.toString("UTF-8").replace("</w:body>","</broken>").getBytes(StandardCharsets.UTF_8));
                else output.write(copy.toByteArray());
                output.closeEntry();
            }
        }
        assertThrows(IOException.class,()->pick(bad,3));
        String[] pictures=new File(ctx.getFilesDir(),"photos").list();
        assertNotNull(pictures); assertEquals(0,pictures.length);
        assertEquals(0,Documents.scratch(ctx).list().length);
    }

}
