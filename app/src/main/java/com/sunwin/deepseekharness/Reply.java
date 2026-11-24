package com.sunwin.deepseekharness;

import org.json.*;

/** Interpret the opt-in Web follow stream. A durable assistant message replaces its provisional chunks. */
final class Reply {
    private final StringBuilder committed=new StringBuilder(), partial=new StringBuilder();
    boolean ended, completed;
    String body() {
        if (partial.length()==0) return committed.toString();
        return committed.length()==0 ? partial.toString() : committed + "\n\n" + partial;
    }
    boolean accept(JSONObject item) throws JSONException {
        if ("assistant-stream".equals(item.optString("type"))) {
            JSONObject frame=item.getJSONObject("frame");
            if ("start".equals(frame.optString("type"))) { partial.setLength(0); return true; }
            JSONObject chunk=frame.optJSONObject("chunk");
            if (chunk!=null && "text-delta".equals(chunk.optString("type"))) {
                partial.append(chunk.getString("text")); return true;
            }
        }
        if (!"event".equals(item.optString("type"))) return false;
        JSONObject event=item.getJSONObject("event");
        JSONObject data=event.optJSONObject("data");
        if (data==null) return false;
        switch (event.getString("type")) {
            case "assistant/message":
                JSONArray parts=data.getJSONObject("message").getJSONArray("content");
                StringBuilder full=new StringBuilder();
                for (int i=0;i<parts.length();i++) {
                    JSONObject part=parts.getJSONObject(i);
                    if ("text".equals(part.optString("type"))) full.append(part.getString("text"));
                }
                if (full.length()>0) {
                    if (committed.length()>0) committed.append("\n\n");
                    committed.append(full);
                }
                partial.setLength(0); return true;
            case "turn/end":
                ended=true; JSONObject reason=data.optJSONObject("reason");
                completed=reason!=null && "completed".equals(reason.optString("kind")) && !body().isEmpty();
                return true;
            default: return false;
        }
    }
}
