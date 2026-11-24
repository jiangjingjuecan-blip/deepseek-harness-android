package com.sunwin.deepseekharness;

import okhttp3.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
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
        long next=app.history.delete(id,current,app.log);
        if (active!=null && active.chat.id==id) {
            if (active.socket!=null) active.socket.cancel();
            active=null;
        }
        archive(app.api,chat.remote);
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
        app.history.link(work.chat.id,session);
        return true;
    }
    private synchronized void meter(Work work,ContextUsage usage) {
        if (active==work) app.history.meter(work.chat.id,work.model,usage);
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
    synchronized boolean send(long chatId,String text,String photo) {
        Loopback api=app.api;
        String model=app.account.selected;
        if (busy() || api==null || !app.account.stored || model.isEmpty() || (text.isEmpty() && photo==null)) return false;
        long row;
        try { row=app.history.begin(chatId,text,photo); }
        catch (android.database.sqlite.SQLiteException e) {
            ChatFailure failure=ChatFailure.caught(e,SAVE_REPLY);
            app.log.interrupted(SAVE_REPLY,failure); app.notice(failure.getMessage()); return false;
        }
        Work work=new Work(app.history.chat(chatId),model);
        active=work;
        app.changed();
        io.execute(() -> run(api,work,row,text,photo));
        return true;
    }
    private void select(Loopback api,String session,String model) throws Exception {
        api.rpc("session/selectModel",Wire.request(Wire.obj("sessionId",session,"provider","deepseek-account","model",model)));
    }
    synchronized void compact(long chatId) {
        Loopback api=app.api;
        String model=app.account.selected;
        if (busy()) { app.notice("正在处理"); return; }
        Work work=new Work(app.history.chat(chatId),model);
        String session=work.chat.remote;
        if (session==null) { app.notice("无需压缩"); return; }
        if (api==null || !app.account.stored || model.isEmpty()) { app.notice("未连接"); return; }
        active=work; app.changed();
        io.execute(()->{
            try {
                if (active!=work) return;
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
    private void run(Loopback api,Work work,long row,String text,String photo) {
        String requestId=UUID.randomUUID().toString();
        Reply reply=new Reply(requestId);
        WebSocket socket=null;
        String session=work.chat.remote;
        ChatFailure.Stage stage=CREATE_SESSION;
        ChatFailure failure=null;
        long draft=ContextUsage.estimate(text,photo!=null);
        ContextUsage prepared=null;
        try {
            if (active!=work) return;
            if (session==null) {
                JSONObject created=api.rpc("session/create",Wire.request(Wire.obj("cwd",cwd,"agentPreset","phone-chat")));
                if (created==null || !(created.opt("sessionId") instanceof String) || created.getString("sessionId").isEmpty())
                    throw new ChatFailure("DSH 未返回会话标识");
                session=created.getString("sessionId");
                if (!link(work,session)) { archive(api,session); return; }
            }
            stage=SELECT_MODEL;
            select(api,session,work.model);
            stage=CONTEXT;
            if (active!=work) return;
            prepared=ContextUsage.prepare(api,session,Long.toString(draft));
            meter(work,prepared);
            app.changed();
            stage=READ_PHOTO;
            String jpeg=photo==null ? null : Base64.getEncoder().encodeToString(Files.readAllBytes(new File(app.getFilesDir(),photo).toPath()));
            JSONObject prompt=Wire.prompt(session,requestId,text,jpeg);
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
            if (stopped!=null && stage!=READ_PHOTO && failure.code.isEmpty() && failure.access==null) {
                failure=stopped;
            }
        } finally {
            if (socket!=null) socket.close(1000,"");
            saveReply(work,row,reply,failure,stage,prepared,draft);
            app.changed();
            if (active==work && reply.active() && api==app.api) {
                try { api.rpc("session/cancel",Wire.request(Wire.obj("sessionId",session))); }
                catch (Exception ignored) { }
            }
            finish(work);
        }
    }
    private synchronized void saveReply(Work work,long row,Reply reply,ChatFailure failure,
                                        ChatFailure.Stage stage,ContextUsage prepared,long draft) {
        if (active!=work) return;
        try {
            if (failure==null && reply.completed) {
                app.history.update(row,reply.body(),"done");
                if (reply.contextTokens<0 && prepared!=null) meter(work,
                        new ContextUsage(prepared.used+draft+ContextUsage.estimate(reply.body(),false),prepared.window,false));
                app.log.add(CHAT_COMPLETE);
            } else {
                if (failure==null) failure=new ChatFailure("DSH 回合未结束");
                app.log.interrupted(stage,failure);
                app.history.update(row,reply.body(),"interrupted",failure.getMessage());
            }
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
