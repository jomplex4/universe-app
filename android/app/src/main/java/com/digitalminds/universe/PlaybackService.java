package com.digitalminds.universe;

import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.TeeAudioProcessor;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * UNIVERSE :: PlaybackService
 *
 * The one and only player (ExoPlayer, the same engine COMET uses) for songs and karaoke videos.
 * Being a MediaSessionService, Android draws the media notification, the lock screen player and
 * handles headset buttons by itself.
 *
 * It also listens to the audio that is about to be played (SpectrumTap) and publishes a
 * spectrum 20 times per second, which is what the light show follows.
 */
public class PlaybackService extends MediaSessionService {

    static final String ACTION_LOCAL_BIND = "com.digitalminds.universe.action.LOCAL_BIND";
    private static final long SPECTRUM_PERIOD_MS = 50;

    /** The running service, or null. Same process as the screens, so they can use it directly. */
    public static volatile PlaybackService instance;
    /** Receives the spectrum (on a background thread). */
    public static volatile SpectrumListener spectrumListener;

    public interface SpectrumListener {
        void onSpectrum(byte[] bins, int sampleRate);
    }

    public interface Ready {
        void run(PlaybackService service);
    }

    public final class LocalBinder extends Binder {
        PlaybackService service() {
            return PlaybackService.this;
        }
    }

    public ExoPlayer player;
    private MediaSession session;
    private final LocalBinder binder = new LocalBinder();
    private final SpectrumTap tap = new SpectrumTap();
    private final byte[] bins = new byte[SpectrumTap.BINS];
    private HandlerThread dspThread;
    private Handler dsp;
    private volatile boolean dspRunning;
    private boolean sessionIsAudio = true;

    // ---------------------------------------------------------------- finding the service

    private static final List<Ready> pending = new ArrayList<>();
    private static ServiceConnection connection;

