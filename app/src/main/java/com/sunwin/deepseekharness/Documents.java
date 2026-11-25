package com.sunwin.deepseekharness;

import android.content.Context;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.Files;
import java.util.*;

final class Documents {
    static final int MAX_INPUT=16*1024*1024, MAX_CHARS=128*1024, PDF_PAGES=64, SCAN_PAGES=3;
    private static final Set<String> PLAIN=new HashSet<>(Arrays.asList(
            "txt md markdown csv tsv json jsonl yaml yml xml html htm css scss js jsx ts tsx java kt kts py c h cpp hpp cc cs go rs rb php swift sh bash zsh sql toml ini conf cfg properties gradle log diff patch tex r lua dart vue srt doc".split(" ")));

    static File scratch(Context ctx) { return new File(ctx.getCacheDir(),"document-imports"); }
    static void clearInterruptedImports(Context ctx,Diagnostics log) {
        // No import is running yet during Application.onCreate. A process kill can
        // leave the original stream or PDFBox's scratch file here before a commit.
        File[] files=scratch(ctx).listFiles();
        if (files!=null) for (File file:files) if (file.isFile() && !file.delete()) log.add(Diagnostics.Event.PHOTO_DELETE_FAILED);
    }
    static final class ImageLimit extends IOException {}
    static final class Full extends IOException {}
    static final class Text extends Writer {
        final StringBuilder buffer=new StringBuilder();
        final List<String> images=new ArrayList<>();
        final List<Integer> offsets=new ArrayList<>();
        boolean canTakeImage() {
            return images.size()<Attachment.MAX_EMBEDDED_IMAGES &&
                    buffer.length()+Attachment.marker(images.size()+1).length()<=MAX_CHARS;
        }
        void image(String path) {
            if (path==null) return;
            offsets.add(buffer.length());
            images.add(path);
            buffer.append(Attachment.marker(images.size()));
        }
        boolean more;
        @Override public void write(char[] chars,int start,int count) throws IOException {
            int n=Math.min(count,MAX_CHARS-buffer.length());
            buffer.append(chars,start,n);
            if (n<count) { more=true; throw new Full(); }
        }
        @Override public void write(String text,int start,int count) throws IOException {
            int n=Math.min(count,MAX_CHARS-buffer.length());
            buffer.append(text,start,start+n);
            if (n<count) { more=true; throw new Full(); }
        }
        @Override public void flush() {}
        @Override public void close() {}
        String value() {
            int n=buffer.length();
            if (n>0 && Character.isHighSurrogate(buffer.charAt(n-1))) buffer.setLength(n-1);
            return buffer.toString()+(more ? "\n…" : "");
        }
    }
    static String name(Context context,Uri uri) {
        String name=uri.getLastPathSegment();
        if ("content".equals(uri.getScheme())) {
            try (Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
                if (c!=null && c.moveToFirst() && !c.isNull(0)) name=c.getString(0);
            }
        }
        if (name==null) return "文件";
        name=name.substring(Math.max(name.lastIndexOf('/'),name.lastIndexOf('\\'))+1)
                .replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]","").trim();
        if (name.isEmpty()) return "文件";
        if (name.length()>160) name=name.substring(0,100)+"…"+name.substring(name.length()-50);
        return name;
    }
    static Attachment importFile(Context ctx,Uri uri,int imageSlots) throws IOException {
        String name=name(ctx,uri);
        String ext=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        boolean plain=PLAIN.contains(ext) || "Makefile".equalsIgnoreCase(name) || "Dockerfile".equalsIgnoreCase(name);
        if (!plain && !Arrays.asList("pdf","docx","xlsx","pptx").contains(ext)) throw new IOException("format");
        File dir=scratch(ctx);
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("document-dir");
        File tmp=File.createTempFile("source-",".part",dir);
        Text out=new Text();
        boolean saved=false;
        try {
            try (InputStream in=ctx.getContentResolver().openInputStream(uri); OutputStream output=new FileOutputStream(tmp)) {
                if (in==null) throw new IOException("open");
                byte[] bytes=new byte[8192]; int total=0,n;
                while ((n=in.read(bytes))!=-1) {
                    total+=n;
                    if (total>MAX_INPUT) throw new IOException("size");
                    output.write(bytes,0,n);
                }
            }

            try {
                if (plain) readPlain(tmp,out);
                else if ("pdf".equals(ext)) {
                    PDFBoxResourceLoader.init(ctx.getApplicationContext());
                    if (!PdfText.read(ctx,tmp,out)) return scan(ctx,tmp,name,imageSlots);
                } else OfficeText.read(ctx,tmp,ext,out);
            } catch (Full enough) { out.more=true; }
            if (out.buffer.toString().trim().isEmpty()) {
                throw new IOException("empty");
            }
            byte[] bytes=out.value().getBytes(StandardCharsets.UTF_8);
            String path=Attachment.newPath(ctx,"documents",".txt");
            File copy=Attachment.file(ctx,path);
            try { Files.write(copy.toPath(),bytes); }
            catch (IOException e) { copy.delete(); throw e; }
            List<String> paths=new ArrayList<>();
            paths.add(path); paths.addAll(out.images);
            Attachment item=new Attachment(Attachment.Kind.TEXT,name,bytes.length,paths,out.offsets);
            saved=true;
            return item;
        } finally {
            if (!saved) for (String image:out.images) Attachment.file(ctx,image).delete();
            tmp.delete();
        }
    }
    private static void readPlain(File file,Text out) throws IOException {
        try (BufferedInputStream input=new BufferedInputStream(new FileInputStream(file))) {
            input.mark(4);
            int a=input.read(), b=input.read(), c=input.read();
            Charset encoding=StandardCharsets.UTF_8;
            int skip=0;
            if (a==0xff && b==0xfe) { encoding=StandardCharsets.UTF_16LE; skip=2; }
            else if (a==0xfe && b==0xff) { encoding=StandardCharsets.UTF_16BE; skip=2; }
            else if (a==0xef && b==0xbb && c==0xbf) skip=3;
            input.reset();
            for (int i=0;i<skip;i++) input.read();
            Reader reader=new InputStreamReader(input,encoding.newDecoder().onMalformedInput(CodingErrorAction.REPORT));
            char[] buf=new char[4096]; int n;
            while ((n=reader.read(buf))!=-1) {
                for (int i=0;i<n;i++) if (Character.isISOControl(buf[i]) && "\t\r\n\f".indexOf(buf[i])<0)
                    throw new IOException("binary");
                out.write(buf,0,n);
            }
        }
    }
    private static Attachment scan(Context ctx,File file,String name,int slots) throws IOException {
        if (slots<=0) throw new ImageLimit();
        List<String> paths=new ArrayList<>();
        try (ParcelFileDescriptor fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdf=new PdfRenderer(fd)) {
            int count=Math.min(Math.min(slots,SCAN_PAGES),pdf.getPageCount());
            if (count==0) throw new IOException("no-pages");
            for (int i=0;i<count;i++) {
                try (PdfRenderer.Page page=pdf.openPage(i)) {
                    int w=page.getWidth(), h=page.getHeight();
                    if (w<=0 || h<=0) throw new IOException("page-size");
                    float scale=(float)Photos.LONG_EDGE/Math.max(w,h);
                    Bitmap bitmap=renderPage(page,Math.max(1,Math.round(w*scale)),Math.max(1,Math.round(h*scale)));
                    try {
                        paths.add(Photos.saveJpeg(ctx,bitmap));
                    } finally { bitmap.recycle(); }
                }
            }
        } catch (IOException | RuntimeException e) {
            for (String path:paths) Attachment.file(ctx,path).delete();
            throw e;
        }
        return new Attachment(Attachment.Kind.SCAN,name,0,paths);
    }
    static Bitmap renderPage(PdfRenderer.Page page,int w,int h) {
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (RuntimeException e) { bitmap.recycle(); throw e; }
    }
}
