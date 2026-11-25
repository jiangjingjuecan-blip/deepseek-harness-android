package com.sunwin.deepseekharness;

import android.net.Uri;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** A send can claim a picker batch while its URI copies are still running. */
final class DraftImporter {
    static final class Batch {
        final long chat,branch;
        final boolean document;
        final File camera;
        final List<Attachment> ready=new CopyOnWriteArrayList<>();
        final List<Uri> remaining=new CopyOnWriteArrayList<>();
        volatile CountDownLatch done=new CountDownLatch(1);
        volatile boolean claimed,canceled;
        volatile long user;
        Batch(long chat,long branch,List<Uri> uris,boolean document,File camera) {
            this.chat=chat; this.branch=branch; this.document=document; this.camera=camera;
            remaining.addAll(uris);
        }
        void dispose() { canceled=true; if (camera!=null) camera.delete(); }
    }
    private final HarnessApp app;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Map<Long,Batch> jobs=new ConcurrentHashMap<>();
    private final Map<Long,Batch> transfers=new ConcurrentHashMap<>();
    DraftImporter(HarnessApp app) { this.app=app; }
    boolean busy(long chat) {
        Batch job=jobs.get(chat); return job!=null && job.done.getCount()!=0;
    }
    Batch available(long chat,long branch) {
        Batch b=jobs.get(chat); return b!=null && b.branch==branch && !b.claimed ? b : null;
    }
    void observed(Batch b) {
        // The composer now has a snapshot containing these completed imports.
        if (b!=null && b.done.getCount()==0) jobs.remove(b.chat,b);
    }
    synchronized void cancel(long chat) {
        Batch b=jobs.remove(chat); if (b!=null) b.dispose();
        transfers.values().removeIf(job->{ if (job.chat!=chat) return false; job.dispose(); return true; });
    }
    void add(long chat,List<Uri> sources,boolean document,File camera) {
        if (busy(chat)) { if (camera!=null) camera.delete(); return; }
        Batch job=new Batch(chat,app.history.chat(chat).branch,sources,document,camera);
        jobs.put(chat,job); app.changed(); io.execute(()->copy(job));
    }
    // Called under the batch lock after the user row and its remaining URI list commit together.
    void attached(Batch b,long user) {
        b.user=user;
        if (b.done.getCount()!=0 && !b.remaining.isEmpty()) transfers.put(user,b);
    }
    void retain(Batch job) {
        for (Uri uri:job.remaining) if ("content".equals(uri.getScheme())) try {
            app.getContentResolver().takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
    }
    void release(Uri uri) {
        if (app.history.needsUri(uri)) return;
        if ("content".equals(uri.getScheme())) try {
            app.getContentResolver().releasePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
        else if ("file".equals(uri.getScheme()) && uri.getPath()!=null) {
            File file=new File(uri.getPath());
            if (new File(app.getCacheDir(),"camera").equals(file.getParentFile()) && file.getName().startsWith("photo-")) file.delete();
        }
    }
    synchronized Batch resume(long chat,long branch,long user) {
        Batch current=transfers.get(user);
        if (current!=null) return current;
        Batch b=app.history.pendingImports(chat,branch,user);
        if (b==null) return null;
        b.user=user; b.claimed=true; transfers.put(user,b); io.execute(()->copy(b)); return b;
    }
    void retry(Batch job) {
        if (job.remaining.isEmpty() || job.done.getCount()!=0) return;
        job.done=new CountDownLatch(1); io.execute(()->copy(job));
    }
    private void copy(Batch job) {
        try {
            for (Uri uri:new ArrayList<>(job.remaining)) {
                if (job.canceled) return;
                Attachment item=null;
                try {
                    List<Attachment> draft=job.user==0 ? app.history.branchDraft(job.branch) : app.history.at(job.branch,job.user).attachments;
                    if (job.document ? Attachment.documents(draft)>=Attachment.MAX_DOCUMENTS : Attachment.photos(draft)>=Attachment.MAX_PHOTOS) {
                        app.notice(job.document?"最多 4 个文件":"最多 9 张图片");
                        synchronized (job) {
                            if (job.user!=0) app.history.finishImport(job,uri,null);
                            job.remaining.remove(uri);
                        }
                        if (job.user!=0) release(uri);
                        continue;
                    }
                    item=job.document ? Documents.importFile(app,uri,Documents.SCAN_PAGES) : Attachment.photo(Photos.importPhoto(app,uri));
                    synchronized (job) {
                        if (job.canceled) return;
                        item.id=job.user==0 ? app.history.addDraftFile(job.chat,job.branch,item) : app.history.finishImport(job,uri,item);
                        if (item.id==0) throw new IOException();
                        job.ready.add(item); job.remaining.remove(uri);
                    }
                    if (job.user!=0) release(uri);
                    app.changed();
                } catch (IOException | RuntimeException e) {
                    if (job.claimed) break;
                    app.notice(e instanceof Documents.ImageLimit ? "最多 9 张图片" : job.document?"无法读取该文件":"图片读取失败");
                } finally {
                    if (item!=null && item.id==0) for (String path:item.paths) new File(app.getFilesDir(),path).delete();
                }
            }
        } finally {
            if (job.camera!=null && (job.remaining.isEmpty() || !job.claimed)) job.camera.delete();
            job.done.countDown(); transfers.remove(job.user,job); app.changed();
        }
    }
}
