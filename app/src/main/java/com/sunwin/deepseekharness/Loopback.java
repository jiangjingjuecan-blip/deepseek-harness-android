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
    private String cookie;
    Loopback(int port, Diagnostics log) {
        if (port < 1 || port > 65535) throw new IllegalArgumentException();
        origin = "http://127.0.0.1:" + port;
        this.log = log;
    }
    void bootstrap(String path) throws IOException {
        try (Response response = http.newCall(new Request.Builder().url(origin + path).header("Connection", "close").build()).execute()) {
            log.add(HTTP_BOOTSTRAP, response.code());
            String header = response.header("Set-Cookie");
            if (response.code() != 303 || header == null) throw new IOException("bootstrap");
            cookie = header.split(";", 2)[0];
        }
    }
    private Request.Builder local(String path) {
        return new Request.Builder().url(origin + path).header("Origin", origin).header("Cookie", cookie);
    }
    JSONObject rpc(String method, JSONObject args) throws IOException, JSONException {
        String id = UUID.randomUUID().toString();
        JSONObject body = Wire.obj("type", "client-request", "rpcId", id, "method", method, "payload", Wire.obj("args",args));
        Request req = local("/api/" + method).header("Connection", "close").post(RequestBody.create(body.toString(), MediaType.get("application/json"))).build();
        try (Response response = http.newCall(req).execute()) {
            log.add(method.startsWith("account/") ? HTTP_ACCOUNT : method.endsWith("Catalog") ? HTTP_MODELS : HTTP_CHAT, response.code());
            if (!response.isSuccessful() || response.body() == null) throw new IOException("rpc-http");
            JSONObject reply = new JSONObject(response.body().string());
            JSONObject result = reply.getJSONObject("result");
            if (!id.equals(reply.optString("rpcId")) || !result.getBoolean("ok")) throw new IOException("rpc-rejected");
            return result.optJSONObject("value");
        }
    }
    WebSocket follow(WebSocketListener listener) {
        return http.newWebSocket(local("/api/remote.mux").build(), listener);
    }
    @Override public void close() {
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        cookie = null;
    }
}
