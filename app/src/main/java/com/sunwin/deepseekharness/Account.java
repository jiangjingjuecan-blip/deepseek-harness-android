package com.sunwin.deepseekharness;

import org.json.JSONObject;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import static com.sunwin.deepseekharness.Diagnostics.Event.*;

final class Account {
    final HarnessApp app;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    volatile boolean stored, confirmed, loading;
    volatile String phase = "", balance = "", selected = "";
    volatile List<String> models = Collections.emptyList();
    volatile URI authorize;
    private String attemptId = "", lastEvent = "";
    private long detailsAt;
    private boolean polling;
    Account(HarnessApp app) { this.app=app; }
    void disconnected() {
        io.execute(() -> {
            stored=false; confirmed=false; loading=false; phase=""; balance=""; selected=""; authorize=null;
            models=Collections.emptyList(); detailsAt=0; lastEvent=""; app.changed();
        });
    }
    void refresh(boolean force) {
        Loopback api=app.api;
        if (api != null) io.execute(() -> {
            if (api != app.api) return;
            try { read(api, api.rpc("account/getState",Wire.obj()),force); }
            catch (Exception e) { app.log.add(NETWORK_ERROR); if (api==app.api && pending()) schedulePoll(); }
        });
    }
    void signIn() { action(true); }
    void signOut() { action(false); }
    private void action(boolean in) {
        Loopback api=app.api;
        if (api==null || loading) return;
        loading=true; app.changed();
        io.execute(() -> {
            try {
                if (api != app.api) return;
                JSONObject view = in ? api.rpc("account/startSignIn",Wire.obj("client",Wire.client(),"callbackOrigin",api.origin,"loginSource","web"))
                        : api.rpc("account/signOut",Wire.obj("client",Wire.client()));
                read(api,view,true);
            } catch (Exception e) { app.log.add(LOGIN_FAILED); app.notice(in ? "登录未完成，请重试" : "退出未完成，请重试"); }
            finally { loading=false; app.changed(); }
        });
    }
    void cancel() {
        Loopback api=app.api;
        io.execute(() -> {
            if (api == null || api != app.api || attemptId.isEmpty()) return;
            try { read(api,api.rpc("account/cancelSignIn",Wire.obj("attemptId",attemptId)),false); }
            catch (Exception e) { app.notice("取消未完成，请重试"); }
        });
    }
    private void read(Loopback api,JSONObject view,boolean force) throws Exception {
        if (api!=app.api) return;
        boolean wasStored=stored;
        stored="credential-stored".equals(view.getString("status"));
        JSONObject attempt=view.optJSONObject("attempt");
        String oldPhase=phase;
        phase=attempt==null ? "" : attempt.optString("phase");
        attemptId=attempt==null ? "" : attempt.optString("id");
        authorize=attempt==null ? null : Wire.authorization(attempt.optString("authorizeUrl"));
        boolean pending=pending();
        Diagnostics.Event event = stored ? LOGIN_STORED : pending ? ("exchanging".equals(phase) ? LOGIN_EXCHANGE : LOGIN_PENDING)
                : ("failed".equals(phase) || "expired".equals(phase)) ? LOGIN_FAILED : LOGIN_SIGNED_OUT;
        if (!event.name().equals(lastEvent)) { app.log.add(event); lastEvent=event.name(); }
        if (!oldPhase.equals(phase) && event==LOGIN_FAILED) app.notice("登录未完成，请重试");
        if (!stored) { balance=""; models=Collections.emptyList(); selected=""; confirmed=false; }
        app.changed();
        if (pending) schedulePoll();
        if (stored && (force || !wasStored || System.currentTimeMillis()-detailsAt>45000)) {
            detailsAt=System.currentTimeMillis();
            try {
                List<String> available=Wire.models(api.rpc("session/modelCatalog",Wire.obj()));
                if (api!=app.api) return;
                models=available;
                if (!available.contains(selected)) selected=available.isEmpty() ? "" : available.get(0);
            } catch (Exception e) { app.log.add(MODEL_ERROR); }
            app.changed();
            try {
                JSONObject profile=api.rpc("account/getProfile",Wire.obj("client",Wire.client()));
                if (api!=app.api) return;
                confirmed=profile!=null && "ready".equals(profile.optString("status"));
                if (confirmed) app.log.add(LOGIN_CONFIRMED);
            } catch (Exception e) { confirmed=false; }
            try {
                String b=Wire.balance(api.rpc("account/getBalance",Wire.obj("client",Wire.client())));
                if (api!=app.api) return;
                balance=b;
            } catch (Exception e) { balance=""; app.log.add(BALANCE_ERROR); }
            app.changed();
        }
    }
    private void schedulePoll() {
        if (polling) return;
        polling=true;
        io.schedule(() -> { polling=false; refresh(false); },1,TimeUnit.SECONDS);
    }
    boolean pending() { return Arrays.asList("initializing","waiting-browser","exchanging","committing").contains(phase); }
    String label() {
        if (confirmed) return "已登录";
        if (stored) return "已保存登录状态";
        return pending() ? "等待授权" : "未登录";
    }
}
