package com.sunwin.deepseekharness;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;

/** Separate instrumentation APK for an empty emulator. No test account or test entry point in the release. */
public final class DeviceSmoke extends Instrumentation {
    private Bundle options;
    private MainActivity activity;
    private HarnessApp app;
    private String stage="launch";
    @Override public void onCreate(Bundle args) { super.onCreate(args); options=args; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            if (!"ranchu".equals(Build.HARDWARE) && !Build.HARDWARE.contains("goldfish")) throw new AssertionError();
            String mode=options.getString("scenario","history");
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            app=(HarnessApp)activity.getApplication();
            if ("login-init".equals(mode)) loginInit();
            else if ("runtime".equals(mode)) stopRuntime();
            else history("reopen".equals(mode));
            result.putString("passed",mode); finish(Activity.RESULT_OK,result);
        } catch (Throwable e) {
            result.putString("failed",stage);
            // Throwable messages may contain a URL. Only export the local stage and exception type.
            result.putString("kind",e.getClass().getSimpleName()); finish(Activity.RESULT_CANCELED,result);
        }
    }
    private View find(View v,String name) {
        if (name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())) return v;
        if (v instanceof TextView && name.contentEquals(((TextView)v).getText())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)v;
            for (int i=0;i<group.getChildCount();i++) { View hit=find(group.getChildAt(i),name); if (hit!=null) return hit; }
        }
        return null;
    }
    private void control(String name,boolean press) {
        stage=name;
        AtomicReference<Boolean> found=new AtomicReference<>(false);
        runOnMainSync(()->{
            View v=find(activity.getWindow().getDecorView(),name);
            if (v!=null && v.isShown()) {
                while (press && !v.isClickable() && v.getParent() instanceof View) v=(View)v.getParent();
                found.set(!press || v.performClick());
            }
        });
        if (!found.get()) throw new AssertionError();
    }
    private void history(boolean reopen) {
        final String title="设备回放对话", answer="这条记录已保存在手机上。";
        if (!reopen) {
            control("设置",true); control("导出诊断日志",false); control("充值",false);
            control("返回",true); control("历史对话",true); control("新建对话",true);
            long id=activity.getSharedPreferences("ui",0).getLong("chat",0);
            long reply=app.history.begin(id,title,null); app.history.update(reply,answer,"done");
            runOnMainSync(app::changed); waitForIdleSync();
        }
        control("历史对话",true); control(title,true); control(answer,false); control("相册",false); control("拍照",false);
        control("语音输入",false); control("发送",false);
    }
    private Loopback ready() throws Exception {
        stage="runtime-ready";
        long until=SystemClock.elapsedRealtime()+320000;
        while (app.api==null && SystemClock.elapsedRealtime()<until && !"failed".equals(app.serviceState)) Thread.sleep(250);
        Loopback api=app.api;
        if (api==null) throw new AssertionError();
        stage="signed-out";
        if (!"signed-out".equals(api.rpc("account/getState",Wire.obj()).getString("status"))) throw new AssertionError();
        return api;
    }
    private void stopRuntime() throws Exception {
        Loopback api=ready();
        stage="service-stop";
        activity.stopService(new Intent(activity,HarnessService.class));
        long until=SystemClock.elapsedRealtime()+20000;
        while (SystemClock.elapsedRealtime()<until) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); app.log.export(out);
            String log=out.toString("UTF-8");
            if (app.api==null && log.lastIndexOf("PROOT_DSH_EXIT")>log.lastIndexOf("RUNTIME_READY")) {
                try { callback(api); } catch (java.net.ConnectException expected) { return; }
            }
            Thread.sleep(150);
        }
        throw new AssertionError();
    }
    private void loginInit() throws Exception {
        Loopback api=ready();
        long until;
        stage="auth-init";
        JSONObject v=api.rpc("account/startSignIn",Wire.obj("client",Wire.client(),"callbackOrigin",api.origin,"loginSource","web"));
        String attempt=v.getJSONObject("attempt").getString("id");
        try {
            until=SystemClock.elapsedRealtime()+60000;
            while ("initializing".equals(v.getJSONObject("attempt").optString("phase")) && SystemClock.elapsedRealtime()<until) {
                Thread.sleep(300); v=api.rpc("account/getState",Wire.obj());
            }
            stage="official-authorize-url";
            if (Wire.authorization(v.getJSONObject("attempt").optString("authorizeUrl"))==null) throw new AssertionError();
            stage="empty-callback";
            if (callback(api)!=400) throw new AssertionError();
        } finally { api.rpc("account/cancelSignIn",Wire.obj("attemptId",attempt)); }
        stage="cancelled";
        if (callback(api)!=404) throw new AssertionError();
        ByteArrayOutputStream out=new ByteArrayOutputStream(); app.log.export(out);
        if (!out.toString("UTF-8").contains("RUNTIME_READY")) throw new AssertionError();
    }
    private int callback(Loopback api) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(api.origin+"/oauth/callback").openConnection(Proxy.NO_PROXY);
        c.setInstanceFollowRedirects(false);
        try { return c.getResponseCode(); } finally { c.disconnect(); }
    }
}
