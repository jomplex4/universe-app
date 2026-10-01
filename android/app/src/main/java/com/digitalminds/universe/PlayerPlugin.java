package com.digitalminds.universe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * UNIVERSE :: Player
 *
 * The web screens talk to the native player through this plugin.
 *
 * Methods: play({items, index, kind}), toggle, next, prev, seek({ms}), openVideo, stop, getState
 * Events:  "state"    {playing, index, count, kind, path, title, artist, position, duration}
 *          "spectrum" {d: base64 bytes, sr: sample rate}   (about 20 per second while playing)
 *          "error"    {message}
 */
@CapacitorPlugin(name = "Player")
public class PlayerPlugin extends Plugin {

    private final Handler main = new Handler(Looper.getMainLooper());
    private Player attachedTo;

    @Override
    public void load() {
        PlaybackService.spectrumListener = (bins, rate) -> {
            JSObject o = new JSObject();
            o.put("d", Base64.encodeToString(bins, Base64.NO_WRAP));
            o.put("sr", rate);
            notifyListeners("spectrum", o);
        };
    }

    @Override
    protected void handleOnDestroy() {
        PlaybackService.spectrumListener = null;
        main.removeCallbacksAndMessages(null);
    }

    // ---------------------------------------------------------------- methods

    @PluginMethod
    public void play(final PluginCall call) {
        final JSArray arr = call.getArray("items");
        final int index = call.getInt("index", 0);
        final boolean video = "video".equals(call.getString("kind", "audio"));
        final List<MediaItem> items = new ArrayList<>();
        if (arr != null) {
            Uri art = Uri.parse("android.resource://" + getContext().getPackageName() + "/" + R.drawable.media_art);
            for (int i = 0; i < arr.length(); i++) {
                try {
                    JSONObject o = arr.getJSONObject(i);
                    String uri = o.optString("uri", "");
                    if (uri.isEmpty()) continue;
                    items.add(new MediaItem.Builder()
                            .setUri(Uri.parse(uri))
                            .setMediaId(o.optString("path", uri))
                            .setMediaMetadata(new MediaMetadata.Builder()
                                    .setTitle(o.optString("title", ""))
                                    .setArtist(o.optString("artist", ""))
                                    .setArtworkUri(art)
                                    .setMediaType(video ? MediaMetadata.MEDIA_TYPE_VIDEO : MediaMetadata.MEDIA_TYPE_MUSIC)
                                    .build())
                            .build());
                } catch (Exception ignored) {
                    // skip a malformed entry
                }
            }
        }
        if (items.isEmpty()) {
            call.reject("EMPTY_QUEUE");
            return;
        }
        main.post(() -> PlaybackService.with(getContext(), svc -> {
            attach(svc);
            svc.playQueue(items, index, video);
            if (video) launchPlayer();
            emitState();
            call.resolve();
        }));
    }

    @PluginMethod
    public void toggle(final PluginCall call) {
        main.post(() -> {
            PlaybackService svc = PlaybackService.instance;
            if (svc != null) {
                ExoPlayer p = svc.player;
                if (p.getPlaybackState() == Player.STATE_IDLE) p.prepare();
                if (p.getPlaybackState() == Player.STATE_ENDED) p.seekToDefaultPosition();
                if (p.getPlayWhenReady()) p.pause();
                else p.play();
            }
            call.resolve();
        });
    }

    @PluginMethod
    public void next(final PluginCall call) {
        main.post(() -> {
            PlaybackService svc = PlaybackService.instance;
            if (svc != null) svc.player.seekToNext();
            call.resolve();
        });
    }

    @PluginMethod
    public void prev(final PluginCall call) {
        main.post(() -> {
            PlaybackService svc = PlaybackService.instance;
            if (svc != null) svc.player.seekToPrevious();    // restarts the song when it is past 3 seconds
            call.resolve();
        });
    }

    @PluginMethod
    public void seek(final PluginCall call) {
        final int ms = call.getInt("ms", 0);
        main.post(() -> {
            PlaybackService svc = PlaybackService.instance;
            if (svc != null) svc.player.seekTo(Math.max(0, ms));
            call.resolve();
        });
    }

    @PluginMethod
    public void openVideo(final PluginCall call) {
        main.post(() -> {
            PlaybackService svc = PlaybackService.instance;
            if (svc != null && PlaybackService.isVideo(svc.player.getCurrentMediaItem())) launchPlayer();
            call.resolve();
        });
    }

    @PluginMethod
    public void stop(final PluginCall call) {
        main.post(() -> {
            PlaybackService svc = PlaybackService.instance;
            if (svc != null) svc.stopAndClear();
            emitState();
            call.resolve();
        });
    }

    @PluginMethod
    public void getState(final PluginCall call) {
        main.post(() -> call.resolve(stateObject()));
    }

    // ---------------------------------------------------------------- state to the web side

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            emitState();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            emitState();
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            emitState();
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            JSObject o = new JSObject();
            o.put("message", error.getErrorCodeName());
            notifyListeners("error", o);
        }
    };

    /** Position moves on its own, so while playing we report it twice a second. */
    private final Runnable positionTick = new Runnable() {
        @Override
        public void run() {
            emitState();
        }
    };

    private void attach(PlaybackService svc) {
        if (attachedTo == svc.player) return;
        if (attachedTo != null) attachedTo.removeListener(listener);
        attachedTo = svc.player;
        attachedTo.addListener(listener);
    }

    private JSObject stateObject() {
        JSObject o = new JSObject();
        PlaybackService svc = PlaybackService.instance;
        if (svc == null) {
            o.put("playing", false);
            o.put("count", 0);
            return o;
        }
        ExoPlayer p = svc.player;
        MediaItem item = p.getCurrentMediaItem();
        int state = p.getPlaybackState();
        long dur = p.getDuration();
        CharSequence title = item == null ? null : item.mediaMetadata.title;
        CharSequence artist = item == null ? null : item.mediaMetadata.artist;
        o.put("playing", p.getPlayWhenReady() && state != Player.STATE_ENDED && state != Player.STATE_IDLE);
        o.put("index", p.getCurrentMediaItemIndex());
        o.put("count", p.getMediaItemCount());
        o.put("kind", PlaybackService.isVideo(item) ? "video" : "audio");
        o.put("path", item == null ? "" : item.mediaId);
        o.put("title", title == null ? "" : title.toString());
        o.put("artist", artist == null ? "" : artist.toString());
        o.put("position", Math.max(0L, p.getCurrentPosition()));
        o.put("duration", dur == C.TIME_UNSET ? 0L : dur);
        return o;
    }

    private void emitState() {
        main.removeCallbacks(positionTick);
        notifyListeners("state", stateObject());
        PlaybackService svc = PlaybackService.instance;
        if (svc != null && svc.player.isPlaying()) main.postDelayed(positionTick, 500);
    }

    private void launchPlayer() {
        Activity a = getActivity();
        Context c = a != null ? a : getContext();
        Intent i = new Intent(c, PlayerActivity.class);
        if (a == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }
}
