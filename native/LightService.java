package com.digitalminds.universe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * UNIVERSE :: LightService
 * One ongoing notification while the strip is connected or media is playing.
 * Keeps lights, music, video audio and routines alive in the background, and
 * offers media controls (also on the lock screen and headset buttons).
 */
public class LightService extends Service {

    public interface Listener { void onAction(String action); }

    public static volatile boolean running = false;
    public static volatile Listener listener;

    static final String ACT_UPDATE = "universe.UPDATE";
    static final String ACT_PREV = "universe.PREV";
    static final String ACT_TOGGLE = "universe.TOGGLE";
    static final String ACT_NEXT = "universe.NEXT";
    static final String ACT_POWER = "universe.POWER";

    private static final String CHANNEL = "universe_light";
    private PowerManager.WakeLock lock;
    private MediaSession session;

    private boolean bt, hasMedia, playing, lightOn = true;
    private String title = "", artist = "";
    private long position = 0, duration = 0;
    private Bitmap art;

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        session = new MediaSession(this, "universe");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { send("toggle"); }
            @Override public void onPause() { send("toggle"); }
            @Override public void onSkipToNext() { send("next"); }
            @Override public void onSkipToPrevious() { send("prev"); }
            @Override public void onSeekTo(long pos) { send("seek:" + pos); }
        });
        // Local playback on the normal media stream: the volume keys and the output switcher in the notification act on it.
        session.setPlaybackToLocal(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        try { art = BitmapFactory.decodeResource(getResources(), R.drawable.media_art); } catch (Exception ignored) { }
    }

    private static void send(String a) {
        Listener l = listener;
        if (l != null) l.onAction(a);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACT_PREV.equals(a)) send("prev");
        else if (ACT_TOGGLE.equals(a)) send("toggle");
        else if (ACT_NEXT.equals(a)) send("next");
        else if (ACT_POWER.equals(a)) send("power");
        else if (intent != null) {
            bt = intent.getBooleanExtra("bt", bt);
            hasMedia = intent.getBooleanExtra("media", hasMedia);
            playing = intent.getBooleanExtra("playing", playing);
            lightOn = intent.getBooleanExtra("light", lightOn);
            String t = intent.getStringExtra("title"); if (t != null) title = t;
            String ar = intent.getStringExtra("artist"); if (ar != null) artist = ar;
            position = intent.getLongExtra("position", position);
            duration = intent.getLongExtra("duration", duration);
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

    private PendingIntent action(String act, int code) {
        Intent i = new Intent(this, LightService.class).setAction(act);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification.Action btn(int icon, String label, String act, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, action(act, code)).build();
    }

    private void show() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Playback and light", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            nm.createNotificationChannel(c);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(R.drawable.ic_stat_universe)
            .setColor(0xFFE10600)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(pi)
            .setVisibility(Notification.VISIBILITY_PUBLIC);

        int lightIcon = R.drawable.ic_n_power;
        String lightLabel = lightOn ? "Light off" : "Light on";
        if (hasMedia) {
            b.setContentTitle(title.isEmpty() ? "UNIVERSE" : title)
                .setContentText(artist.isEmpty() ? (bt ? "Your light follows the music" : "UNIVERSE") : artist)
                .addAction(btn(R.drawable.ic_n_prev, "Previous", ACT_PREV, 11))
                .addAction(btn(playing ? R.drawable.ic_n_pause : R.drawable.ic_n_play, playing ? "Pause" : "Play", ACT_TOGGLE, 12))
                .addAction(btn(R.drawable.ic_n_next, "Next", ACT_NEXT, 13));
            if (bt) b.addAction(btn(lightIcon, lightLabel, ACT_POWER, 14));
            session.setActive(true);
            MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration);
            if (art != null) { md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art); b.setLargeIcon(art); }
            session.setMetadata(md.build());
            session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, position, playing ? 1f : 0f, SystemClock.elapsedRealtime())
                .build());
            b.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        } else {
            session.setActive(false);
            b.setContentTitle("UNIVERSE")
                .setContentText(lightOn ? "Your light keeps running in the background" : "Light is off")
                .addAction(btn(lightIcon, lightLabel, ACT_POWER, 14));
        }
        Notification n = b.build();

        int type = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            if (bt) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (hasMedia || !bt) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, type);
            else startForeground(7, n);
        } catch (Exception e) {
            try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                else startForeground(7, n);
            } catch (Exception ignored) { stopSelf(); }
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (lock != null && lock.isHeld()) lock.release();
        lock = null;
        if (session != null) { session.setActive(false); session.release(); session = null; }
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
