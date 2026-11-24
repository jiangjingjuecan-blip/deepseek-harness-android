package com.sunwin.deepseekharness;

import org.json.*;
import java.nio.charset.StandardCharsets;

/** Occupancy for a single model request, never cumulative session billing. */
final class ContextUsage {
    final long used, window;
    final boolean compacted;

    ContextUsage(long used,long window,boolean compacted) {
        this.used=used; this.window=window; this.compacted=compacted;
    }
    static long defaultWindow(String model) {
        return "deepseek-flash".equals(model) || "deepseek-v4-pro".equals(model) ? 1048576 : 65536;
    }
    static long estimate(String text,boolean photo) {
        return 16L+text.getBytes(StandardCharsets.UTF_8).length+(photo ? 1032 : 0);
    }
    String percent() {
        if (used>0 && used*100.0/window<1) return "<1%";
        return Math.min(100,Math.round(used*100.0/window))+"%";
    }

    static long tokens(JSONObject usage) {
        if (usage==null || !usage.has("inputTokens") || !usage.has("outputTokens")) return -1;
        long count=0;
        for (String key:new String[]{"inputTokens","cacheReadTokens","cacheWriteTokens","outputTokens"}) {
            long part=usage.optLong(key,usage.has(key) ? -1 : 0);
            if (part<0 || part>Integer.MAX_VALUE) return -1;
            count+=part;
        }
        return count;
    }
    static ContextUsage projection(JSONObject snapshot,long window) {
        JSONObject projections=snapshot.optJSONObject("projections");
        JSONObject values=projections==null ? null : projections.optJSONObject("values");
        JSONObject pressure=values==null ? null : values.optJSONObject("contextPressure");
        if (pressure==null) return null;
        long used=pressure.optLong("projectedTokens",pressure.optLong("pressureTokens",-1));
        long capacity=pressure.optLong("contextWindow",window);
        return used>=0 && capacity>0 ? new ContextUsage(used,capacity,false) : null;
    }
    static ContextUsage prepare(Loopback api,String session,String argument) throws Exception {
        JSONObject value=api.rpc("commands/execute",Wire.obj("agentId",session,"line","/phone-context "+argument,
                "submittedAttachments",new JSONArray()));
        JSONObject result=value==null ? null : value.optJSONObject("result");
        if (result==null) throw new ChatFailure("上下文服务未就绪");
        String text=result.optString("text");
        if (!"success".equals(result.optString("kind"))) {
            if ("消息过长".equals(text) || "上下文过长".equals(text) || "正在压缩".equals(text)) throw new ChatFailure(text);
            throw new ChatFailure("压缩失败");
        }
        if (!text.matches("[0-9]{1,15} [1-9][0-9]{0,14} [01]")) throw new ChatFailure("上下文响应无效");
        String[] fields=text.split(" ");
        return new ContextUsage(Long.parseLong(fields[0]),Long.parseLong(fields[1]),"1".equals(fields[2]));
    }
}
