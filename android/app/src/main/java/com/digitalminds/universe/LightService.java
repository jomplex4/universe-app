package com.digitalminds.universe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * UNIVERSE :: LightService
 * Keeps the strip connection, the light show and the routines alive while the app is in the
 * background. One small ongoing notification with a Light on / off button.
 * (Music and video have their own media notification, drawn by PlaybackService.)
 */
public class LightService extends Service {

    public interface Listener { void onAction(String action); }

    public static volatile boolean running = false;
    public static volatile Listener listener;

    static final String ACT_UPDATE = "universe.UPDATE";
    static final String ACT_POWER = "universe.POWER";

    private static final String CHANNEL = "universe_light";
    private PowerManager.WakeLock lock;
    private boolean lightOn = true;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACT_POWER.equals(a)) {
            Listener l = listener;
            if (l != null) l.onAction("power");
        } else if (intent != null) {
            lightOn = intent.getBooleanExtra("light", lightOn);
        }
        show();
        if (lock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "universe:light");
                lock.setReferenceCounted(false);
                lock.acquire(8 * 60 * 60 * 1000L);
            }
        }
        return START_NOT_STICKY;
    }

    private void show() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Light", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent toggle = new Intent(this, LightService.class).setAction(ACT_POWER);
        PendingIntent power = PendingIntent.getService(this, 14, toggle, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_universe)
            .setColor(0xFFE10600)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .setContentTitle("UNIVERSE")
            .setContentText(lightOn ? "Your light keeps running in the background" : "Light is off")
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_n_power), lightOn ? "Light off" : "Light on", power).build());
        Notification n = b.build();

        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(7, n);
        } catch (Exception e) {
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
