package com.sunwin.deepseekharness;

import android.app.*;
import android.content.*;
import android.net.*;
import android.os.IBinder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.regex.*;
import static com.sunwin.deepseekharness.Diagnostics.Event.*;

public final class HarnessService extends Service {
    private static final Pattern LAUNCH=Pattern.compile("^dsh web: http://127\\.0\\.0\\.1:(\\d{1,5})(/\\?token=[A-Za-z0-9._~%+=/-]+)$");
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    private final Object lock=new Object();
    private HarnessApp app;
    private Guest guest;
    private Process child;
    private boolean stopped;
    private volatile boolean failed;
    private ConnectivityManager networks;
    private final ConnectivityManager.NetworkCallback dnsChanges=new ConnectivityManager.NetworkCallback() {
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties link) {
            try { guest.updateDns(); } catch (IOException e) { app.log.add(NETWORK_ERROR); }
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        app=(HarnessApp)getApplication(); guest=new Guest(this);
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("runtime","对话服务",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,HarnessService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        startForeground(7,new Notification.Builder(this,"runtime").setSmallIcon(R.drawable.notification)
                .setContentTitle("DeepSeek 运行中").setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null,"停止",stop).build()).build());
        networks=getSystemService(ConnectivityManager.class);
        networks.registerDefaultNetworkCallback(dnsChanges);
        app.serviceState="starting"; app.log.add(SERVICE_START); app.changed();
        new Thread(this::runGuest,"dsh-process").start();
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if (intent!=null && "stop".equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }
    private void runGuest() {
        Process p=null;
        Loopback api=null;
        try {
            synchronized (lock) {
                if (stopped) return;
                app.log.add(RUNTIME_START);
                child=p=guest.start();
            }
            Process current=p;
            ScheduledFuture<?> deadline=timer.schedule(() -> {
                app.log.add(RUNTIME_TIMEOUT); terminate(current);
            },300,TimeUnit.SECONDS);
            // The child prints its one-use startup URL. Never forward either pipe or any exception message.
            try (BufferedReader lines=new BufferedReader(new InputStreamReader(p.getInputStream(),StandardCharsets.UTF_8))) {
                for (String line; (line=lines.readLine())!=null;) {
                    Matcher match=LAUNCH.matcher(line);
                    if (api==null && match.matches()) {
                        api=new Loopback(Integer.parseInt(match.group(1)),app.log);
                        api.bootstrap(match.group(2)); deadline.cancel(false);
                        synchronized (lock) {
                            if (stopped) break;
                            app.log.add(RUNTIME_READY); app.connected(api);
                        }
                    } else app.log.childLine(line);
                }
            } finally { deadline.cancel(false); }
        } catch (Exception e) { app.log.add(RUNTIME_ERROR); }
        finally {
            if (p!=null) {
                if (p.isAlive()) terminate(p);
                try { app.log.add(PROOT_DSH_EXIT,p.waitFor()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            synchronized (lock) {
                if (!stopped) {
                    failed=true;
                    app.main.post(this::stopSelf);
                }
            }
            if (api!=null) api.close();
        }
    }
    private static void requestStop(Process p) {
        try { p.getOutputStream().close(); } catch (IOException ignored) { }
    }
    private static void terminate(Process p) {
        requestStop(p);
        try { if (!p.waitFor(8,TimeUnit.SECONDS)) p.destroyForcibly(); }
        catch (InterruptedException e) { p.destroyForcibly(); Thread.currentThread().interrupt(); }
    }
    @Override public void onDestroy() {
        synchronized (lock) { stopped=true; if (child!=null) requestStop(child); }
        networks.unregisterNetworkCallback(dnsChanges);
        timer.shutdownNow(); app.disconnected(failed); app.log.add(SERVICE_STOP);
        stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
