package com.sunwin.deepseekharness;

import okhttp3.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.sunwin.deepseekharness.Diagnostics.Event.*;

final class ChatEngine {
    private final HarnessApp app;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    volatile boolean busy;
    ChatEngine(HarnessApp app) { this.app=app; }
    boolean send(long chatId,String text,String photo) {
        Loopback api=app.api;
        String model=app.account.selected;
        if (busy || api==null || !app.account.stored || model.isEmpty() || (text.isEmpty() && photo==null)) return false;
        busy=true;
        long row=app.history.begin(chatId,text,photo);
        app.changed();
        io.execute(() -> run(api,chatId,row,model,text,photo));
        return true;
    }
    private void run(Loopback api,long chatId,long row,String model,String text,String photo) {
        Reply reply=new Reply();
        WebSocket socket=null;
        String session=null;
        boolean submitting=false;
        try {
            session=app.history.chat(chatId).remote;
            if (session==null) {
                session=api.rpc("session/create",Wire.request(Wire.obj("cwd","/home/dsh/chat","agentPreset","phone-chat"))).getString("sessionId");
                app.history.link(chatId,session);
            }
            api.rpc("session/selectModel",Wire.request(Wire.obj("sessionId",session,"provider","deepseek-account","model",model)));
            String jpeg=photo==null ? null : Base64.getEncoder().encodeToString(Files.readAllBytes(new File(app.getFilesDir(),photo).toPath()));
            JSONObject prompt=Wire.prompt(session,UUID.randomUUID().toString(),text,jpeg);
            Feed feed=new Feed(Wire.follow(session).toString());
            socket=api.follow(feed);
            if (!"snapshot".equals(feed.next(25).getString("type"))) throw new IOException("opening");
            submitting=true;
            if (!api.rpc("session/prompt",prompt).getBoolean("accepted")) throw new IOException("prompt");
            long saved=0;
            boolean dirty=false;
            String visible="";
            while (!reply.ended) {
                dirty |= reply.accept(feed.next(180));
                long now=android.os.SystemClock.elapsedRealtime();
                if (dirty && !reply.ended && (feed.messages.isEmpty() || now-saved>=70)) {
                    String body=reply.body();
                    if (!body.equals(visible)) {
                        app.history.update(row,body,"pending"); app.changed(); visible=body;
                    }
                    dirty=false; saved=now;
                }
            }
        } catch (Exception e) {
            app.log.add(STREAM_ERROR);
            if (submitting && api==app.api) {
                try { api.rpc("session/cancel",Wire.request(Wire.obj("sessionId",session))); }
                catch (Exception ignored) { }
            }
        } finally {
            if (socket!=null) socket.close(1000,"");
            app.history.update(row,reply.body(),reply.completed ? "done" : "interrupted");
            app.log.add(reply.completed ? CHAT_COMPLETE : CHAT_INTERRUPTED);
            busy=false; app.changed();
        }
    }
    private final class Feed extends WebSocketListener {
        private final BlockingQueue<String> messages=new LinkedBlockingQueue<>();
        private final String open;
        Feed(String open) { this.open=open; }
        @Override public void onOpen(WebSocket ws,Response response) {
            app.log.add(WS_OPEN,response.code()); ws.send(open);
        }
        @Override public void onMessage(WebSocket ws,String json) { messages.offer(json); }
        @Override public void onClosed(WebSocket ws,int code,String reason) { messages.offer(""); }
        @Override public void onClosing(WebSocket ws,int code,String reason) { ws.close(1000,""); messages.offer(""); }
        @Override public void onFailure(WebSocket ws,Throwable e,Response response) {
            if (response!=null) app.log.add(STREAM_ERROR,response.code());
            messages.offer("");
        }
        JSONObject next(int seconds) throws Exception {
            String raw=messages.poll(seconds,TimeUnit.SECONDS);
            if (raw==null || raw.isEmpty()) throw new IOException("stream-closed");
            JSONObject msg=new JSONObject(raw);
            if (!"item".equals(msg.optString("type")) || !"reply".equals(msg.optString("streamId"))) throw new IOException("stream-frame");
            return msg.getJSONObject("value");
        }
    }
}
