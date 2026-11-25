package com.sunwin.deepseekharness;

import android.app.*;
import android.content.*;
import android.os.*;
import android.system.*;
import android.view.*;
import android.widget.TextView;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.lang.Process;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.*;
import okhttp3.*;

/** Separate instrumentation APK for an empty emulator. No test account or test entry point in the release. */
public final class DeviceSmoke extends Instrumentation {
    private Bundle options;
    private MainActivity activity;
    private HarnessApp app;
    private String stage="launch";
    @Override public void onCreate(Bundle args) { super.onCreate(args); options=args; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        // Instrumentation.start() can run this thread before Application.onCreate
        // finishes removing files from interrupted imports in the previous process.
        waitForIdleSync();
        try {
            if (!"ranchu".equals(Build.HARDWARE) && !Build.HARDWARE.contains("goldfish")) throw new AssertionError();
            String mode=options.getString("scenario","history");
            if ("attachment-seed-v4".equals(mode) || "attachment-seed-v5".equals(mode) || "adventure-seed-v6".equals(mode) || "send-seed-v7".equals(mode) || "web-seed-v8".equals(mode)) {
                stage=mode; seedOldPhoto(mode.endsWith("v4") ? 4 : mode.endsWith("v5") ? 5 : mode.endsWith("v6") ? 6 : mode.endsWith("v7") ? 7 : 8);
                result.putString("passed",mode); finish(Activity.RESULT_OK,result); return;
            }
            if ("attachment-pdf".equals(mode)) {
                stage="native-pdf"; DeviceAttachments.pdfs(this,filename->stage="format-"+filename);
                result.putString("passed",mode); finish(Activity.RESULT_OK,result); return;
            }
            if ("self-check".equals(mode)) {
                checkBindings(result);
                result.putString("passed",mode); finish(Activity.RESULT_OK,result); return;
            }
            if ("session".equals(mode) || "context-guest".equals(mode)) {
                app=(HarnessApp)getTargetContext().getApplicationContext();
                app.startForegroundService(new Intent(app,HarnessService.class));
                if ("session".equals(mode)) materializeSession(result);
                else {
                    Loopback api=ready();
                    stage="guest-context-preset";
                    String id=api.rpc("session/create",Wire.request(Wire.obj("cwd",Guest.CHAT_DIR,"agentPreset","phone-chat")))
                            .getString("sessionId");
                    // An empty account has no selectable route. Exercise the installed
                    // preset handler through its argument check, before resolving a model.
                    JSONObject command=api.rpc("commands/execute",Wire.obj("agentId",id,"line","/phone-context probe",
                            "submittedAttachments",new JSONArray())).getJSONObject("result");
                    if (!"error".equals(command.optString("kind")) || !"参数错误".equals(command.optString("text")))
                        throw new AssertionError();
                }
                result.putString("passed",mode); finish(Activity.RESULT_OK,result); return;
            }
            if (getTargetContext().getPackageManager().getComponentEnabledSetting(new ComponentName(
                    getTargetContext(),HarnessService.class))==android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                // The emulator can run the UI in isolation with the guest service disabled via pm.
                ((HarnessApp)getTargetContext().getApplicationContext()).serviceState="stopped";
            }
            activity=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            app=(HarnessApp)activity.getApplication();
            if ("context".equals(mode) || "attachments".equals(mode) || "adventure-ui".equals(mode) || "send-bench".equals(mode) || "send-ui".equals(mode) || "web-ui".equals(mode)) {
                DeviceContext test=new DeviceContext(this,activity);
                try { test.run(Integer.parseInt(options.getString("fixturePort")),mode,result); }
                finally { stage=test.stage; }
            } else if ("attachments-reopen".equals(mode) || "attachment-upgrade".equals(mode)) {
                DeviceAttachments test=new DeviceAttachments(this,activity);
                try { test.reopen("attachment-upgrade".equals(mode)); } finally { stage=test.stage; }
            } else if ("delete".equals(mode)) {
                DeviceDeletion test=new DeviceDeletion(this,activity);
                try { test.run(); } finally { stage=test.stage; }
            } else if ("login-init".equals(mode)) loginInit();
            else if ("runtime".equals(mode)) stopRuntime();
            else history("reopen".equals(mode));
            result.putString("passed",mode); finish(Activity.RESULT_OK,result);
        } catch (Throwable e) {
            result.putString("failed",stage);
            // Throwable messages may contain a URL. Only export the local stage and exception type.
            result.putString("kind",e.getClass().getSimpleName());
            for (StackTraceElement frame:e.getStackTrace()) {
                if (frame.getClassName().startsWith("com.sunwin.deepseekharness.")) {
                    result.putString("where",frame.getFileName()+" "+frame.getLineNumber()); break;
                }
            }
            if (e instanceof ChatFailure) result.putString("detail",((ChatFailure)e).diagnostic());
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    // Seed a published 0.1.4 or 0.1.5 target through its own database implementation.
    private void seedOldPhoto(int version) throws IOException {
        Context target=getTargetContext();
        try (History history=new History(target)) {
            android.database.sqlite.SQLiteDatabase db=history.getWritableDatabase();
            if (db.getVersion()!=version) throw new AssertionError();
            String path="photos/upgrade-"+UUID.randomUUID()+".jpg";
            File photo=new File(target.getFilesDir(),path);
            Files.createDirectories(photo.toPath().getParent());
            try (InputStream in=getContext().getAssets().open("photo.jpg")) { Files.copy(in,photo.toPath()); }
            if (version>=5) {
                long id=history.create();
                long row=history.begin(id,"升级前照片",Collections.singletonList(Attachment.photo(path)));
                history.update(row,"升级前回复","done");
                if (version>=6) {
                    history.link(id,"fixture-upgrade-v"+version);
                    history.meter(id,"deepseek-flash",new ContextUsage(66,1048576,false));
                    target.getSharedPreferences("ui",0).edit().putLong("adventure-upgrade",id).commit();
                }
            } else {
                db.beginTransaction();
                try {
                    db.execSQL("INSERT INTO chat(title,updated) VALUES('升级前照片',0)");
                    db.execSQL("INSERT INTO message(chat,role,body,photo,state) VALUES(last_insert_rowid(),'user','升级前照片',?,'done')",new Object[]{path});
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
        }
        // Keep system permission dialogs out of this empty emulator's interaction test.
        target.getSharedPreferences("ui",0).edit().putBoolean("notificationAsked",true).putBoolean("batteryAsked",true).commit();
    }
    private void checkBindings(Bundle result) throws Exception {
        Context base=getTargetContext();
        Path isolated=Files.createTempDirectory(base.getCacheDir().toPath(),"device-smoke-");
        Context env=new ContextWrapper(base) {
            @Override public File getNoBackupFilesDir() { return isolated.toFile(); }
        };
        try {
            stage="app-data-exec";
            Path installed=Paths.get(base.getApplicationInfo().nativeLibraryDir);
            Path copy=Files.copy(installed.resolve("libguest_busybox.so"),isolated.resolve("busybox"));
            if (!copy.toFile().setExecutable(true,true)) throw new AssertionError();
            try {
                Process p=new ProcessBuilder(copy.toString(),"true").start();
                p.waitFor(); throw new AssertionError("app data exec allowed");
            } catch (IOException e) {
                AccessFault denial=AccessFault.caught(e);
                if (denial==null || !"EACCES".equals(denial.errno)) throw e;
                result.putString("app-data-execve","EACCES");
            }
            Path addon=Files.copy(installed.resolve("libdsh_flock.so"),isolated.resolve("system.node"));
            FileDescriptor fd=Os.open(addon.toString(),OsConstants.O_RDONLY,0);
            try {
                long page=Os.mmap(0,1,OsConstants.PROT_READ|OsConstants.PROT_EXEC,OsConstants.MAP_PRIVATE,fd,0);
                Os.munmap(page,1);
                result.putString("app-data-exec-mmap","allowed");
            } catch (ErrnoException e) { result.putString("app-data-exec-mmap",OsConstants.errnoName(e.errno)); }
            finally { Os.close(fd); }
            Guest guest=new Guest(env);
            stage="bound-exec";
            ProcessBuilder pb=guest.prepare();
            List<String> args=pb.command();
            int tail=args.indexOf("--")+1;
            args.subList(tail,args.size()).clear();
            args.add("/bin/busybox"); args.add("true");
            checkProcess(pb,null);
            stage="legacy-session-link";
            int linkOption=args.indexOf("--link2symlink");
            args.remove(linkOption);
            checkProcess(pb,"EACCES");
            result.putString("legacy-linkat","EACCES");
            args.add(linkOption,"--link2symlink");
            stage="unbound-executable";
            // Leave a valid +x ELF behind after omitting only the rg bind. The
            // production checker must reject its inode before attempting mmap.
            int rg=0;
            while (!args.get(rg).startsWith(installed+"/libdsh_rg.so:")) rg++;
            String target=args.get(rg).substring(args.get(rg).indexOf(':')+1).replace("!","");
            args.subList(rg-1,rg+1).clear();
            Path misplaced=Files.copy(copy,isolated.resolve("linux"+target));
            if (!misplaced.toFile().setExecutable(true,true)) throw new AssertionError();
            checkProcess(pb,"BIND_MISMATCH");
            result.putString("missing-bind","BIND_MISMATCH");
        } finally {
            try (java.util.stream.Stream<Path> files=Files.walk(isolated)) {
                for (Path path:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
    private void checkProcess(ProcessBuilder pb,String expectedCode) throws Exception {
        Process p=pb.start();
        boolean expected=false;
        try (BufferedReader lines=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))) {
            for (String line;(line=lines.readLine())!=null;) {
                if ("DSH_CHECK_OK".equals(line)) expected=expectedCode==null;
                else if (line.startsWith("DSH_CHECK\t")) {
                    ChatFailure failure=((HarnessApp)getTargetContext().getApplicationContext()).log.childLine(line);
                    expected=failure!=null && failure.code.equals(expectedCode) &&
                            (("BIND_MISMATCH".equals(expectedCode) && failure.access.path.endsWith("-rg")) ||
                            ("EACCES".equals(expectedCode) && "linkat".equals(failure.access.syscall)
                                    && failure.getMessage().startsWith("EACCES · 建链接 ")
                                    && failure.diagnostic().contains("process=GUEST_CHECK syscall=linkat")
                                    && failure.diagnostic().contains("errno=EACCES(13)")));
                    if (!expected && failure!=null) throw failure;
                }
            }
            if (p.waitFor()!=(expectedCode==null?0:125) || !expected) throw new AssertionError();
        } finally { p.getOutputStream().close(); }
    }
    private View find(View v,String name) {
        if (name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())) return v;
        if (v instanceof TextView && name.contentEquals(((TextView)v).getText())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)v;
            for (int i=0;i<group.getChildCount();i++) { View hit=find(group.getChildAt(i),name); if (hit!=null) return hit; }
        }
        return null;
    }
    private void control(String name,boolean press) {
        stage=name;
        AtomicReference<Boolean> found=new AtomicReference<>(false);
        runOnMainSync(()->{
            View v=find(activity.getWindow().getDecorView(),name);
            if (v!=null && v.isShown()) {
                while (press && !v.isClickable() && v.getParent() instanceof View) v=(View)v.getParent();
                found.set(!press || v.performClick());
            }
        });
        if (!found.get()) throw new AssertionError();
    }
    private void history(boolean reopen) {
        final String title="设备回放对话", answer="这条记录已保存在手机上。";
        if (!reopen) {
            control("设置",true); control("导出诊断日志",false); control("充值",false);
            control("返回",true); control("历史对话",true); control("新建对话",true);
            long id=activity.getSharedPreferences("ui",0).getLong("chat",0);
            long reply=app.history.begin(id,title,Collections.emptyList()); app.history.update(reply,answer,"done");
            runOnMainSync(app::changed); waitForIdleSync();
        }
        control("历史对话",true); control(title,true); control(answer,false); control("相册",false); control("拍照",false);
        control("发送",false);
    }
    private Loopback ready() throws Exception {
        stage="runtime-ready";
        long until=SystemClock.elapsedRealtime()+320000;
        while (app.api==null && SystemClock.elapsedRealtime()<until && !"failed".equals(app.serviceState)) Thread.sleep(250);
        Loopback api=app.api;
        if (api==null) throw new AssertionError();
        stage="signed-out";
        if (!"signed-out".equals(api.rpc("account/getState",Wire.obj()).getString("status"))) throw new AssertionError();
        return api;
    }
    private void materializeSession(Bundle result) throws Exception {
        Loopback api=ready();
        stage="create-session";
        String id=api.rpc("session/create",Wire.request(Wire.obj("cwd",Guest.CHAT_DIR,"agentPreset","phone-chat")))
                .getString("sessionId");
        stage="first-session-write";
        api.rpc("session/rename",Wire.request(Wire.obj("sessionId",id,"title","设备自检")));
        String request=UUID.randomUUID().toString();
        BlockingQueue<String> frames=new LinkedBlockingQueue<>();
        WebSocket socket=api.follow(new WebSocketListener() {
            @Override public void onOpen(WebSocket ws,Response response) { ws.send(Wire.follow(id).toString()); }
            @Override public void onMessage(WebSocket ws,String text) { frames.offer(text); }
        });
        try {
            stage="session-snapshot";
            String snapshot=frames.poll(25,TimeUnit.SECONDS);
            if (snapshot==null || !"snapshot".equals(Wire.followItem(new JSONObject(snapshot)).optString("type"))) throw new AssertionError();
            Path session=app.getNoBackupFilesDir().toPath().resolve("linux/home/dsh/.dsh/sessions/--home-dsh-chat--").resolve(id);
            long baseline=journalBytes(session);
            stage="first-prompt";
            api.rpc("session/prompt",Wire.prompt(app,id,request,"你好",Collections.emptyList(),0));
            stage="session-journal";
            Reply reply=new Reply(request);
            long until=SystemClock.elapsedRealtime()+90000;
            do {
                String next=frames.poll(250,TimeUnit.MILLISECONDS);
                if (next!=null) reply.accept(Wire.followItem(new JSONObject(next)));
                if (reply.failure!=null && reply.failure.access!=null) throw reply.failure;
                // The first compressed frame can hold only a header. Wait for the
                // turn to end and for a later append before stopping the service.
                long bytes=journalBytes(session);
                if (baseline==0) baseline=bytes;
                if (reply.ended && bytes>baseline) {
                    result.putString("journal","appended");
                    return;
                }
            } while (SystemClock.elapsedRealtime()<until);
            if (reply.failure!=null) throw reply.failure;
            throw new AssertionError();
        } finally { socket.close(1000,""); }
    }
    private static long journalBytes(Path session) throws IOException {
        if (Files.isDirectory(session)) try (DirectoryStream<Path> files=Files.newDirectoryStream(session)) {
            for (Path file:files) {
                if (file.getFileName().toString().matches("session(?:\\.v[0-9]+)?\\.jsonl(?:\\.zstd)?")
                        && Files.isRegularFile(file)) return Files.size(file);
            }
        }
        return 0;
    }
    private void stopRuntime() throws Exception {
        Loopback api=ready();
        stage="service-stop";
        activity.stopService(new Intent(activity,HarnessService.class));
        long until=SystemClock.elapsedRealtime()+20000;
        while (SystemClock.elapsedRealtime()<until) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); app.log.export(out);
            String log=out.toString("UTF-8");
            if (app.api==null && log.lastIndexOf("PROOT_DSH_EXIT")>log.lastIndexOf("RUNTIME_READY")) {
                try { callback(api); } catch (java.net.ConnectException expected) { return; }
            }
            Thread.sleep(150);
        }
        throw new AssertionError();
    }
    private void loginInit() throws Exception {
        Loopback api=ready();
        long until;
        stage="auth-init";
        JSONObject v=api.rpc("account/startSignIn",Wire.obj("client",Wire.client(),"callbackOrigin",api.origin,"loginSource","web"));
        String attempt=v.getJSONObject("attempt").getString("id");
        try {
            until=SystemClock.elapsedRealtime()+60000;
            while ("initializing".equals(v.getJSONObject("attempt").optString("phase")) && SystemClock.elapsedRealtime()<until) {
                Thread.sleep(300); v=api.rpc("account/getState",Wire.obj());
            }
            stage="official-authorize-url";
            if (Wire.authorization(v.getJSONObject("attempt").optString("authorizeUrl"))==null) throw new AssertionError();
            stage="empty-callback";
            if (callback(api)!=400) throw new AssertionError();
        } finally { api.rpc("account/cancelSignIn",Wire.obj("attemptId",attempt)); }
        stage="cancelled";
        if (callback(api)!=404) throw new AssertionError();
        ByteArrayOutputStream out=new ByteArrayOutputStream(); app.log.export(out);
        if (!out.toString("UTF-8").contains("RUNTIME_READY")) throw new AssertionError();
    }
    private int callback(Loopback api) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(api.origin+"/oauth/callback").openConnection(Proxy.NO_PROXY);
        c.setInstanceFollowRedirects(false);
        try { return c.getResponseCode(); } finally { c.disconnect(); }
    }
}
