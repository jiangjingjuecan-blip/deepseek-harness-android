package com.sunwin.deepseekharness;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;

/** Touch the signed client on the emulator using only locally seeded conversations. */
final class DeviceDeletion {
    private final Instrumentation test;
    private final MainActivity activity;
    private final HarnessApp app;
    String stage="delete-start";
    DeviceDeletion(Instrumentation test,MainActivity activity) {
        this.test=test; this.activity=activity; app=(HarnessApp)activity.getApplication();
    }
    void run() {
        activity.stopService(new Intent(activity,HarnessService.class));
        touch("历史对话",false);
        touch("新建对话",false);
        long first=current(), second=app.history.create();
        String firstTitle="当前样例"+first, secondTitle="删除样例"+second;
        app.history.update(app.history.begin(first,firstTitle,Collections.emptyList()),"保留的正文","done");
        app.history.update(app.history.begin(second,secondTitle,Collections.emptyList()),"待删除的正文","done");
        app.history.meter(first,"",new ContextUsage(30,100,true));
        app.changed();
        touch("历史对话",false);
        touch(secondTitle,false);
        bounds("待删除的正文");
        if (current()!=second) throw new AssertionError();
        touch("历史对话",false);
        touch(firstTitle,false);
        bounds("保留的正文");
        touch("历史对话",false);
        confirm(secondTitle,"取消");
        bounds("历史对话");
        if (current()!=first || app.history.messages(second).size()!=2) throw new AssertionError();
        confirm(secondTitle,"删除");
        bounds("历史对话");
        if (current()!=first || app.history.chat(second)!=null) throw new AssertionError();
        confirm(firstTitle,"删除");
        bounds("上下文用量 0%");
        bounds("DeepSeek 助手");
        long fresh=current();
        if (fresh==first || app.history.chat(first)!=null || app.history.chat(fresh).remote!=null
                || !app.history.messages(fresh).isEmpty()) throw new AssertionError();
    }
    private long current() { return activity.getSharedPreferences("ui",0).getLong("chat",0); }
    private void confirm(String row,String button) {
        touch(row,true);
        bounds("删除对话");
        List<String> labels=new ArrayList<>();
        collect(test.getUiAutomation().getRootInActiveWindow(),labels);
        Collections.sort(labels);
        List<String> expected=Arrays.asList("删除对话","取消","删除");
        Collections.sort(expected);
        if (!expected.equals(labels)) throw new AssertionError();
        touch(button,false);
    }
    private void collect(AccessibilityNodeInfo node,List<String> labels) {
        if (node==null) return;
        if (node.isVisibleToUser() && node.getText()!=null && node.getText().length()>0)
            labels.add(node.getText().toString());
        for (int i=0;i<node.getChildCount();i++) collect(node.getChild(i),labels);
    }
    private Rect bounds(String label) {
        stage=label;
        long until=SystemClock.uptimeMillis()+12000;
        while (SystemClock.uptimeMillis()<until) {
            AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();
            if (root!=null) for (AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label)) {
                if (node.isVisibleToUser() && (TextUtils.equals(label,node.getText()) || TextUtils.equals(label,node.getContentDescription()))) {
                    Rect rect=new Rect(); node.getBoundsInScreen(rect); return rect;
                }
            }
            SystemClock.sleep(80);
        }
        throw new AssertionError();
    }
    private void touch(String label,boolean hold) {
        Rect rect=bounds(label);
        long down=SystemClock.uptimeMillis();
        pointer(down,MotionEvent.ACTION_DOWN,rect);
        SystemClock.sleep(hold ? ViewConfiguration.getLongPressTimeout()+350 : 70);
        pointer(down,MotionEvent.ACTION_UP,rect);
        test.waitForIdleSync();
    }
    private void pointer(long down,int action,Rect rect) {
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,rect.centerX(),rect.centerY(),0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { test.sendPointerSync(event); } finally { event.recycle(); }
    }
}
