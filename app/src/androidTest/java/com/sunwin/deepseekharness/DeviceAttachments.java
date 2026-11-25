package com.sunwin.deepseekharness;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;
import java.util.zip.*;

final class DeviceAttachments {
    private final Instrumentation test;
    private final MainActivity activity;
    private final HarnessApp app;
    String stage="attachments";
    DeviceAttachments(Instrumentation test,MainActivity activity) {
        this.test=test; this.activity=activity; app=(HarnessApp)activity.getApplication();
    }
    void send() throws Exception {
        long chat=activity.getSharedPreferences("ui",0).getLong("chat",0);
        Path folder=Files.createTempDirectory(activity.getCacheDir().toPath(),"attachment-test");
        Uri red=Uri.fromFile(folder.resolve("red.jpg").toFile()), blue=Uri.fromFile(folder.resolve("blue.jpg").toFile());
        for (Uri uri:Arrays.asList(red,blue)) try (OutputStream out=app.getContentResolver().openOutputStream(uri)) {
            jpeg(out,uri.equals(red) ? Color.RED : Color.BLUE);
        }
        stage="gallery-multiselect";
        result("相册",intent->{
            require(Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction()) && intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));
            return multiple(red,blue);
        });
        await("gallery-draft",()->!app.imports.busy(chat) && app.history.draft(chat).size()==2);
        stage="camera-add";
        result("拍照",intent->{
            require(MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction()));
            try (OutputStream out=app.getContentResolver().openOutputStream(intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT))) {
                jpeg(out,Color.GREEN);
            } catch (IOException e) { throw new AssertionError(); }
            return new Intent();
        });
        await("camera-draft",()->!app.imports.busy(chat) && app.history.draft(chat).size()==3);
        // Check removal through the compose row, then add the removed photograph again.
        ui(()->click(root(),"移除照片"));
        require(app.history.draft(chat).size()==2);
        result("相册",intent->new Intent().setData(red));
        await("restored-draft",()->!app.imports.busy(chat) && app.history.draft(chat).size()==3);

        Path txt=Files.write(folder.resolve("便签.txt"),"设备文件正文".getBytes("UTF-8"));
        Path docx=folder.resolve("报告.docx");
        try (ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(docx))) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<document><p><t>设备段落</t></p></document>".getBytes("UTF-8")); zip.closeEntry();
        }
        result("文件",intent->multiple(Uri.fromFile(txt.toFile()),Uri.fromFile(docx.toFile())));
        await("document-draft",()->!app.imports.busy(chat) && app.history.draft(chat).size()==5);
        test.waitForIdleSync();
        ui(()->{
            require(find(root(),"便签.txt")!=null && find(root(),"报告.docx")!=null);
            ((EditText)find(root(),null)).setText("附件回放"); click(root(),"发送");
        });
        await("fixture-reply",()->!app.chat.busy() && app.history.messages(chat).size()==2);
        require("附件已收到".equals(app.history.messages(chat).get(1).text));
        checkMessage();
    }
    static void pdfs(Instrumentation test) throws Exception {
        Context app=test.getTargetContext();
        Path folder=Files.createTempDirectory(app.getCacheDir().toPath(),"pdf-test");
        for (String filename:new String[]{"text.pdf","scan.pdf"}) {
            Path file=folder.resolve(filename);
            try (InputStream in=test.getContext().getAssets().open(filename)) { Files.copy(in,file); }
            Attachment item=Documents.importFile(app,Uri.fromFile(file.toFile()),2);
            if ("scan.pdf".equals(filename)) {
                require(item.kind==Attachment.Kind.SCAN && item.imageCount()==2);
                Bitmap page=Photos.load(app,item.paths.get(0),200);
                require(page.getPixel(page.getWidth()/2,page.getHeight()/2)!=Color.WHITE);
            } else require(new String(Files.readAllBytes(Attachment.file(app,item.paths.get(0)).toPath()),"UTF-8").contains("Fixture PDF text"));
            for (String path:item.paths) Attachment.file(app,path).delete();
        }
    }
    void reopen(boolean onlyUpgrade) throws Exception {
        activity.stopService(new Intent(activity,HarnessService.class));
        if (!onlyUpgrade) checkMessage();
        ui(()->click(root(),"历史对话"));
        ui(()->click(root(),"升级前照片"));
        await("v4-thumb",()->hasBitmap("照片 1",Color.rgb(100,58,202)));
        long id=activity.getSharedPreferences("ui",0).getLong("chat",0);
        History.Message old=app.history.messages(id).get(0);
        require("升级前照片".equals(old.text) && old.attachments.size()==1);
    }
    private void checkMessage() throws Exception {
        await("stored-thumbnails",()->hasBitmap("照片 1",Color.BLUE) && hasBitmap("照片 2",Color.GREEN) && hasBitmap("照片 3",Color.RED));
        ui(()->{ require(find(root(),"报告.docx")!=null); click(root(),"便签.txt"); });
        await("local-preview",()->{
            final boolean[] seen={false};
            ui(()->{
                for (View window:WindowInspector.getGlobalWindowViews()) if (find(window,"设备文件正文")!=null) {
                    click(window,"关闭"); seen[0]=true; break;
                }
            });
            return seen[0];
        });
        Instrumentation.ActivityMonitor monitor=test.addMonitor(PhotoViewer.class.getName(),null,false);
        try {
            ui(()->click(root(),"照片 1"));
            PhotoViewer viewer=(PhotoViewer)test.waitForMonitorWithTimeout(monitor,20000);
            require(viewer!=null); test.waitForIdleSync();
            stage="fullscreen-swipe";
            ui(()->{
                View frame=viewer.getWindow().getDecorView(), photo=find(frame,"照片 1"); require(photo!=null);
                float w=photo.getWidth(), h=photo.getHeight(); long now=SystemClock.uptimeMillis();
                point(photo,now,now,MotionEvent.ACTION_DOWN,.85f*w,h/2);
                point(photo,now,now+100,MotionEvent.ACTION_MOVE,.45f*w,h/2);
                point(photo,now,now+180,MotionEvent.ACTION_UP,.15f*w,h/2);
                require(find(frame,"2 / 3")!=null);
                stage="pinch-letterbox";
                require(!expanded(photo));
                pinch(photo);
                stage="pinch-expanded";
                require(expanded(photo));
                stage="viewer-close";
                click(frame,"关闭");
            });
            test.waitForIdleSync();
        } finally { test.removeMonitor(monitor); }
    }
    private boolean hasBitmap(String label,int expected) {
        final boolean[] ok={false};
        ui(()->{
            View image=find(root(),label);
            if (!(image instanceof ImageView) || image.getWidth()<1 || image.getHeight()<1) return;
            Bitmap thumb=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
            try {
                image.draw(new Canvas(thumb));
                int color=thumb.getPixel(thumb.getWidth()/2,thumb.getHeight()/2);
                ok[0]=Color.alpha(color)==255 && Math.abs(Color.red(color)-Color.red(expected))+
                        Math.abs(Color.green(color)-Color.green(expected))+Math.abs(Color.blue(color)-Color.blue(expected))<36;
            } finally { thumb.recycle(); }
        });
        return ok[0];
    }
    // Test an actual painted pixel in the letterbox before and after a two-finger gesture.
    private boolean expanded(View photo) {
        int w=photo.getWidth(), h=photo.getHeight();
        Bitmap capture=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        try {
            capture.eraseColor(Color.BLACK); photo.draw(new Canvas(capture));
            int color=capture.getPixel(w/2,Math.max(0,(int)(h/2f-Math.min(h,w/2f)*0.65f)));
            int center=capture.getPixel(w/2,h/2);
            return center!=Color.BLACK && Math.abs(Color.red(color)-Color.red(center))+
                    Math.abs(Color.green(color)-Color.green(center))+Math.abs(Color.blue(color)-Color.blue(center))<24;
        } finally { capture.recycle(); }
    }
    private static void point(View v,long down,long time,int action,float x,float y) {
        MotionEvent e=MotionEvent.obtain(down,time,action,x,y,0);
        try { v.dispatchTouchEvent(e); } finally { e.recycle(); }
    }
    private void pinch(View v) {
        float cx=v.getWidth()/2f, cy=v.getHeight()/2f, w=v.getWidth();
        long down=SystemClock.uptimeMillis(); point(v,down,down,MotionEvent.ACTION_DOWN,cx-.1f*w,cy);
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[2];
        for (int i=0;i<2;i++) {
            props[i]=new MotionEvent.PointerProperties(); props[i].id=i; props[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coords[i]=new MotionEvent.PointerCoords(); coords[i].y=cy; coords[i].pressure=1; coords[i].size=.1f;
        }
        for (int n=0;n<8;n++) {
            float radius=(.1f+.045f*Math.min(n,6))*w;
            coords[0].x=cx-radius; coords[1].x=cx+radius;
            int action=n==0 ? MotionEvent.ACTION_POINTER_DOWN | 1<<8 : n==7 ? MotionEvent.ACTION_POINTER_UP | 1<<8 : MotionEvent.ACTION_MOVE;
            MotionEvent e=MotionEvent.obtain(down,down+15+n*35,action,2,props,coords,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
            try { v.dispatchTouchEvent(e); } finally { e.recycle(); }
        }
        point(v,down,down+330,MotionEvent.ACTION_UP,coords[0].x,cy);
    }
    private static void jpeg(OutputStream out,int color) {
        Bitmap b=Bitmap.createBitmap(200,100,Bitmap.Config.ARGB_8888);
        try { b.eraseColor(color); require(b.compress(Bitmap.CompressFormat.JPEG,90,out)); } finally { b.recycle(); }
    }
    private static Intent multiple(Uri first,Uri second) {
        ClipData clip=ClipData.newRawUri("附件",first); clip.addItem(new ClipData.Item(second));
        Intent result=new Intent(); result.setClipData(clip); return result;
    }
    private void result(String button,Function<Intent,Intent> reply) {
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                return new Instrumentation.ActivityResult(Activity.RESULT_OK,reply.apply(intent));
            }
        };
        test.addMonitor(monitor);
        try { ui(()->click(root(),button)); test.waitForIdleSync(); }
        finally { test.removeMonitor(monitor); }
    }
    private View root() { return activity.getWindow().getDecorView(); }
    private static View find(View v,String label) {
        if (label==null && v instanceof EditText) return v;
        if (label!=null && (label.contentEquals(v.getContentDescription()==null ? "" : v.getContentDescription()) ||
                (v instanceof TextView && label.contentEquals(((TextView)v).getText())))) return v;
        if (v instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)v;
            for (int i=0;i<group.getChildCount();i++) {
                View found=find(group.getChildAt(i),label); if (found!=null) return found;
            }
        }
        return null;
    }
    private static void click(View root,String label) {
        View v=find(root,label); require(v!=null);
        while (!v.isClickable() && v.getParent() instanceof View) v=(View)v.getParent();
        require(v.isEnabled() && v.performClick());
    }
    private void ui(Runnable action) {
        AtomicReference<Throwable> error=new AtomicReference<>();
        test.runOnMainSync(()->{ try { action.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get()!=null) throw new AssertionError(stage);
    }
    private void await(String name,BooleanSupplier condition) throws InterruptedException {
        stage=name; long end=SystemClock.elapsedRealtime()+90000;
        while (!condition.getAsBoolean()) { if (SystemClock.elapsedRealtime()>=end) throw new AssertionError(); Thread.sleep(60); }
    }
    private static void require(boolean ok) { if (!ok) throw new AssertionError(); }
}
