package com.sunwin.deepseekharness;

import okhttp3.*;
import org.json.*;
import java.io.IOException;
import java.net.Proxy;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static com.sunwin.deepseekharness.Diagnostics.Event.*;

final class Loopback implements AutoCloseable {
    final String origin;
    private final OkHttpClient http = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(5,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS)
            .pingInterval(20,TimeUnit.SECONDS).build();
    private final Diagnostics log;
    private final Process runtime;
    private volatile boolean closed;
    private volatile String cookie;
    volatile Account.Listing listing = Account.Listing.UNKNOWN;
    Loopback(int port, Diagnostics log) {
        this(port, log, null);
    }
    Loopback(int port, Diagnostics log, Process runtime) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException();
        origin = "http://127.0.0.1:" + port;
        this.log = log;
        this.runtime = runtime;
    }
    void bootstrap(String path) throws IOException {
        try (Response response = http.newCall(new Request.Builder().url(origin + path).header("Connection", "close").build()).execute()) {
            log.add(HTTP_BOOTSTRAP, response.code());
            String header = response.header("Set-Cookie");
            if (response.code() != 303 || header == null)
                throw new ChatFailure("本机鉴权失败 · HTTP " + response.code());
            cookie = header.split(";", 2)[0];
        }
    }
    private Request.Builder local(String path) throws ChatFailure {
        String current = cookie;
        if (current == null || closed) throw new ChatFailure("本机会话已关闭");
        return new Request.Builder().url(origin + path).header("Origin", origin).header("Cookie", current);
    }
    JSONObject rpc(String method, JSONObject args) throws IOException, JSONException {
        String id = UUID.randomUUID().toString();
        JSONObject body = Wire.obj("type", "client-request", "rpcId", id, "method", method, "payload", Wire.obj("args",args));
        Request req = local("/api/" + method).header("Connection", "close").post(RequestBody.create(body.toString(), MediaType.get("application/json"))).build();
        try (Response response = http.newCall(req).execute()) {
            log.add(method.startsWith("account/") ? HTTP_ACCOUNT : method.endsWith("Catalog") ? HTTP_MODELS : HTTP_CHAT, response.code());
            if (!response.isSuccessful()) {
                JSONObject error = null;
                try {
                    JSONObject bodyError = new JSONObject(response.peekBody(8192).string());
                    JSONObject result = bodyError.optJSONObject("result");
                    error = result == null ? bodyError.optJSONObject("error") : result.optJSONObject("error");
                    if (error == null) error = bodyError;
                } catch (IOException | JSONException ignored) { }
                throw ChatFailure.remote(error, response.code(), "本机 HTTP 请求失败");
            }
            if (response.body() == null) throw new ChatFailure("本机 HTTP 响应为空");
            return Wire.rpcValue(id,new JSONObject(response.body().string()));
        }
    }
    WebSocket follow(WebSocketListener listener) throws ChatFailure {
        return http.newWebSocket(local("/api/remote.mux").build(), listener);
    }
    ChatFailure stoppedBecause() {
        if (runtime != null && !runtime.isAlive()) return new ChatFailure("DSH 已退出（" + runtime.exitValue() + "）");
        return closed ? new ChatFailure("DSH 服务已停止") : null;
    }
    @Override public void close() {
        closed = true;
        cookie = null;
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
    }
}
