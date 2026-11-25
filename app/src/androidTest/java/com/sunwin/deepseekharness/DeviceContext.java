package com.sunwin.deepseekharness;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** The emulator runs the signed Android client. ADB connects it to an isolated ARM64 DSH fixture. */
final class DeviceContext {
    private final Instrumentation test;
    private final MainActivity activity;
    private final HarnessApp app;
    String stage="context-start";
    DeviceContext(Instrumentation test,MainActivity activity) {
        this.test=test; this.activity=activity; app=(HarnessApp)activity.getApplication();
    }

    void run(int port,String mode,android.os.Bundle result) throws Exception {
        if (port<1 || port>65535) throw new AssertionError();
        activity.stopService(new Intent(activity,HarnessService.class));
        await("stop-emulator-service",()->"stopped".equals(app.serviceState) || "failed".equals(app.serviceState));
        stage="fixture-connect";
        JSONObject bridge;
        HttpURLConnection handoff=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/fixture-connect").openConnection(Proxy.NO_PROXY);
        handoff.setInstanceFollowRedirects(false);
        try (Scanner input=new Scanner(handoff.getInputStream(),"UTF-8")) {
            bridge=new JSONObject(input.useDelimiter("\\A").next());
        } finally { handoff.disconnect(); }
        Loopback api=new Loopback(bridge.getInt("port"),app.log);
        ChatEngine original=app.chat;
        try {
            api.bootstrap(bridge.getString("path"));
            app.chat=new ChatEngine(app,bridge.getString("cwd"));
            app.connected(api);
            await("fixture-account",()->app.account.confirmed && "deepseek-flash".equals(app.account.selected));
            onUi(()->{
                click("历史对话"); click("新建对话");
            });
            if ("send-ui".equals(mode)) {
                DeviceSendUi ui=new DeviceSendUi(test,activity,port);
                try { ui.run(); } finally { stage=ui.stage; if (ui.revealRate!=null) result.putString("reveal_rate",ui.revealRate); }
                return;
            }
            if ("send-bench".equals(mode)) {
                DeviceSendBench bench=new DeviceSendBench(test,activity);
                try { bench.run(result); } finally { stage=bench.stage; }
                return;
            }
            if ("adventure-ui".equals(mode)) {
                DeviceAdventure adventure=new DeviceAdventure(test,activity);
                try { adventure.run(); } finally { stage=adventure.stage; }
                return;
            }
            if ("attachments".equals(mode)) {
                DeviceAttachments attachments=new DeviceAttachments(test,activity);
                try { attachments.send(); } finally { stage=attachments.stage; }
                return;
            }
            long id=activity.getSharedPreferences("ui",0).getLong("chat",0);
            String[] questions={"固定首问","旧甲"+repeat("甲"),"旧乙"+repeat("乙"),"近三","近四","近五","抬高用量","核对自动"};
            for (int i=0;i<questions.length;i++) {
                if (i==6) {
                    stage="manual-menu";
                    onUi(()->click("上下文用量 <1%"));
                    clickMenu();
                    await("manual-complete",()->!app.chat.busy());
                }
                String question=questions[i];
                stage="send-"+i;
                onUi(()->{
                    EditText input=(EditText)find(activity.getWindow().getDecorView(),null);
                    input.setText(question); click("发送");
                });
                await("reply-"+i,()->!app.chat.busy());
                List<History.Message> rows=app.history.messages(id);
                if (rows.size()!=2*(i+1) || !"done".equals(rows.get(rows.size()-1).state)) throw new AssertionError();
                if (i==6 || i==7) {
                    String percent=i==6 ? "61%" : "<1%";
                    test.waitForIdleSync();
                    stage="indicator-"+percent;
                    onUi(()->{
                        if (find(activity.getWindow().getDecorView(),"上下文用量 "+percent)==null) throw new AssertionError();
                    });
                }
            }
            List<History.Message> rows=app.history.messages(id);
            for (int i=0;i<questions.length;i++) if (!questions[i].equals(rows.get(2*i).text)) throw new AssertionError();
        } finally {
            app.disconnected(false);
            app.chat=original;
        }
    }
    private static String repeat(String value) { return String.join("",Collections.nCopies(600,value)); }

    private void onUi(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
        test.runOnMainSync(()->{
            try { action.run(); }
            catch (Throwable e) { error.set(e); }
        });
        if (error.get()!=null) throw new AssertionError(stage);
    }
    private void await(String name,BooleanSupplier condition) throws InterruptedException {
        stage=name;
        long until=SystemClock.elapsedRealtime()+150000;
        while (!condition.getAsBoolean()) {
            if (SystemClock.elapsedRealtime()>=until) throw new AssertionError();
            Thread.sleep(80);
        }
    }
    private View find(View view,String label) {
        if (label==null && view instanceof EditText) return view;
        if (label!=null && label.equals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) {
                View found=find(group.getChildAt(i),label);
                if (found!=null) return found;
            }
        }
        return null;
    }
    private void click(String label) {
        View button=find(activity.getWindow().getDecorView(),label);
        if (button==null || !button.isShown() || !button.performClick()) throw new AssertionError();
    }
    private void clickMenu() throws InterruptedException {
        // A framework PopupMenu owns a separate ListView window from the activity.
        await("压缩上下文",()->{
            java.util.concurrent.atomic.AtomicBoolean pressed=new java.util.concurrent.atomic.AtomicBoolean();
            onUi(()->{
                for (View root:WindowInspector.getGlobalWindowViews()) {
                    if (pressMenuRow(root)) { pressed.set(true); break; }
                }
            });
            return pressed.get();
        });
        test.waitForIdleSync();
    }
    private boolean pressMenuRow(View view) {
        if (view instanceof ListView && view.isShown()) {
            ListView list=(ListView)view;
            for (int i=0;i<list.getChildCount();i++) {
                int position=list.getFirstVisiblePosition()+i;
                Object item=list.getItemAtPosition(position);
                if (item instanceof MenuItem && "压缩上下文".contentEquals(((MenuItem)item).getTitle()))
                    return list.performItemClick(list.getChildAt(i),position,list.getItemIdAtPosition(position));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) if (pressMenuRow(group.getChildAt(i))) return true;
        }
        return false;
    }
}
