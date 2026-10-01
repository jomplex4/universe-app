package com.digitalminds.universe;

import android.animation.ValueAnimator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.Locale;

/**
 * UNIVERSE :: PlayerActivity
 *
 * Karaoke player. Only what karaoke needs: a progress bar with times, the five centre buttons
 * (back 10 s, previous, play, next, forward 10 s) and Fit / Fill. Plus a double tap on the left
 * or right side to jump 10 s.
 *
 * It does not own the player: the PlaybackService does, so the music keeps going (and the lights
 * keep following) when this screen is away.
 */
public class PlayerActivity extends AppCompatActivity {

    private static final long HIDE_AFTER_MS = 3500;

    private ExoPlayer player;
    private FrameLayout root;
    private VideoFrame videoFrame;
    private SurfaceView surface;
    private View controls;
    private View gradTop;
    private View gradBottom;
    private View topArea;
    private View bottomArea;
    private View gestureLayer;
    private TextView title;
    private TextView timePos;
    private TextView timeLeft;
    private TextView hud;
    private SeekBar seek;
    private ImageButton btnPlay;
    private ImageButton btnResize;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean closing;
    private boolean seeking;
    private boolean listening;
    private float picScale = 1f;
    private float lastRatio;
    private ValueAnimator picAnimator;
    private GestureDetector gestures;

    // ================================================================ life cycle

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PlaybackService svc = PlaybackService.instance;
        if (svc == null || svc.player.getMediaItemCount() == 0) {   // nothing to show (app restarted)
            finish();
            return;
        }
        player = svc.player;

        setContentView(R.layout.activity_player);
        root = findViewById(R.id.root);
        videoFrame = findViewById(R.id.videoFrame);
        surface = findViewById(R.id.surface);
        controls = findViewById(R.id.controls);
        gradTop = findViewById(R.id.gradTop);
        gradBottom = findViewById(R.id.gradBottom);
        topArea = findViewById(R.id.topArea);
        bottomArea = findViewById(R.id.bottomArea);
        gestureLayer = findViewById(R.id.gestureLayer);
        title = findViewById(R.id.title);
        timePos = findViewById(R.id.timePos);
        timeLeft = findViewById(R.id.timeLeft);
        hud = findViewById(R.id.hud);
        seek = findViewById(R.id.seek);
        btnPlay = findViewById(R.id.btnPlay);
        btnResize = findViewById(R.id.btnResize);

