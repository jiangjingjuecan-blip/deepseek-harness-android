package com.sunwin.deepseekharness;

import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.*;
import android.view.inspector.WindowInspector;
import android.widget.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

final class DeviceAdventure {
    private final Instrumentation test;
    private final MainActivity activity;
    private final HarnessApp app;
    private final long chat;
    String stage="upgrade";

    DeviceAdventure(Instrumentation test,MainActivity activity) {
        this.test=test; this.activity=activity; app=(HarnessApp)activity.getApplication();
        chat=activity.getSharedPreferences("ui",0).getLong("chat",0);
    }
    void run() throws Exception {
        long old=activity.getSharedPreferences("ui",0).getLong("adventure-upgrade",0);
        History.Chat migrated=app.history.chat(old);
        require(migrated!=null && "fixture-upgrade-v6".equals(migrated.remote));
        require(app.history.branches(old).size()==1 && app.history.meter(old,"deepseek-flash").used==66);
        List<History.Message> rows=app.history.messages(old);
        require(rows.size()==2 && "升级前回复".equals(rows.get(1).text));
        require(Attachment.file(app,rows.get(0).attachments.get(0).paths.get(0)).length()>0);
        send("设备开场","门前");
        hold("门前"); tap("存档");
        until(()->app.history.messages(chat).get(1).saved);
        tap("骰子"); tap("d20"); until(()->app.history.messages(chat).size()==3);
        send("查门","银钥匙"); hold("银钥匙"); tap("重生成"); reply("金钥匙");
        tap("上一版"); visible("1/2"); visible("银钥匙");
        tap("下一版"); visible("2/2"); edit("金钥匙","铜钥匙"); visible("3/3");
        send("用钥匙","打开");
        tap("上一版"); visible("金钥匙"); require(app.history.messages(chat).size()==5);
        edit("查门","回营"); tap("发送"); reply("归来");
        tap("分支"); tap("主线"); visible("银钥匙");
        hold("门前"); tap("分支"); send("向北","山路");
        require(app.history.messages(chat).size()==4 && app.history.messages(chat).get(1).saved);
    }
    private static void require(boolean ok) { if (!ok) throw new AssertionError(); }
    private void until(BooleanSupplier check) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+90000;
        while (!check.getAsBoolean()) {
            if (SystemClock.elapsedRealtime()>=deadline) throw new AssertionError();
            Thread.sleep(70);
        }
    }
    private void send(String input,String output) throws Exception {
        type(input); tap("发送"); reply(output);
    }
    private void reply(String output) throws Exception {
        stage="reply-"+output;
        until(()->{
            List<History.Message> rows=app.history.messages(chat);
            if (rows.isEmpty() || app.chat.busy()) return false;
            History.Message tail=rows.get(rows.size()-1);
            require(!"interrupted".equals(tail.state));
            return output.equals(tail.text);
        });
        visible(output);
    }
    private void edit(String from,String to) throws Exception {
        hold(from); tap("编辑"); type(to); tap("保存"); visible(to);
    }
    private void type(String text) throws Exception { act(null,v->((EditText)v).setText(text)); }
    private void visible(String label) throws Exception { act(label,v->{}); }
    private void hold(String label) throws Exception { act(label,v->require(v.performLongClick())); }
    private void tap(String label) throws Exception {
        act(label,v->{
            for (View child=v;child.getParent() instanceof View;child=(View)child.getParent()) {
                if (child.getParent() instanceof ListView) {
                    ListView list=(ListView)child.getParent(); int pos=list.getPositionForView(child);
                    require(list.performItemClick(child,pos,list.getItemIdAtPosition(pos))); return;
                }
            }
            require(v.performClick());
        });
    }
    private void act(String label,java.util.function.Consumer<View> action) throws Exception {
        stage=label==null?"input":label;
        test.waitForIdleSync();
        AtomicReference<Throwable> failure=new AtomicReference<>();
        until(()->{
            boolean[] found={false};
            test.runOnMainSync(()->{
                try {
                    List<View> roots=WindowInspector.getGlobalWindowViews();
                    for (int i=roots.size()-1;i>=0;i--) {
                        View v=find(roots.get(i),label);
                        if (v!=null) { found[0]=true; action.accept(v); break; }
                    }
                } catch (Throwable e) { failure.set(e); }
            });
            return found[0] || failure.get()!=null;
        });
        if (failure.get()!=null) throw new AssertionError(stage,failure.get());
        test.waitForIdleSync();
    }
    private View find(View view,String label) {
        if (!view.isShown()) return null;
        if (label==null && view instanceof EditText) return view;
        if (label!=null && (label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()) ||
                view instanceof TextView && label.contentEquals(((TextView)view).getText()))) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for (int i=0;i<group.getChildCount();i++) {
                View child=find(group.getChildAt(i),label);
                if (child!=null) return child;
            }
        }
        return null;
    }
}
