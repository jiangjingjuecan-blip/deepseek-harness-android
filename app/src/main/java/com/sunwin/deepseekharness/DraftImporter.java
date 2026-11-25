package com.sunwin.deepseekharness;

import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Imports survive activity recreation. A canceled job cannot attach to a reused chat ID. */
final class DraftImporter {
    private final HarnessApp app;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Map<Long,Object> jobs=new HashMap<>();
    DraftImporter(HarnessApp app) { this.app=app; }
    synchronized boolean busy(long chat) { return jobs.containsKey(chat); }
    synchronized void cancel(long chat) { jobs.remove(chat); }
    synchronized void add(long chat,List<Uri> sources,boolean document,File camera) {
        if (busy(chat)) { if (camera!=null) camera.delete(); return; }
        long branch=app.history.chat(chat).branch;
        Object job=new Object(); jobs.put(chat,job); app.changed();
        io.execute(()->{
            try {
                for (Uri uri:sources) {
                    synchronized (this) { if (jobs.get(chat)!=job) return; }
                    List<Attachment> draft=app.history.branchDraft(branch);
                    int slots=Attachment.MAX_PHOTOS-Attachment.photos(draft);
                    if (!document && slots<=0) { app.notice("最多 9 张图片"); break; }
                    if (document && Attachment.documents(draft)>=Attachment.MAX_DOCUMENTS) { app.notice("最多 4 个文件"); break; }
                    Attachment item=null;
                    boolean saved=false;
                    try {
                        item=document ? Documents.importFile(app,uri,Documents.SCAN_PAGES) : Attachment.photo(Photos.importPhoto(app,uri));
                        synchronized (this) {
                            if (jobs.get(chat)!=job) return;
                            saved=app.history.addDraft(chat,branch,item);
                        }
                        if (!saved) return;
                        app.changed();
                    } catch (Documents.ImageLimit e) { app.notice("最多 9 张图片"); }
                    catch (IOException | RuntimeException e) { app.notice(document ? "无法读取该文件" : "图片读取失败"); }
                    finally {
                        if (!saved && item!=null) for (String path:item.paths) new File(app.getFilesDir(),path).delete();
                    }
                }
            } finally {
                if (camera!=null) camera.delete();
                synchronized (this) { if (jobs.get(chat)==job) jobs.remove(chat); }
                app.changed();
            }
        });
    }
}
