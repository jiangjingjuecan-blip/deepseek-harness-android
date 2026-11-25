package com.sunwin.deepseekharness;

import org.json.*;
import java.net.URI;
import android.content.Context;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    static JSONObject prompt(Context ctx,String session,String id,String text,List<Attachment> files,int allowance) throws IOException {
        return prompt(ctx,session,id,text,files,allowance,Attachment.documentImageSlots(files));
    }
    static JSONObject prompt(Context ctx,String session,String id,String text,List<Attachment> files,int allowance,int docSlots) throws IOException {
        return request(obj("sessionId",session,"requestId",id,"mode","queue","content",parts(ctx,text,files,allowance,docSlots,false)));
    }
    static JSONArray parts(Context ctx,String text,List<Attachment> files,int allowance,int docSlots,boolean historical) throws IOException {
        if (Attachment.photos(files)>Attachment.MAX_PHOTOS || Attachment.documents(files)>Attachment.MAX_DOCUMENTS)
            throw new IOException("attachment-limit");
        docSlots=Math.min(docSlots,Attachment.documentImageSlots(files));
        allowance=Math.max(0,Math.min(allowance,ContextUsage.MAX_FILE_BYTES));
        JSONArray parts=new JSONArray();
        if (!text.isEmpty()) parts.put(obj("type","text","text",text));
        // Give short documents their full length and divide the remainder between long ones.
        List<Integer> docs=new ArrayList<>();
        for (int i=0;i<files.size();i++) if (files.get(i).kind==Attachment.Kind.TEXT) docs.add(i);
        docs.sort(Comparator.comparingInt(i->files.get(i).textBytes));
        int[] limits=new int[files.size()];
        for (int i=0;i<docs.size();i++) {
            int at=docs.get(i);
            limits[at]=Math.min(files.get(at).textBytes,allowance/(docs.size()-i));
            allowance-=limits[at];
        }
        int image=0;
        for (int i=0;i<files.size();i++) {
            Attachment item=files.get(i);
            if (item.kind==Attachment.Kind.TEXT) {
                int sent=document(ctx,parts,item,limits[i],Math.min(docSlots,Attachment.MAX_EMBEDDED_IMAGES),historical);
                image+=sent; docSlots-=sent;
            } else if (item.kind==Attachment.Kind.SCAN) {
                parts.put(obj("type","text","text",ContextUsage.fileLabel(item.name)));
                int pages=Math.min(docSlots,item.imageCount());
                for (int p=0;p<pages;p++) addImage(ctx,parts,item.paths.get(p),item.name+" - "+(p+1)+".jpg",historical);
                image+=pages; docSlots-=pages;
            } else {
                addImage(ctx,parts,item.paths.get(0),"照片"+(++image)+".jpg",historical);
            }
        }
        return parts;
    }
    private static int document(Context ctx,JSONArray parts,Attachment item,int limit,int slots,boolean historical) throws IOException {
        byte[] raw=Files.readAllBytes(Attachment.file(ctx,item.paths.get(0)).toPath());
        int end=Math.min(raw.length,limit);
        while (end>0 && end<raw.length && (raw[end]&0xc0)==0x80) end--;
        String body=new String(raw,0,end,StandardCharsets.UTF_8);
        StringBuilder part=new StringBuilder(ContextUsage.fileLabel(item.name));
        int cursor=0,sent=0;
        for (int i=0;i<item.imageOffsets.size();i++) {
            int at=item.imageOffsets.get(i);
            if (at>=body.length()) break;
            String mark=Attachment.marker(i+1);
            part.append(body,cursor,at);
            cursor=Math.min(at+mark.length(),body.length());
            // Only recorded offsets designate images. Literal brackets in the source are ordinary text.
            if (sent>=slots || !body.startsWith(mark,at)) continue;
            parts.put(obj("type","text","text",part.append(mark).toString()));
            addImage(ctx,parts,item.paths.get(i+1),item.name+" "+mark+".jpg",historical);
            sent++; part.setLength(0);
        }
        part.append(body,cursor,body.length());
        if (end<raw.length) part.append("\n…");
        if (part.length()>0) parts.put(obj("type","text","text",part.toString()));
        return sent;
    }
    private static void addImage(Context ctx,JSONArray parts,String path,String name,boolean historical) throws IOException {
        if (historical) { parts.put(obj("type","text","text","[图片："+name+"]")); return; }
        byte[] raw=Files.readAllBytes(Attachment.file(ctx,path).toPath());
        parts.put(obj("type","image","mediaType","image/jpeg","data",Base64.getEncoder().encodeToString(raw),"name",name));
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
