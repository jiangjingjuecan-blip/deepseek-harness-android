package com.sunwin.deepseekharness;

import okhttp3.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static com.sunwin.deepseekharness.Diagnostics.Event.*;
import static com.sunwin.deepseekharness.ChatFailure.Stage.*;

final class ChatEngine {
    private final HarnessApp app;
    private final String cwd;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService cleanup=Executors.newSingleThreadExecutor();
    private volatile Work active;
    // SQLite can reuse both chat and message IDs. Only the original operation may publish a result.
    private static final class Work {
        final History.Chat chat;
        final String model;
        WebSocket socket;
        Work(History.Chat chat,String model) { this.chat=chat; this.model=model; }
    }
    ChatEngine(HarnessApp app,String cwd) { this.app=app; this.cwd=cwd; }
    boolean busy() { return active!=null; }
    synchronized long delete(long id,long current) {
        History.Chat chat=app.history.chat(id);
        if (chat==null) return current;
        List<String> sessions=app.history.sessions(id);
        long next=app.history.delete(id,current,app.log);
        app.imports.cancel(id);
        if (active!=null && active.chat.id==id) {
            if (active.socket!=null) active.socket.cancel();
            active=null;
        }
        for (String session:sessions) archive(app.api,session);
        return next;
    }
    private void archive(Loopback api,String session) {
        if (session==null || session.isEmpty()) return;
        if (api==null) { app.log.add(SESSION_ARCHIVE_OFFLINE); return; }
        // DSH Web 0.2.2-rc.2 can stop and archive a session. Its journal has no deletion RPC.
        cleanup.execute(()->{
            try { api.rpc("workspace/archiveSession",Wire.request(Wire.obj("sessionId",session,"stopActivity",true))); }
            catch (IOException | JSONException e) { app.log.add(SESSION_ARCHIVE_FAILED); }
        });
    }
    private synchronized boolean link(Work work,String session) {
        if (active!=work) return false;
        app.history.linkBranch(work.chat.branch,session);
        return true;
    }
    private synchronized void meter(Work work,ContextUsage usage) {
        if (active==work) app.history.meterBranch(work.chat.branch,work.model,usage);
    }
    private synchronized void savePartial(Work work,long row,String body) {
        if (active==work) app.history.update(row,body,"pending");
    }
    private synchronized WebSocket follow(Work work,Loopback api,Feed feed) throws ChatFailure {
        if (active!=work) return null;
        work.socket=api.follow(feed);
        return work.socket;
    }
    private synchronized void finish(Work work) {
        if (active==work) active=null;
        app.changed();
    }
    synchronized boolean send(long chatId,String text,List<Attachment> files) {
        if (!ready(chatId) || Attachment.photos(files)>Attachment.MAX_PHOTOS || Attachment.documents(files)>Attachment.MAX_DOCUMENTS) return false;
        History.Chat chat=app.history.chat(chatId);
        if (text.isEmpty() && files.isEmpty() && app.history.diceBefore(chat.branch,Long.MAX_VALUE).isEmpty()) return false;
        return generate(chatId,()->app.history.begin(chatId,text,files),false);
    }
    synchronized boolean regenerate(long chat,long message) {
        return ready(chat) && generate(chat,()->app.history.regenerate(chat,message),true);
    }
    synchronized boolean answerUser(long chat,long user) {
        return ready(chat) && generate(chat,()->app.history.answerUser(chat,user),true);
    }
    private boolean ready(long chat) {
        return !busy() && !app.imports.busy(chat) && app.api!=null && app.account.stored && !app.account.selected.isEmpty();
    }
    private boolean generate(long chatId,java.util.function.LongSupplier insert,boolean fresh) {
        Loopback api=app.api;
        try {
            long row=insert.getAsLong();
            Work work=new Work(app.history.chat(chatId),app.account.selected);
            long branch=work.chat.branch;
            History.Message user=app.history.at(branch,app.history.last(branch,row));
            String text=app.history.promptText(branch,user);
            active=work; app.changed();
            io.execute(() -> run(api,work,row,user,text,fresh));
            return true;
        } catch (android.database.sqlite.SQLiteException e) {
            ChatFailure failure=ChatFailure.caught(e,SAVE_REPLY);
            app.log.interrupted(SAVE_REPLY,failure); app.notice(failure.getMessage()); return false;
        }
    }
    private synchronized boolean track(Work work,String remote) {
        if (active!=work) return false;
        app.history.trackSession(work.chat.id,remote); return true;
    }
    private synchronized void anchor(Work work,long id,String remote,long seq) {
        if (active==work) app.history.anchor(id,remote,seq);
    }
    private static String digest(String text) throws java.security.NoSuchAlgorithmException {
        byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder(64);
        for (byte b:bytes) { hex.append(Character.forDigit((b & 255)>>>4,16)); hex.append(Character.forDigit(b & 15,16)); }
        return hex.toString();
    }
    private void locateLegacy(Loopback api,Work work,List<History.Message> prefix,int count) throws Exception {
        String remote=prefix.get(count-1).session;
        JSONArray expected=new JSONArray();
        for (int i=0;i<count;i++) {
            History.Message m=prefix.get(i);
            if (!"done".equals(m.state) || !remote.equals(m.session)) break;
            expected.put(new JSONArray().put(m.role).put(m.text.isEmpty() ? JSONObject.NULL : digest(m.text)));
        }
        if (expected.length()==0) return;
        JSONObject reply;
        try {
            reply=api.rpc("commands/execute",Wire.obj("agentId",remote,"line","/phone-locate "+expected,"submittedAttachments",new JSONArray()));
        } catch (ChatFailure e) {
            if ("session/not-found".equals(e.code) || "gateway/context-not-found".equals(e.code)) return;
            throw e;
        }
        JSONObject result=reply==null ? null : reply.optJSONObject("result");
        if (result==null || !"success".equals(result.optString("kind"))) return;
        JSONArray cuts=new JSONArray(result.getString("text"));
        if (cuts.length()>expected.length()) throw new ChatFailure("存档位置无效");
        synchronized (this) {
            if (active!=work) throw new ChatFailure("已取消");
            android.database.sqlite.SQLiteDatabase db=app.history.getWritableDatabase(); db.beginTransaction();
            try {
                for (int i=0;i<cuts.length();i++) app.history.anchor(prefix.get(i).id,remote,cuts.getLong(i));
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
    }
    // Resolve only the immutable prefix before the new input. A compacted sibling
    // can keep changing: the inclusive journal seq of our fork never moves.
    private String session(Loopback api,Work work,long through,boolean fresh) throws Exception {
        if (!fresh && !work.chat.dirty && work.chat.remote!=null) return work.chat.remote;
        List<History.Message> prefix=app.history.line(work.chat.branch,0,through);
        int tail=prefix.size();
        while (tail>0 && prefix.get(tail-1).session==null) tail--;
        if (tail>0 && prefix.get(tail-1).seq<0) {
            locateLegacy(api,work,prefix,tail);
            prefix=app.history.line(work.chat.branch,0,through);
            tail=prefix.size();
            while (tail>0 && prefix.get(tail-1).seq<0) tail--;
        }
        JSONObject created=null;
        if (tail>0) {
            History.Message cut=prefix.get(tail-1);
            try {
                created=api.rpc("session/fork",Wire.request(Wire.obj("sessionId",cut.session,"atSeq",cut.seq)));
            } catch (ChatFailure e) {
                if (!"session/not-found".equals(e.code) && !"session/fork-unavailable".equals(e.code)) throw e;
                tail=0;
            }
        }
        if (created==null) created=api.rpc("session/create",Wire.request(Wire.obj("cwd",cwd,"agentPreset","phone-chat")));
        String remote=created==null ? "" : created.optString("sessionId");
        if (remote.isEmpty()) throw new ChatFailure("DSH 未返回会话标识");
        if (!track(work,remote)) { archive(api,remote); throw new ChatFailure("已取消"); }
        // Select the route before restoring. Each private maintenance turn consumes its
        // empty marker before the model step and keeps the original user/assistant role.
        if (tail<prefix.size()) select(api,remote,work.model);
        for (int i=tail;i<prefix.size();i++) {
            if (active!=work) throw new ChatFailure("已取消");
            History.Message m=prefix.get(i);
            if ("dice".equals(m.role)) continue;  // Injected exactly once with the following user row.
            String body="user".equals(m.role) ? app.history.promptText(work.chat.branch,m) : m.text;
            JSONArray parts=Wire.parts(app,body,m.attachments,m.fileBytes<0 ? ContextUsage.MAX_FILE_BYTES : m.fileBytes,
                    m.imageSlots<0 ? Attachment.documentImageSlots(m.attachments) : m.imageSlots,true);
            if (parts.length()==0) continue; // Empty interrupted reply has no model-visible content.
            JSONObject value=api.rpc("commands/execute",Wire.obj("agentId",remote,"line","/phone-restore "+Wire.obj("role",m.role,"content",parts),
                    "submittedAttachments",new JSONArray()));
            JSONObject result=value==null ? null : value.optJSONObject("result");
            if (result==null || !"success".equals(result.optString("kind")) || !result.optString("text").matches("[0-9]{1,15}"))
                throw new ChatFailure("分支恢复失败");
            anchor(work,m.id,remote,Long.parseLong(result.getString("text")));
        }
        if (!link(work,remote)) throw new ChatFailure("已取消");
        return remote;
    }
    private void select(Loopback api,String session,String model) throws Exception {
        api.rpc("session/selectModel",Wire.request(Wire.obj("sessionId",session,"provider","deepseek-account","model",model)));
    }
    synchronized void compact(long chatId) {
        Loopback api=app.api;
        String model=app.account.selected;
        if (busy()) { app.notice("正在处理"); return; }
        Work work=new Work(app.history.chat(chatId),model);
        if (work.chat.remote==null && app.history.last(work.chat.branch,Long.MAX_VALUE)==0) { app.notice("无需压缩"); return; }
        if (api==null || !app.account.stored || model.isEmpty()) { app.notice("未连接"); return; }
        active=work; app.changed();
        io.execute(()->{
            try {
                if (active!=work) return;
                String session=session(api,work,Long.MAX_VALUE,false);
                select(api,session,model);
                if (active!=work) return;
                ContextUsage usage=ContextUsage.prepare(api,session,"manual");
                meter(work,usage);
                if (active==work) app.notice(usage.compacted ? "成功" : "无需压缩");
            } catch (Exception e) {
                if (active==work) {
                    ChatFailure failure=ChatFailure.caught(e,CONTEXT);
                    app.log.interrupted(CONTEXT,failure); app.notice("压缩失败");
                }
            } finally { finish(work); }
        });
    }
    private void run(Loopback api,Work work,long row,History.Message user,String text,boolean fresh) {
        List<Attachment> files=user.attachments;
        String requestId=UUID.randomUUID().toString();
        Reply reply=new Reply(requestId);
        WebSocket socket=null;
        String session=work.chat.remote;
        ChatFailure.Stage stage=CREATE_SESSION;
        ChatFailure failure=null;
        long draft=ContextUsage.estimate(text,files);
        ContextUsage prepared=null;
        try {
            if (active!=work) return;
            session=session(api,work,user.id-1,fresh);
            stage=SELECT_MODEL;
            select(api,session,work.model);
            stage=CONTEXT;
            if (active!=work) return;
            prepared=ContextUsage.prepare(api,session,ContextUsage.argument(draft,files));
            draft+=prepared.fileBytes+(long)prepared.documentImages*ContextUsage.DOCUMENT_IMAGE_COST;
            meter(work,prepared);
            app.changed();
            stage=READ_ATTACHMENT;
            int bytes=user.fileBytes<0 ? prepared.fileBytes : Math.min(user.fileBytes,prepared.fileBytes);
            int images=user.imageSlots<0 ? prepared.documentImages : Math.min(user.imageSlots,prepared.documentImages);
            JSONObject prompt=Wire.prompt(app,session,requestId,text,files,bytes,images);
            synchronized (this) {
                if (active!=work) return;
                app.history.allowance(user.id,bytes,images);
            }
            Feed feed=new Feed(Wire.follow(session).toString());
            stage=FOLLOW;
            socket=follow(work,api,feed);
            if (socket==null) return;
            JSONObject snapshot=feed.next(25,"订阅快照超时");
            if (!"snapshot".equals(snapshot.getString("type"))) throw new ChatFailure("订阅首帧缺少快照");
            ContextUsage opening=ContextUsage.projection(snapshot,prepared.window);
            if (opening!=null && opening.window==prepared.window) {
                meter(work,opening); app.changed();
            }
            stage=PROMPT;
            if (active!=work) return;
            JSONObject ack=api.rpc("session/prompt",prompt);
            if (ack==null || !ack.optBoolean("accepted")) throw new ChatFailure("DSH 未接受消息");
            stage=REPLY;
            long saved=0;
            boolean dirty=false;
            String visible="";
            while (!reply.ended) {
                ChatFailure previous=reply.attemptFailure;
                long previousCount=reply.contextTokens;
                dirty |= reply.accept(feed.next(180,"等待回复事件超时"));
                if (reply.contextTokens>=0 && reply.contextTokens!=previousCount) {
                    meter(work,new ContextUsage(reply.contextTokens,prepared.window,false));
                    app.changed();
                }
                if (reply.attemptFailure!=null && reply.attemptFailure!=previous) app.log.attemptFailed(reply.attemptFailure);
                long now=android.os.SystemClock.elapsedRealtime();
                if (dirty && !reply.ended && (feed.messages.isEmpty() || now-saved>=70)) {
                    String body=reply.body();
                    if (!body.equals(visible)) {
                        savePartial(work,row,body); app.changed(); visible=body;
                    }
                    dirty=false; saved=now;
                }
            }
            failure=reply.failure;
        } catch (Exception e) {
            failure=ChatFailure.caught(e,stage);
            ChatFailure stopped=api.stoppedBecause();
            if (stopped!=null && stage!=READ_ATTACHMENT && failure.code.isEmpty() && failure.access==null) {
                failure=stopped;
            }
        } finally {
            if (socket!=null) socket.close(1000,"");
            saveReply(work,row,user.id,session,reply,failure,stage,prepared,draft);
            app.changed();
            if (active==work && reply.active() && api==app.api) {
                try { api.rpc("session/cancel",Wire.request(Wire.obj("sessionId",session))); }
                catch (Exception ignored) { }
            }
            finish(work);
        }
    }
    private synchronized void saveReply(Work work,long row,long user,String session,Reply reply,ChatFailure failure,
                                        ChatFailure.Stage stage,ContextUsage prepared,long draft) {
        if (active!=work) return;
        try {
            android.database.sqlite.SQLiteDatabase db=app.history.getWritableDatabase(); db.beginTransaction();
            try {
                if (session!=null) anchor(work,user,session,reply.userSeq);
                if (failure==null && reply.completed) {
                    anchor(work,row,session,reply.endSeq);
                    app.history.update(row,reply.body(),"done");
                    if (reply.contextTokens<0 && prepared!=null) meter(work,
                            new ContextUsage(prepared.used+draft+ContextUsage.estimate(reply.body(),Collections.emptyList()),prepared.window,false));
                    app.log.add(CHAT_COMPLETE);
                } else {
                    if (failure==null) failure=new ChatFailure("DSH 回合未结束");
                    app.log.interrupted(stage,failure);
                    app.history.dirty(work.chat.branch);
                    app.history.update(row,reply.body(),"interrupted",failure.getMessage());
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } catch (android.database.sqlite.SQLiteException e) {
            ChatFailure storage=ChatFailure.caught(e,SAVE_REPLY);
            app.log.interrupted(SAVE_REPLY,storage); app.notice(storage.getMessage());
        }
    }
    private static final class Frame {
        final String json;
        final ChatFailure failure;
        Frame(String json,ChatFailure failure) { this.json=json; this.failure=failure; }
    }
    private final class Feed extends WebSocketListener {
        private final BlockingQueue<Frame> messages=new LinkedBlockingQueue<>();
        private final String open;
        Feed(String open) { this.open=open; }
        @Override public void onOpen(WebSocket ws,Response response) {
            app.log.add(WS_OPEN,response.code());
            if (!ws.send(open)) fail(new ChatFailure("订阅请求未送达"));
        }
        private void fail(ChatFailure failure) { messages.offer(new Frame(null,failure)); }
        @Override public void onMessage(WebSocket ws,String json) { messages.offer(new Frame(json,null)); }
        @Override public void onMessage(WebSocket ws,okio.ByteString bytes) { fail(new ChatFailure("收到二进制回复帧")); }
        @Override public void onClosed(WebSocket ws,int code,String reason) {
            app.log.add(WS_CLOSED,code); fail(ChatFailure.wsClosed(code,reason));
        }
        @Override public void onClosing(WebSocket ws,int code,String reason) { fail(ChatFailure.wsClosed(code,reason)); ws.close(1000,""); }
        @Override public void onFailure(WebSocket ws,Throwable e,Response response) {
            if (response!=null) fail(new ChatFailure("WebSocket 握手失败 · HTTP " + response.code()));
            else fail(ChatFailure.caught(e,FOLLOW));
        }
        JSONObject next(int seconds,String timeoutLabel) throws Exception {
            Frame frame=messages.poll(seconds,TimeUnit.SECONDS);
            if (frame==null) throw new ChatFailure(timeoutLabel+"（"+seconds+" 秒）");
            if (frame.failure!=null) throw frame.failure;
            return Wire.followItem(new JSONObject(frame.json));
        }
    }
}
