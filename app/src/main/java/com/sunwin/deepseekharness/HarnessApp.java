package com.sunwin.deepseekharness;

import android.app.Application;
import android.os.*;
import android.widget.Toast;

public final class HarnessApp extends Application {
    final Handler main=new Handler(Looper.getMainLooper());
    Diagnostics log;
    History history;
    Account account;
    ChatEngine chat;
    volatile Loopback api;
    volatile String serviceState="starting";
    volatile String serviceFailure="";
    private Runnable listener;
    @Override public void onCreate() {
        super.onCreate();
        log=new Diagnostics(getNoBackupFilesDir());
        history=new History(this);
        int recovered=history.recover();
        history.cleanDeletedPhotos(log);
        if (recovered>0) log.add(Diagnostics.Event.CHAT_RECOVERED_AFTER_RESTART,recovered);
        account=new Account(this); chat=new ChatEngine(this,Guest.CHAT_DIR);
    }
    void listen(Runnable callback) { listener=callback; }
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
