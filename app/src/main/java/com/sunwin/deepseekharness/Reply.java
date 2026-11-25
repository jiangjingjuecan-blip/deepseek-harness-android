package com.sunwin.deepseekharness;

import org.json.*;
import java.util.*;

/** Interpret the opt-in Web follow stream. A durable assistant message replaces its provisional chunks. */
final class Reply {
    private final StringBuilder committed=new StringBuilder(), partial=new StringBuilder();
    private final String requestId;
    private boolean queued;
    private int turn=-1;
    private String attemptId;
    boolean ended, completed;
    long userSeq=-1, endSeq=-1;
    long contextTokens=-1;
    ChatFailure failure, attemptFailure;
    volatile String webStatus="", title="", webBlocksJson, citationsJson, webError="";
    private final List<WebTools.Source> liveSources=new ArrayList<>();
    private final Set<String> liveSourceUrls=new HashSet<>();
    private final Set<String> pendingCalls=new HashSet<>();
    Reply(String requestId) { this.requestId=requestId; }
    boolean active() { return turn>=0 && !ended; }
    synchronized List<WebTools.Source> sources() { return new ArrayList<>(liveSources); }
    String body() {
        if (partial.length()==0) return committed.toString();
        return committed.length()==0 ? partial.toString() : committed + "\n\n" + partial;
    }
    private synchronized boolean addSource(String url,String title,String site) {
        String safe=WebTools.safeHttpUrl(url);
        if (safe==null || !liveSourceUrls.add(safe)) return false;
        liveSources.add(new WebTools.Source(safe,title,site));
        citationsJson=WebTools.serializeSources(liveSources);
        return true;
    }
    private boolean acceptWebHook(JSONObject data) {
        if (!"phone-web".equals(data.optString("handlerId"))) return false;
        if (data.optInt("turn",-1)!=turn) return false;
        String point=data.optString("point","");
        String raw=data.optString("decision","");
        if (raw.isEmpty()) return false;
        try {
            JSONObject payload=new JSONObject(raw);
            switch (point) {
                case "phone-web:activity": {
                    String id=payload.optString("id","");
                    if (!id.isEmpty()) pendingCalls.add(id);
                    JSONArray qs=payload.optJSONArray("queries");
                    List<String> queries=new ArrayList<>();
                    if (qs!=null) for (int i=0;i<qs.length();i++) queries.add(qs.optString(i,""));
                    webStatus=WebTools.activityLabel(payload.optString("action",""),payload.optString("kind",""),
                            queries,payload.optString("url",""),payload.optString("pattern",""));
                    return true;
                }
                case "phone-web:result": {
                    String id=payload.optString("toolUseId","");
                    if (!id.isEmpty()) pendingCalls.remove(id);
                    String err=payload.optString("errorCode","");
                    if (!err.isEmpty()) webError=WebTools.toolErrorLabel(err,payload.optString("kind",""));
                    if (pendingCalls.isEmpty() && body().length()>0) webStatus="";
                    return true;
                }
                case "phone-web:citation": {
                    return addSource(payload.optString("url",""),payload.optString("title",""),payload.optString("site",""));
                }
                case "phone-web:title": {
                    String t=ChatTitle.sanitize(payload.optString("title",""));
                    if (!t.isEmpty()) title=t;
                    return false;
                }
                case "phone-web:blocks": {
                    JSONArray blocks=payload.optJSONArray("blocks");
                    if (blocks!=null && blocks.length()>0) webBlocksJson=blocks.toString();
                    JSONArray cites=payload.optJSONArray("citations");
                    if (cites!=null) {
                        for (int i=0;i<cites.length();i++) {
                            JSONObject c=cites.optJSONObject(i);
                            if (c!=null) addSource(c.optString("url",""),c.optString("title",""),c.optString("site",""));
                        }
                    }
                    JSONArray errs=payload.optJSONArray("errors");
                    if (errs!=null && errs.length()>0 && webError.isEmpty()) {
                        JSONObject first=errs.optJSONObject(0);
                        if (first!=null) webError=WebTools.toolErrorLabel(first.optString("errorCode",""),first.optString("kind",""));
                    }
                    pendingCalls.clear();
                    return true;
                }
                default: return false;
            }
        } catch (JSONException ignored) {
            return false;
        }
    }
    boolean accept(JSONObject item) throws JSONException {
        if ("assistant-stream".equals(item.optString("type"))) {
            JSONObject frame=item.getJSONObject("frame");
            if (!active()) return false;
            if ("start".equals(frame.optString("type"))) {
                if (frame.getInt("turn")!=turn) return false;
                attemptId=frame.getString("attemptId");
                contextTokens=-1;
                partial.setLength(0); attemptFailure=null; return true;
            }
            if (attemptId==null || !attemptId.equals(frame.optString("attemptId"))) return false;
            JSONObject chunk=frame.optJSONObject("chunk");
            if (chunk!=null && "usage".equals(chunk.optString("type"))) {
                long count=ContextUsage.tokens(chunk.optJSONObject("usage"));
                if (count>=0) contextTokens=count;
                return false;
            }
            if (chunk!=null && "text-delta".equals(chunk.optString("type"))) {
                String delta=chunk.getString("text");
                partial.append(delta);
                if (!delta.isEmpty() && !webStatus.isEmpty()) webStatus="";
                return true;
            }
            if (chunk!=null && "finish".equals(chunk.optString("type"))) {
                JSONObject reason=chunk.optJSONObject("reason");
                if (reason!=null && reason.optJSONObject("failure")!=null)
                    attemptFailure=ChatFailure.remote(reason.optJSONObject("failure"),0,"模型请求失败");
            }
        }
        if (!"event".equals(item.optString("type"))) return false;
        JSONObject event=item.getJSONObject("event");
        JSONObject data=event.optJSONObject("data");
        // In queue mode DSH records our source.rpcId in the next-turn inbox before
        // opening the turn, even if it then fails before creating a user/message.
        if (!active()) {
            if ("agent/inbox/spliced".equals(event.optString("type")) && data!=null && "next-turn".equals(data.optString("target"))) {
                JSONArray inserted=data.getJSONArray("inserted");
                for (int i=0; i<inserted.length(); i++) {
                    JSONObject source=inserted.getJSONObject(i).optJSONObject("source");
                    if (source!=null && requestId.equals(source.optString("rpcId"))) queued=true;
                }
            } else if (queued && !ended && "turn/start".equals(event.optString("type"))) turn=event.getJSONObject("data").getInt("turn");
            return false;
        }
        switch (event.getString("type")) {
            case "hook/result":
                return data!=null && acceptWebHook(data);
            case "user/message":
                JSONObject source=data.optJSONObject("source");
                if (source!=null && (requestId.equals(source.optString("rpcId"))
                        || (userSeq>=0 && attemptId==null && "runtime-context".equals(source.optString("kind")))))
                    userSeq=event.optLong("seq",userSeq);
                return false;
            case "assistant/message":
                if (event.getJSONObject("data").getInt("turn")!=turn || !"append".equals(event.optString("surfaceOp"))) return false;
                long count=ContextUsage.tokens(data.optJSONObject("usage"));
                if (count>=0) contextTokens=count;
                JSONArray parts=event.getJSONObject("data").getJSONObject("message").getJSONArray("content");
                StringBuilder full=new StringBuilder();
                for (int i=0;i<parts.length();i++) {
                    JSONObject part=parts.getJSONObject(i);
                    if ("text".equals(part.optString("type"))) full.append(part.getString("text"));
                }
                if (full.length()>0) {
                    if (committed.length()>0) committed.append("\n\n");
                    committed.append(full);
                    webStatus="";
                }
                partial.setLength(0); return true;
            case "turn/end":
                if (event.getJSONObject("data").getInt("turn")!=turn) return false;
                endSeq=event.optLong("seq",-1); ended=true; webStatus=""; JSONObject reason=data.optJSONObject("reason");
                completed=reason!=null && "completed".equals(reason.optString("kind")) && !body().isEmpty();
                if (!completed) failure=ChatFailure.turn(reason,attemptFailure);
                return true;
            default: return false;
        }
    }
}
