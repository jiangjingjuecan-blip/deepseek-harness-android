package com.sunwin.deepseekharness;

import android.app.Application;
import android.os.*;
import android.widget.Toast;

public final class HarnessApp extends Application {
    final Handler main=new Handler(Looper.getMainLooper());
    Diagnostics log;
    History history;
    DraftImporter imports;
    Account account;
    ChatEngine chat;
    volatile Loopback api;
    volatile String serviceState="starting";
    volatile String serviceFailure="";
    private Runnable listener;
    private volatile Runnable streamListener;
    private final java.util.concurrent.atomic.AtomicBoolean streamPosted=new java.util.concurrent.atomic.AtomicBoolean();
    @Override public void onCreate() {
        super.onCreate();
        log=new Diagnostics(getNoBackupFilesDir());
        history=new History(this);
        int recovered=history.recover();
        history.recoverAttachmentFiles(log);
        Documents.clearInterruptedImports(this,log);
        if (recovered>0) log.add(Diagnostics.Event.CHAT_RECOVERED_AFTER_RESTART,recovered);
        imports=new DraftImporter(this);
        account=new Account(this); chat=new ChatEngine(this,Guest.CHAT_DIR);
    }
    void listen(Runnable callback) { listener=callback; }
    void listenStream(Runnable callback) { streamListener=callback; }
    boolean webSearchEnabled() {
        return getSharedPreferences("ui",MODE_PRIVATE).getBoolean(WebTools.PREF,WebTools.DEFAULT_ENABLED);
    }
    void webSearchEnabled(boolean enabled) {
        getSharedPreferences("ui",MODE_PRIVATE).edit().putBoolean(WebTools.PREF,enabled).apply();
    }
    void streamChanged() {
        if (streamListener!=null && streamPosted.compareAndSet(false,true)) main.post(()->
            android.view.Choreographer.getInstance().postFrameCallback(t->{
                streamPosted.set(false);
                if (streamListener!=null) streamListener.run();
            }));
    }
    private final Runnable notifyChange=() -> { if (listener!=null) listener.run(); };
    void changed() { main.removeCallbacks(notifyChange); main.post(notifyChange); }
    void notice(String message) { main.post(() -> Toast.makeText(this,message,Toast.LENGTH_SHORT).show()); }
    void connected(Loopback api) {
        this.api=api; serviceState="ready"; account.refresh(true); changed();
    }
    void disconnected(boolean failed) {
        Loopback old=api; api=null;
        if (old!=null) old.close();
        serviceState=failed ? "failed" : "stopped";
        account.disconnected(); changed();
    }
}
