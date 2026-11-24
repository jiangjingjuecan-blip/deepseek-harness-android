package com.sunwin.deepseekharness;

import org.json.JSONObject;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import static com.sunwin.deepseekharness.Diagnostics.Event.*;

final class Account {
    enum Listing {
        UNKNOWN("模型目录待确认"), ONLINE("账号模型"), BUNDLED("内置模型目录"), CACHED("上次的模型目录");
        final String label;
        Listing(String label) { this.label=label; }

        // DSH 0.2.2-rc.2 returns identical RPC groups for online and bundled listings.
        // Its account provider reports the source on stderr. Retain only the fixed outcome.
        static Listing fromLine(String line) {
            if (!line.startsWith("[llm-deepseek-account] model listing ")) return null;
            if (line.endsWith("using the bundled catalog")) return BUNDLED;
            if (line.contains("keeping the previous catalog")) return CACHED;
            if (line.contains(" loaded ") && line.endsWith(" row(s)")) return ONLINE;
            return null;
        }
    }
    final HarnessApp app;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    volatile boolean stored, confirmed, loading;
    volatile String phase = "", balance = "", selected = "", modelError = "";
    volatile List<String> models = Collections.emptyList();
    volatile URI authorize;
    private String attemptId = "", lastEvent = "";
    private long detailsAt;
    private boolean polling;
    Account(HarnessApp app) { this.app=app; }
    void disconnected() {
        io.execute(() -> {
            stored=false; confirmed=false; loading=false; phase=""; balance=""; selected=""; authorize=null;
            models=Collections.emptyList(); modelError=""; detailsAt=0; lastEvent=""; app.changed();
        });
    }
    void refresh(boolean force) {
        Loopback api=app.api;
        if (api != null) io.execute(() -> {
            if (api != app.api) return;
            try { read(api, api.rpc("account/getState",Wire.obj()),force); }
            catch (Exception e) {
                if (api!=app.api) return;
                app.log.add(NETWORK_ERROR);
                confirmed=false; balance=""; detailsAt=0;
                clearModels("无法读取账号状态");
                app.changed();
                if (pending()) schedulePoll();
            }
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
        if (!stored) {
            balance=""; clearModels(""); confirmed=false;
            if (wasStored) api.listing=Listing.UNKNOWN;
        }
        app.changed();
        if (pending) schedulePoll();
        if (stored && (force || !wasStored || System.currentTimeMillis()-detailsAt>45000)) {
            detailsAt=System.currentTimeMillis();
            try {
                List<String> available=Wire.models(api.rpc("session/modelCatalog",Wire.obj()));
                if (api!=app.api) return;
                models=available;
                modelError=available.isEmpty() ? "账号暂无可用模型" : "";
                if (!available.contains(selected)) selected=available.isEmpty() ? "" : available.get(0);
            } catch (Exception e) {
                if (api!=app.api) return;
                clearModels(e instanceof ChatFailure ? e.getMessage() : "模型目录读取失败");
                app.log.add(MODEL_ERROR);
            }
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
    private void clearModels(String message) {
        models=Collections.emptyList(); selected=""; modelError=message;
    }
    String modelStatus(Loopback api) {
        if (!modelError.isEmpty()) return modelError;
        if (api.listing!=Listing.ONLINE && !selected.isEmpty()) return api.listing.label;
        return confirmed && !selected.isEmpty() ? selected : label();
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
