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
    private final List<Work> outbox=new CopyOnWriteArrayList<>();
    private long nextKey=-1;
    volatile Work latest;
    static final class Work {
        volatile History.Chat chat;
        String model;
        Loopback.Cancellation cancellation=new Loopback.Cancellation();
        volatile WebSocket socket;
        Loopback api;
        long localUser,localReply,answerUser;
        volatile long user,row;
        volatile boolean started,stopped,ended,preparing,newDraftSaved,unsaved;
        volatile String body="",error;
        volatile ContextUsage usage;
        String text="";
        List<Attachment> files=Collections.emptyList();
        DraftImporter.Batch batch;
        List<Attachment> attachments() {
            if (batch==null || batch.ready.isEmpty()) return files;
            List<Attachment> result=new ArrayList<>(files);
            Set<Long> ids=new HashSet<>();
            for (Attachment f:files) ids.add(f.id);
            for (Attachment f:batch.ready) if (ids.add(f.id)) result.add(f);
            return result;
        }
        Work(History.Chat chat,String model) { this.chat=chat; this.model=model; }
    }
    ChatEngine(HarnessApp app,String cwd) { this.app=app; this.cwd=cwd; }
    boolean busy() { return active!=null; }
    synchronized long delete(long id,long current) {
        History.Chat chat=app.history.chat(id);
        if (chat==null) return current;
        List<String> sessions=app.history.sessions(id);
        List<android.net.Uri> pending=app.history.importSources(id);
        long next=app.history.delete(id,current,app.log);
        app.imports.cancel(id);
        if (active!=null && active.chat.id==id) {
            if (active.socket!=null) active.socket.cancel();
            active=null;
        }
        for (Work item:outbox) if (item.chat.id==id) {
            if (item.batch!=null) item.batch.dispose(); outbox.remove(item);
        }
        if (latest!=null && latest.chat.id==id) latest=null;
        for (android.net.Uri uri:pending) app.imports.release(uri);
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
        if (active==work) { app.history.meterBranch(work.chat.branch,work.model,usage); work.usage=usage; }
    }
    private synchronized void savePartial(Work work,long row,String body) {
        if (active==work) app.history.update(row,body,"pending");
    }
    private synchronized WebSocket follow(Work work,Loopback api,Feed feed) throws ChatFailure {
        if (active!=work) return null;
        if (work.stopped) throw new ChatFailure("已停止");
        work.socket=api.follow(feed);
        return work.socket;
    }
    private synchronized void finish(Work work) {
        if (work.row!=0 && !work.unsaved) outbox.remove(work);
        if (active==work) active=null;
        app.changed();
    }
    Work stoppingTarget() {
        Work w=active; return w!=null && w.localReply!=0 && !w.ended ? w : null;
    }
    List<Work> outbox(long branch) {
        List<Work> list=new ArrayList<>();
        for (Work w:outbox) if (w.chat.branch==branch) list.add(w);
        return list;
    }
    void savingDraft(long branch) {
        for (Work w:outbox) if (w.chat.branch==branch) w.newDraftSaved=true;
    }
    synchronized Work reserve(History.Chat chat,String text,List<Attachment> files,DraftImporter.Batch batch,
                              long answerUser,boolean dice) {
        if (busy() || Attachment.photos(files)>Attachment.MAX_PHOTOS || Attachment.documents(files)>Attachment.MAX_DOCUMENTS ||
                (text.isEmpty() && files.isEmpty() && batch==null && !dice && answerUser==0)) return null;
        Work w=new Work(chat,app.account.selected);
        w.api=app.api; w.text=text; w.files=new ArrayList<>(files); w.batch=batch; w.answerUser=answerUser;
        if (batch!=null) batch.claimed=true;
        w.preparing=!files.isEmpty() || batch!=null;
        w.localUser=answerUser==0 ? nextKey-- : answerUser; w.localReply=nextKey--;
        outbox.add(w); latest=active=w;
        return w;
    }
    synchronized void start(Work w) {
        if (active!=w || w.started) return;
        w.started=true;
        io.execute(()->prepare(w));
    }
    private void prepare(Work w) {
        try {
            History.Message user;
            synchronized (this) {
                if (active!=w) return;
                synchronized (w.batch==null?w:w.batch) {
                    History h=app.history;
                    android.database.sqlite.SQLiteDatabase db=h.getWritableDatabase(); db.beginTransaction();
                    long row; History.Chat source;
                    try {
                        String title=w.batch==null ? "" : w.batch.document?"文件对话":"图片对话";
                        row=w.answerUser==0 ? h.begin(w.chat.id,w.chat.branch,w.text,w.attachments(),title)
                                : h.answerOnBranch(w.chat.id,w.chat.branch,w.answerUser);
                        user=h.at(w.chat.branch,h.last(w.chat.branch,row));
                        if (w.batch!=null) h.recordImports(user.id,w.batch);
                        if (!w.newDraftSaved) h.textDraft(w.chat.branch,"");
                        source=h.branchChat(w.chat.branch);
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                    w.chat=source; w.user=user.id; w.row=row;
                    if (w.batch!=null) { app.imports.attached(w.batch,user.id); app.imports.retain(w.batch); }
                }
            }
            app.changed();
            if (w.api==null || w.api!=app.api || !app.account.stored || w.model.isEmpty()) {
                String reason=w.api==null || w.api!=app.api ? "未连接" : !app.account.stored?"未登录":"模型未就绪";
                throw new ChatFailure(w.stopped?"已停止":reason);
            }
            run(w.api,w,w.row,user,app.history.promptText(w.chat.branch,user),w.answerUser!=0);
        } catch (Exception e) {
            w.error=w.batch!=null && !w.batch.remaining.isEmpty()?"附件读取失败":ChatFailure.caught(e,SAVE_REPLY).getMessage();
            synchronized (this) {
                if (active!=w) return;
                w.preparing=false; w.ended=true;
                try { if (w.row!=0) {
                    app.history.dirty(w.chat.branch); app.history.update(w.row,w.body,"interrupted",w.error);
                } }
                catch (android.database.sqlite.SQLiteException ex) { w.unsaved=true; }
                finally { finish(w); }
            }
        }
    }
    synchronized boolean retryLocal(Work w) {
        if (!ready() || !outbox.contains(w) || !w.ended) return false;
        w.api=app.api; w.model=app.account.selected; w.cancellation=new Loopback.Cancellation(); w.error=null; w.ended=false; w.stopped=false; w.started=false;
        w.preparing=w.batch!=null || !w.files.isEmpty();
        if (w.batch!=null) app.imports.retry(w.batch);
        active=latest=w; start(w); app.changed(); return true;
    }
    // Non-visual callers dispatch immediately. The activity dispatches from the first draw.
    boolean send(long chatId,String text,List<Attachment> files) {
        if (!ready()) return false;
        History.Chat chat=app.history.chat(chatId);
        Work w=reserve(chat,text,files,null,0,text.isEmpty() && !app.history.diceBefore(chat.branch,Long.MAX_VALUE).isEmpty());
        if (w==null) return false;
        start(w); return true;
    }
    synchronized boolean regenerate(long chat,long message) {
        return ready() && generate(chat,()->app.history.regenerate(chat,message));
    }
    synchronized boolean retry(long chat,long user,long message) {
        if (!ready()) return false;
        Work failed=null;
        for (Work w:outbox) if (w.row==message && w.unsaved) failed=w;
        boolean repair=failed!=null;
        boolean ok=generate(chat,()->app.history.retry(chat,user,message,repair));
        if (ok && repair) outbox.remove(failed);
        return ok;
    }
    synchronized boolean answerUser(long chat,long user) {
        return ready() && generate(chat,()->app.history.answerUser(chat,user));
    }
    private boolean ready() {
        return !busy() && app.api!=null && app.account.stored && !app.account.selected.isEmpty();
    }
    private boolean generate(long chatId,java.util.function.LongSupplier insert) {
        try {
            Work w; long row; History.Message user; String text;
            android.database.sqlite.SQLiteDatabase db=app.history.getWritableDatabase(); db.beginTransaction();
            try {
                row=insert.getAsLong();
                w=new Work(app.history.chat(chatId),app.account.selected);
                user=app.history.at(w.chat.branch,app.history.last(w.chat.branch,row));
                text=app.history.promptText(w.chat.branch,user);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            w.api=app.api; w.localReply=w.row=row; w.user=user.id; w.started=true;
            w.preparing=!user.attachments.isEmpty() || user.missing>0;
            latest=active=w; app.changed();
            io.execute(()->run(w.api,w,row,user,text,true)); return true;
        } catch (android.database.sqlite.SQLiteException e) {
            ChatFailure failure=ChatFailure.caught(e,SAVE_REPLY);
            app.log.interrupted(SAVE_REPLY,failure); app.notice(failure.getMessage()); return false;
        }
    }
    void interrupt() {
        Work w=stoppingTarget();
        if (w==null || w.stopped) return;
        w.stopped=true; w.cancellation.stop();
        WebSocket socket=w.socket;
        if (socket!=null) socket.cancel();
        app.streamChanged();
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
        boolean prompting=false;
        api.cancellation.set(work.cancellation);
        try {
            if (active!=work) return;
            stage=READ_ATTACHMENT;
            if (work.batch==null && user.missing>0) work.batch=app.imports.resume(work.chat.id,work.chat.branch,user.id);
            if (work.batch!=null) {
                work.preparing=true; app.streamChanged();
                while (!work.stopped && active==work && !work.batch.done.await(80,TimeUnit.MILLISECONDS)) { }
                if (active!=work) return;
                if (!work.stopped && !work.batch.remaining.isEmpty()) throw new ChatFailure("附件读取失败");
                user=app.history.at(work.chat.branch,user.id); files=user.attachments;
                draft=ContextUsage.estimate(text,files);
            }
            if (work.stopped) throw new ChatFailure("已停止");
            stage=CREATE_SESSION;
            session=session(api,work,user.id-1,fresh);
            stage=SELECT_MODEL;
            select(api,session,work.model);
            stage=CONTEXT;
            if (active!=work) return;
            prepared=ContextUsage.prepare(api,session,ContextUsage.argument(draft,files));
            draft+=prepared.fileBytes+(long)prepared.documentImages*ContextUsage.DOCUMENT_IMAGE_COST;
            meter(work,prepared);
            app.streamChanged();
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
                meter(work,opening); app.streamChanged();
            }
            stage=PROMPT;
            if (active!=work) return;
            prompting=true;
            JSONObject ack=api.rpc("session/prompt",prompt);
            work.preparing=false; app.streamChanged();
            if (ack==null || !ack.optBoolean("accepted")) throw new ChatFailure("DSH 未接受消息");
            stage=REPLY;
            long saved=0,published=0;
            boolean dirty=false;
            String visible="";
            while (!reply.ended) {
                ChatFailure previous=reply.attemptFailure;
                long previousCount=reply.contextTokens;
                dirty |= reply.accept(feed.next(180,"等待回复事件超时"));
                if (reply.contextTokens>=0 && reply.contextTokens!=previousCount) {
                    meter(work,new ContextUsage(reply.contextTokens,prepared.window,false));
                    app.streamChanged();
                }
                if (reply.attemptFailure!=null && reply.attemptFailure!=previous) app.log.attemptFailed(reply.attemptFailure);
                long now=android.os.SystemClock.elapsedRealtime();
                if (dirty && !reply.ended && (feed.messages.isEmpty() || now-published>=16)) {
                    String body=reply.body();
                    if (!body.equals(visible)) {
                        work.body=visible=body; app.streamChanged();
                        if (saved==0 || now-saved>=450) { savePartial(work,row,body); saved=now; }
                    }
                    dirty=false; published=now;
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
            api.cancellation.remove();
            if (socket!=null) socket.close(1000,"");
            if (work.stopped) failure=new ChatFailure("已停止");
            work.body=reply.body(); work.preparing=false;
            saveReply(work,row,user.id,session,reply,failure,stage,prepared,draft);
            work.ended=true; app.streamChanged();
            if (active==work && (reply.active() || work.stopped && prompting) && api==app.api) {
                final String cancelSession=session;
                cleanup.execute(()->{
                    try { api.rpc("session/cancel",Wire.request(Wire.obj("sessionId",cancelSession))); }
                    catch (Exception ignored) { }
                });
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
                    work.error=failure.getMessage();
                    app.log.interrupted(stage,failure);
                    app.history.dirty(work.chat.branch);
                    app.history.update(row,reply.body(),"interrupted",failure.getMessage());
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } catch (android.database.sqlite.SQLiteException e) {
            ChatFailure storage=ChatFailure.caught(e,SAVE_REPLY);
            work.unsaved=true; work.error=storage.getMessage();
            if (!outbox.contains(work)) outbox.add(work);
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
