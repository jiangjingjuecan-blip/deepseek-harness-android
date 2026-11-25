package com.sunwin.deepseekharness;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inspector.WindowInspector;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Exercises the real composer, row actions and settings against a gated local reply. */
final class DeviceSendUi {
    private final Instrumentation test;
    private final MainActivity activity;
    private final HarnessApp app;
    private final int port;
    private final long chat;
    String stage="send-ui",revealRate;
    DeviceSendUi(Instrumentation test,MainActivity activity,int port) {
        this.test=test; this.activity=activity; this.port=port; app=(HarnessApp)activity.getApplication();
        chat=activity.getSharedPreferences("ui",0).getLong("chat",0);
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    private <T> T ui(Callable<T> call) throws Exception {
        FutureTask<T> task=new FutureTask<>(call); test.runOnMainSync(task); return task.get();
    }
    private View root() { return activity.getWindow().getDecorView(); }
    private EditText input() { return (EditText)DeviceSendBench.find(root(),null,false); }
    private View control(String text) { return DeviceSendBench.find(root(),text,false); }
    private TextView reply(String prefix) {
        ScrollView scroll=DeviceSendBench.threadScroll(root());
        return (TextView)DeviceSendBench.find(scroll,prefix,true);
    }
    private void await(String label,BooleanSupplier condition) throws Exception {
        stage=label;
        long until=SystemClock.elapsedRealtime()+45000;
        while (!ui(condition::getAsBoolean)) {
            if (SystemClock.elapsedRealtime()>until) throw new AssertionError(label);
            SystemClock.sleep(30);
        }
    }
    private void click(String label) throws Exception {
        await(label,()->{
            List<View> windows=WindowInspector.getGlobalWindowViews();
            for (int i=windows.size()-1;i>=0;i--) {
                View v=DeviceSendBench.find(windows.get(i),label,false);
                if (v==null || !v.isShown()) continue;
                while (!v.isClickable() && v.getParent() instanceof View) {
                    if (v.getParent() instanceof ListView) {
                        ListView list=(ListView)v.getParent(); int at=list.getPositionForView(v);
                        return list.performItemClick(v,at,list.getItemIdAtPosition(at));
                    }
                    v=(View)v.getParent();
                }
                return v.performClick();
            }
            return false;
        });
    }
    private void advance() throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/fixture-next").openConnection(Proxy.NO_PROXY);
        try { check(c.getResponseCode()==200); } finally { c.disconnect(); }
    }
    private void send(String text) throws Exception {
        ui(()->{ input().setText(text); return control("发送").performClick(); });
    }
    void run() throws Exception {
        await("empty",()->!control("发送").isEnabled());
        ui(()->{
            input().requestFocus();
            check((input().getInputType() & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0);
            check((input().getImeOptions() & EditorInfo.IME_FLAG_NO_ENTER_ACTION)!=0);
            input().setText("甲"); input().setSelection(1);
            android.view.inputmethod.InputConnection ime=input().onCreateInputConnection(new EditorInfo());
            ime.setComposingText("拼",1); ime.performEditorAction(EditorInfo.IME_ACTION_SEND);
            check("甲拼".contentEquals(input().getText()) && !app.chat.busy());
            ime.finishComposingText(); ime.performEditorAction(EditorInfo.IME_ACTION_DONE);
            check("甲拼\n".contentEquals(input().getText()) && !app.chat.busy()); input().setText("");
            return true;
        });
        String path="photos/send-ui-"+UUID.randomUUID()+".jpg";
        File photo=new File(app.getFilesDir(),path); Files.createDirectories(photo.toPath().getParent());
        try (InputStream in=test.getContext().getAssets().open("photo.jpg")) { Files.copy(in,photo.toPath()); }
        app.history.addDraft(chat,Attachment.photo(path)); app.changed();
        await("draft-chip",()->control("待发照片 1")!=null);
        app.history.getWritableDatabase().execSQL("CREATE TRIGGER test_failed_insert BEFORE INSERT ON message "+
                "WHEN NEW.role='assistant' BEGIN SELECT RAISE(ABORT,'fixture'); END");
        try {
            ui(()->{
                input().setText("采样重试"); check(control("发送").performClick());
                check(input().length()==0 && control("待发照片 1")==null);
                check(reply("采样重试")!=null && control("停止")!=null && control("照片 1")!=null && control("处理中")!=null);
                return true;
            });
            await("failed-bubble",()->control("重试")!=null && control("重试").isShown());
        } finally { app.history.getWritableDatabase().execSQL("DROP TRIGGER test_failed_insert"); }
        click("重试");
        await("tap-reveal",()->{
            TextView body=reply("溪水试");
            if (body==null || body.length()>=67) return false;
            int was=body.length();
            return body.performClick() && body.length()>was;
        });
        advance();
        await("retried",()->!app.chat.busy() && reply("溪水试")!=null && reply("溪水试").length()==259);
        List<History.Message> rows=app.history.messages(chat);
        check(rows.size()==2 && rows.get(0).attachments.size()==1 && rows.get(0).attachments.get(0).paths.get(0).equals(path));
        send("采样停止");
        await("started",()->reply("溪水止")!=null);
        ScrollView scroll=ui(()->DeviceSendBench.threadScroll(root()));
        int pinned=ui(()->{ scroll.scrollTo(0,scroll.getScrollY()/2); return scroll.getScrollY(); });
        await("pinned-while-streaming",()->control("跳到最新") instanceof ImageButton && control("跳到最新").isShown());
        SystemClock.sleep(650); check(ui(()->scroll.getScrollY()==pinned));
        click("停止");
        await("stopped",()->!app.chat.busy() && control("发送")!=null);
        rows=app.history.messages(chat);
        check(rows.size()==4 && rows.get(3).state.equals("interrupted") && !rows.get(3).text.isEmpty());
        advance();
        ui(()->{ scroll.scrollTo(0,0); return true; });
        await("jump-icon",()->control("跳到最新") instanceof ImageButton && control("跳到最新").isShown());
        click("跳到最新"); await("follow",()->!scroll.canScrollVertically(1) && !control("跳到最新").isShown());
        send("采样八字");
        await("eight-start",()->reply("溪水八")!=null);
        // Sample the painted body length against the UI clock while the 24-character turn is held open.
        long[] first=ui(()->new long[]{SystemClock.uptimeMillis(),reply("溪水八").length()}),later;
        do { SystemClock.sleep(50); later=ui(()->new long[]{SystemClock.uptimeMillis(),reply("溪水八").length()}); }
        while (later[0]-first[0]<2000);
        double rate=(later[1]-first[1])*1000.0/(later[0]-first[0]);
        revealRate=String.format(Locale.ROOT,"%.2f chars/s over %d ms (%d→%d)",rate,later[0]-first[0],first[1],later[1]);
        stage="eight-rate"; check(rate>=6 && rate<=10 && later[1]<24);
        advance();
        await("eight-done",()->!app.chat.busy() && reply("溪水八")!=null && reply("溪水八").length()==24);
        click("设置"); click("显示速度"); click("即时"); click("返回");
        send("采样即时");
        await("instant",()->reply("溪水时")!=null && reply("溪水时").length()==67);
        advance();
        await("instant-done",()->!app.chat.busy() && reply("溪水时").length()==259);
        click("设置"); click("显示速度"); click("8 字/秒"); click("返回");
        check(ui(()->reply("溪水时")!=null && reply("溪水时").length()==259));
        click("历史对话"); click("采样重试");
        check(ui(()->reply("溪水时")!=null && reply("溪水时").length()==259));
        check(ui(()->reply("溪水时").performLongClick())); click("重生成");
        await("regenerate-reveal",()->reply("溪水时")!=null && reply("溪水时").length()<67);
        advance(); await("regenerated",()->!app.chat.busy());
        click("上一版");
        check(ui(()->reply("溪水时")!=null && reply("溪水时").length()==259));
        click("下一版");
        check(ui(()->reply("溪水时")!=null && reply("溪水时").length()==259));
    }
}
