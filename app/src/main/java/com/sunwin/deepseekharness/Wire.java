package com.sunwin.deepseekharness;

import org.json.*;
import java.net.URI;
import java.util.*;
import java.math.BigDecimal;

final class Wire {
    static JSONObject obj(Object... values) {
        JSONObject obj = new JSONObject();
        try {
            for (int i = 0; i < values.length; i += 2) obj.put((String) values[i], values[i+1]);
        } catch (JSONException e) { throw new IllegalArgumentException("Invalid JSON"); }
        return obj;
    }
    static JSONObject request(JSONObject arg) { return obj("request", arg); }
    static JSONObject rpcValue(String id,JSONObject reply) throws ChatFailure, JSONException {
        if (!id.equals(reply.optString("rpcId"))) throw new ChatFailure("RPC 响应标识不匹配");
        JSONObject result=reply.getJSONObject("result");
        if (!result.getBoolean("ok")) throw ChatFailure.remote(result.optJSONObject("error"),0,"DSH 拒绝请求");
        return result.optJSONObject("value");
    }
    static JSONObject client() {
        return obj("version", "0.2.2-rc.2", "locale", "zh-CN", "timezoneOffsetSeconds",
                TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000);
    }
    static URI authorization(String url) {
        try {
            URI uri = URI.create(url);
            if ("https".equals(uri.getScheme()) && "platform.deepseek.com".equals(uri.getRawAuthority())
                    && "/dsh/authorize".equals(uri.getRawPath()) && uri.getFragment() == null) return uri;
        } catch (IllegalArgumentException ignored) { }
        return null;
    }
    static JSONObject prompt(String session, String id, String text, String jpeg) {
        JSONArray parts = new JSONArray();
        if (!text.isEmpty()) parts.put(obj("type", "text", "text", text));
        if (jpeg != null) parts.put(obj("type", "image", "mediaType", "image/jpeg", "data", jpeg, "name", "照片.jpg"));
        return request(obj("sessionId", session, "requestId", id, "mode", "queue", "content", parts));
    }
    static JSONObject follow(String session) {
        return obj("type","open","streamId","reply","endpoint","session/follow","payload", obj("args",
                request(obj("address", obj("kind","session","sessionId",session), "assistantStream",true,"maxMessages",4))));
    }
    static JSONObject followItem(JSONObject frame) throws ChatFailure, JSONException {
        if (!"reply".equals(frame.optString("streamId"))) throw new ChatFailure("订阅流标识不匹配");
        switch (frame.optString("type")) {
            case "item": return frame.getJSONObject("value");
            case "error": throw ChatFailure.remote(frame.optJSONObject("error"),0,"DSH 订阅失败");
            case "end": throw new ChatFailure("订阅提前结束");
            default: throw new ChatFailure("订阅帧格式不符");
        }
    }
    static List<String> models(JSONObject catalog) throws JSONException, ChatFailure {
        if (catalog==null) throw new ChatFailure("模型目录响应为空");
        JSONArray failures=catalog.optJSONArray("failures");
        for (int i=0; failures!=null && i<failures.length(); i++) {
            JSONObject failure=failures.getJSONObject(i);
            if ("deepseek-account".equals(failure.optString("id")))
                throw ChatFailure.remote(failure,0,"账号模型目录读取失败");
        }
        List<String> names = new ArrayList<>();
        JSONArray groups = catalog.getJSONArray("groups");
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.getJSONObject(i);
            if (!"deepseek-account".equals(group.optString("id"))) continue;
            JSONArray entries = group.getJSONArray("models");
            for (int j = 0; j < entries.length(); j++) names.add(entries.getJSONObject(j).getString("id"));
        }
        if (names.remove("deepseek-flash")) names.add(0, "deepseek-flash");
        return Collections.unmodifiableList(names);
    }
    static String balance(JSONObject value) throws JSONException {
        if (value == null || !"ready".equals(value.optString("status"))) return "";
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        for (String key : new String[]{"value","bonusWallets"}) {
            JSONArray wallets = value.optJSONArray(key);
            if (wallets == null) continue;
            for (int i = 0; i < wallets.length(); i++) {
                JSONObject w = wallets.getJSONObject(i);
                String c = w.getString("currency");
                if ("CNY".equals(c) || "USD".equals(c)) amounts.merge(c, new BigDecimal(w.getString("balance")), BigDecimal::add);
            }
        }
        List<String> totals = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> w : amounts.entrySet()) totals.add(("CNY".equals(w.getKey()) ? "¥" : "US$") + w.getValue().toPlainString());
        return String.join("  ·  ", totals);
    }
}
