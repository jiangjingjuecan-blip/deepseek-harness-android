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
    volatile boolean busy;
    ChatEngine(HarnessApp app,String cwd) { this.app=app; this.cwd=cwd; }
    boolean send(long chatId,String text,String photo) {
        Loopback api=app.api;
        String model=app.account.selected;
        if (busy || api==null || !app.account.stored || model.isEmpty() || (text.isEmpty() && photo==null)) return false;
        long row;
        try { row=app.history.begin(chatId,text,photo); }
        catch (android.database.sqlite.SQLiteException e) {
            ChatFailure failure=ChatFailure.caught(e,SAVE_REPLY);
            app.log.interrupted(SAVE_REPLY,failure); app.notice(failure.getMessage()); return false;
        }
        busy=true;
        app.changed();
        io.execute(() -> run(api,chatId,row,model,text,photo));
        return true;
    }
    private void run(Loopback api,long chatId,long row,String model,String text,String photo) {
        String requestId=UUID.randomUUID().toString();
        Reply reply=new Reply(requestId);
        WebSocket socket=null;
        String session=null;
        ChatFailure.Stage stage=CREATE_SESSION;
        ChatFailure failure=null;
        try {
            session=app.history.chat(chatId).remote;
            if (session==null) {
                JSONObject created=api.rpc("session/create",Wire.request(Wire.obj("cwd",cwd,"agentPreset","phone-chat")));
                if (created==null || !(created.opt("sessionId") instanceof String) || created.getString("sessionId").isEmpty())
                    throw new ChatFailure("DSH 未返回会话标识");
                session=created.getString("sessionId");
                app.history.link(chatId,session);
            }
            stage=SELECT_MODEL;
            api.rpc("session/selectModel",Wire.request(Wire.obj("sessionId",session,"provider","deepseek-account","model",model)));
            stage=READ_PHOTO;
            String jpeg=photo==null ? null : Base64.getEncoder().encodeToString(Files.readAllBytes(new File(app.getFilesDir(),photo).toPath()));
            JSONObject prompt=Wire.prompt(session,requestId,text,jpeg);
            Feed feed=new Feed(Wire.follow(session).toString());
            stage=FOLLOW;
            socket=api.follow(feed);
            if (!"snapshot".equals(feed.next(25,"订阅快照超时").getString("type"))) throw new ChatFailure("订阅首帧缺少快照");
            stage=PROMPT;
            JSONObject ack=api.rpc("session/prompt",prompt);
            if (ack==null || !ack.optBoolean("accepted")) throw new ChatFailure("DSH 未接受消息");
            stage=REPLY;
            long saved=0;
            boolean dirty=false;
            String visible="";
            while (!reply.ended) {
                ChatFailure previous=reply.attemptFailure;
                dirty |= reply.accept(feed.next(180,"等待回复事件超时"));
                if (reply.attemptFailure!=null && reply.attemptFailure!=previous) app.log.attemptFailed(reply.attemptFailure);
                long now=android.os.SystemClock.elapsedRealtime();
                if (dirty && !reply.ended && (feed.messages.isEmpty() || now-saved>=70)) {
                    String body=reply.body();
                    if (!body.equals(visible)) {
                        app.history.update(row,body,"pending"); app.changed(); visible=body;
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
            try {
                if (failure==null && reply.completed) {
                    app.history.update(row,reply.body(),"done");
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
            app.changed();
            if (reply.active() && api==app.api) {
                try { api.rpc("session/cancel",Wire.request(Wire.obj("sessionId",session))); }
                catch (Exception ignored) { }
            }
            busy=false; app.changed();
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