    /** Runs r (on the main thread) as soon as the service exists, starting it if needed. */
    public static void with(final Context ctx, Ready r) {
        PlaybackService s = instance;
        if (s != null) {
            r.run(s);
            return;
        }
        synchronized (pending) {
            pending.add(r);
            if (connection != null) return;                 // already starting
            connection = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    PlaybackService svc = ((LocalBinder) service).service();
                    List<Ready> run;
                    synchronized (pending) {
                        run = new ArrayList<>(pending);
                        pending.clear();
                    }
                    for (Ready p : run) p.run(svc);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    synchronized (pending) {
                        connection = null;                  // next call binds again
                    }
                }
            };
        }
        Intent i = new Intent(ctx, PlaybackService.class).setAction(ACTION_LOCAL_BIND);
        ctx.getApplicationContext().bindService(i, connection, Context.BIND_AUTO_CREATE);
    }

    // ---------------------------------------------------------------- life cycle

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        player = buildPlayer();
        player.addListener(listener);

        session = new MediaSession.Builder(this, player)
                .setSessionActivity(activityIntent(true))
                .build();
        addSession(session);

        dspThread = new HandlerThread("universe-spectrum");
        dspThread.start();
        dsp = new Handler(dspThread.getLooper());
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        if (intent != null && ACTION_LOCAL_BIND.equals(intent.getAction())) return binder;
        return super.onBind(intent);
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        if (!player.getPlayWhenReady() || player.getMediaItemCount() == 0) stopSelf();
    }

    @Override
    public void onDestroy() {
        dspRunning = false;
        if (dsp != null) dsp.removeCallbacksAndMessages(null);
        if (dspThread != null) dspThread.quitSafely();
        instance = null;
        if (session != null) {
            session.release();
            session = null;
        }
        player.release();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- player

    /** Hands the audio to the tap on its way to the speaker. */
    private final class TapRenderers extends DefaultRenderersFactory {
        TapRenderers(Context context) {
            super(context);
        }

        @Override
        protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioTrackPlaybackParams) {
            TeeAudioProcessor.AudioBufferSink sink = new TeeAudioProcessor.AudioBufferSink() {
                @Override
                public void flush(int sampleRateHz, int channelCount, int encoding) {
                    tap.configure(sampleRateHz, channelCount, encoding);
                }

                @Override
                public void handleBuffer(ByteBuffer buffer) {
                    tap.write(buffer);
                }
            };
            return new DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(new AudioProcessor[] {new TeeAudioProcessor(sink)})
                    .build();
        }
    }

    private ExoPlayer buildPlayer() {
        // Phone chip decoders first; if one fails the next one is tried automatically.
        MediaCodecSelector selector = (mimeType, requiresSecure, requiresTunneling) -> {
            List<MediaCodecInfo> infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecure, requiresTunneling);
            if (!MimeTypes.isVideo(mimeType)) return infos;
            List<MediaCodecInfo> sorted = new ArrayList<>(infos);
            Collections.sort(sorted, (a, b) -> Integer.compare(a.hardwareAccelerated ? 0 : 1, b.hardwareAccelerated ? 0 : 1));
            return sorted;
        };
        DefaultRenderersFactory renderers = new TapRenderers(this)
                .setMediaCodecSelector(selector)
                .setEnableDecoderFallback(true)
                .setEnableAudioFloatOutput(true);   // 24 and 32 bit files keep their depth
        DefaultExtractorsFactory extractors = new DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true);
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();
        ExoPlayer p = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this, extractors))
                .setAudioAttributes(attrs, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_LOCAL)
                .setSeekBackIncrementMs(10_000)
                .setSeekForwardIncrementMs(10_000)
                .build();
        p.setRepeatMode(Player.REPEAT_MODE_ALL);     // a party keeps going: the list wraps around
        return p;
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updateSpectrumLoop();
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            updateSessionActivity(mediaItem);
        }

        @Override
        public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
            refreshNotification();
        }
    };

    /** Starts a queue (the current folder or list) at index. */
    public void playQueue(List<MediaItem> items, int index, boolean video) {
        if (items.isEmpty()) return;
        int i = Math.max(0, Math.min(index, items.size() - 1));
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(video ? C.AUDIO_CONTENT_TYPE_MOVIE : C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(), true);
        setVideoEnabled(true);
        updateSessionActivity(items.get(i));
        player.setMediaItems(items, i, 0L);
        player.prepare();
        player.play();
    }

    public void stopAndClear() {
        player.stop();
        player.clearMediaItems();
    }

    /** Video off = it keeps going as audio only (saves battery when the screen is away). */
    public void setVideoEnabled(boolean enabled) {
        TrackSelectionParameters cur = player.getTrackSelectionParameters();
        if (cur.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == !enabled) return;
        player.setTrackSelectionParameters(cur.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build());
    }

    public static boolean isVideo(@Nullable MediaItem item) {
        if (item == null) return false;
        Integer type = item.mediaMetadata.mediaType;
        return type != null && type == MediaMetadata.MEDIA_TYPE_VIDEO;
    }

    // ---------------------------------------------------------------- notification

    /**
     * The notification is for music, and for a video that keeps going as audio. A video you are
     * watching on screen needs none (the screen itself is the player).
     */
    private boolean wantsNotification() {
        MediaItem item = player.getCurrentMediaItem();
        if (item == null || !isVideo(item)) return true;
        return player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO);
    }

    @Override
    public void onUpdateNotification(MediaSession session, boolean startInForegroundRequired) {
        if (wantsNotification()) {
            super.onUpdateNotification(session, startInForegroundRequired);
        } else {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
            NotificationManagerCompat.from(this).cancel(DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID);
        }
    }

    private void refreshNotification() {
        MediaSession s = session;
        if (s == null) return;
        int state = player.getPlaybackState();
        boolean running = player.getPlayWhenReady() && (state == Player.STATE_READY || state == Player.STATE_BUFFERING);
        try {
            onUpdateNotification(s, running);
        } catch (Exception e) {
            // The system may refuse to start a foreground service right now; playback is not affected.
        }
    }

    /** Tapping the notification opens the right screen: the video player or the app. */
    private PendingIntent activityIntent(boolean audio) {
        Class<?> target = audio ? MainActivity.class : PlayerActivity.class;
        Intent open = new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, audio ? 1 : 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void updateSessionActivity(@Nullable MediaItem item) {
        boolean audio = !isVideo(item);
        if (sessionIsAudio == audio || session == null) return;
        sessionIsAudio = audio;
        session.setSessionActivity(activityIntent(audio));
    }

    // ---------------------------------------------------------------- spectrum for the lights

    private final Runnable spectrumTick = new Runnable() {
        @Override
        public void run() {
            if (!dspRunning) return;
            if (tap.compute(bins)) {
                SpectrumListener l = spectrumListener;
                if (l != null) l.onSpectrum(bins.clone(), tap.rate());
            }
            if (dsp != null) dsp.postDelayed(this, SPECTRUM_PERIOD_MS);
        }
    };

    private void updateSpectrumLoop() {
        if (dsp == null) return;
        if (player.isPlaying() && !dspRunning) {
            dspRunning = true;
            dsp.post(spectrumTick);
        } else if (!player.isPlaying() && dspRunning) {
            dspRunning = false;
            dsp.removeCallbacks(spectrumTick);
        }
    }
}