        setupWindow();
        setupControls();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                closePlayer();
            }
        });
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (player != null) refreshAll();
    }

    @Override
    protected void onStart() {
        super.onStart();
        PlaybackService svc = PlaybackService.instance;
        if (svc == null || player == null) return;
        svc.setVideoEnabled(true);
        player.setVideoSurfaceView(surface);
        if (!listening) {
            player.addListener(listener);
            listening = true;
        }
        refreshAll();
        handler.post(ticker);
        showControls();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(hideControls);
        PlaybackService svc = PlaybackService.instance;
        if (player == null || svc == null) return;
        if (!closing && !isFinishing() && !isChangingConfigurations() && player.getPlayWhenReady()) {
            svc.setVideoEnabled(false);          // Home, screen off, another app: keep going as audio only
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (picAnimator != null) picAnimator.cancel();
        if (player != null) {
            if (listening) player.removeListener(listener);
            player.clearVideoSurfaceView(surface);
        }
        super.onDestroy();
    }

    /** Back arrow: stops watching. The queue stays, so the mini player in the app can bring it back. */
    private void closePlayer() {
        closing = true;
        if (player != null) player.pause();
        finish();
    }

    // ================================================================ window (camera cutout)

    private void setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            // The picture may use the strip behind the camera, so "Fill" really fills the whole screen.
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        // Buttons stay clear of the notch; the video itself and the dark gradients go edge to edge.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
            float d = getResources().getDisplayMetrics().density;
            topArea.setPadding(cut.left, cut.top, cut.right, 0);
            bottomArea.setPadding((int) (8 * d) + cut.left, 0, (int) (8 * d) + cut.right, (int) (6 * d) + cut.bottom);
            gradTop.getLayoutParams().height = (int) (120 * d) + cut.top;
            gradBottom.getLayoutParams().height = (int) (150 * d) + cut.bottom;
            gradTop.requestLayout();
            gradBottom.requestLayout();
            return insets;
        });
    }

    private void hideSystemUi() {
        WindowInsetsControllerCompat c = new WindowInsetsControllerCompat(getWindow(), root);
        c.hide(WindowInsetsCompat.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // ================================================================ controls

    private void setupControls() {
        findViewById(R.id.btnBack).setOnClickListener(v -> closePlayer());
        btnPlay.setOnClickListener(v -> {
            togglePlay();
            scheduleHide();
        });
        findViewById(R.id.btnPrev).setOnClickListener(v -> {
            player.seekToPrevious();
            scheduleHide();
        });
        findViewById(R.id.btnNext).setOnClickListener(v -> {
            player.seekToNext();
            scheduleHide();
        });
        findViewById(R.id.btnRewind).setOnClickListener(v -> {
            skip(-10_000);
            scheduleHide();
        });
        findViewById(R.id.btnForward).setOnClickListener(v -> {
            skip(10_000);
            scheduleHide();
        });
        btnResize.setOnClickListener(v -> {
            togglePicture();
            scheduleHide();
        });

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                long dur = player.getDuration();
                if (dur == C.TIME_UNSET || dur <= 0) return;
                long pos = dur * progress / 1000L;
                timePos.setText(clock(pos));
                timeLeft.setText("-" + clock(dur - pos));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seeking = true;
                handler.removeCallbacks(hideControls);
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                long dur = player.getDuration();
                if (dur != C.TIME_UNSET && dur > 0) player.seekTo(dur * bar.getProgress() / 1000L);
                seeking = false;
                scheduleHide();
            }
        });

        // Tap = show or hide the controls. Double tap on the left / right third = jump 10 s.
        gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (controls.getVisibility() == View.VISIBLE) hideControlsNow();
                else showControls();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float w = root.getWidth();
                if (e.getX() < w / 3f) skip(-10_000);
                else if (e.getX() > 2f * w / 3f) skip(10_000);
                else togglePlay();
                return true;
            }
        });
        gestureLayer.setOnTouchListener((v, ev) -> gestures.onTouchEvent(ev));
    }

    private void togglePlay() {
        if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare();
        if (player.getPlaybackState() == Player.STATE_ENDED) player.seekToDefaultPosition();
        if (player.getPlayWhenReady()) player.pause();
        else player.play();
    }

    private void skip(long deltaMs) {
        long dur = player.getDuration();
        long target = player.getCurrentPosition() + deltaMs;
        if (dur != C.TIME_UNSET && dur > 0) target = Math.min(target, dur - 200);
        player.seekTo(Math.max(0, target));
        showHud(deltaMs < 0 ? "-10 s" : "+10 s");
        updateProgress();
    }

    // ================================================================ show / hide controls

    private final Runnable hideControls = this::hideControlsNow;

    private void hideControlsNow() {
        if (seeking) return;
        controls.animate().alpha(0f).setDuration(180).withEndAction(() -> controls.setVisibility(View.INVISIBLE)).start();
    }

    private void showControls() {
        controls.animate().cancel();
        controls.setVisibility(View.VISIBLE);
        controls.setAlpha(1f);
        scheduleHide();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideControls);
        if (player != null && player.isPlaying()) handler.postDelayed(hideControls, HIDE_AFTER_MS);
    }

    private final Runnable hideHud = () -> hud.setVisibility(View.GONE);

    private void showHud(String text) {
        hud.setText(text);
        hud.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideHud);
        handler.postDelayed(hideHud, 900);
    }

    // ================================================================ player wiring

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onIsPlayingChanged(boolean isPlaying) {
            updatePlayButton();
            if (isPlaying) scheduleHide();
            else showControls();
        }

        @Override
        public void onPlaybackStateChanged(int state) {
            updatePlayButton();
        }

        @Override
        public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
            updateTitle();
            resetPicture();
            updateProgress();
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            applyVideoSize(size);
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            showHud("This video can't be played");
            showControls();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            handler.postDelayed(this, 250);
        }
    };

    private void refreshAll() {
        updateTitle();
        updatePlayButton();
        applyVideoSize(player.getVideoSize());
        updateProgress();
    }

    private void updateTitle() {
        MediaItem item = player.getCurrentMediaItem();
        CharSequence t = item == null ? null : item.mediaMetadata.title;
        title.setText(t == null ? "" : t);
    }

    private void updatePlayButton() {
        boolean playing = player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlaybackState() != Player.STATE_IDLE;
        btnPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    private void updateProgress() {
        if (player == null) return;
        long dur = player.getDuration();
        long pos = Math.max(0, player.getCurrentPosition());
        if (dur == C.TIME_UNSET || dur <= 0) {
            timePos.setText(clock(pos));
            timeLeft.setText("-00:00");
            return;
        }
        if (!seeking) {
            seek.setProgress((int) (1000L * pos / dur));
            timePos.setText(clock(pos));
            timeLeft.setText("-" + clock(Math.max(0, dur - pos)));
        }
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%02d:%02d", m, sec);
    }

    // ================================================================ picture: Fit and Fill

    private void applyVideoSize(@Nullable VideoSize size) {
        if (size == null || size.width == 0 || size.height == 0) return;
        float ratio = size.width * size.pixelWidthHeightRatio / size.height;
        if (Math.abs(ratio - lastRatio) > 0.001f) {
            lastRatio = ratio;
            resetPicture();
        }
        videoFrame.setAspectRatio(ratio);
    }

    /** Scale that makes the fitted frame cover the whole window (1 when it already does). */
    private float fillScale() {
        float fw = videoFrame.getWidth();
        float fh = videoFrame.getHeight();
        if (fw <= 0f || fh <= 0f) return 1f;
        return Math.max(1f, Math.max(root.getWidth() / fw, root.getHeight() / fh));
    }

    /** Fit and Fill button: flips between the two states. */
    private void togglePicture() {
        float fill = fillScale();
        float target = (picScale > 1.02f || fill <= 1.02f) ? 1f : fill;
        animatePicture(target);
        showHud(target > 1.02f ? "Fill" : "Fit");
        btnResize.setImageResource(target > 1.02f ? R.drawable.ic_fit : R.drawable.ic_fill);
    }

    private void animatePicture(final float target) {
        if (picAnimator != null) picAnimator.cancel();
        final float from = picScale;
        picAnimator = ValueAnimator.ofFloat(0f, 1f);
        picAnimator.setDuration(200);
        picAnimator.setInterpolator(new DecelerateInterpolator());
        picAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            picScale = from + (target - from) * t;
            videoFrame.setScaleX(picScale);
            videoFrame.setScaleY(picScale);
        });
        picAnimator.start();
    }

    private void resetPicture() {
        if (picAnimator != null) picAnimator.cancel();
        picScale = 1f;
        videoFrame.setScaleX(1f);
        videoFrame.setScaleY(1f);
        btnResize.setImageResource(R.drawable.ic_fill);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        resetPicture();     // turning the phone: back to the whole picture (Fit), not a stretched crop
    }
}
