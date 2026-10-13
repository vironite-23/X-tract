package com.vironite.retrievex.ui;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.AudioManager;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.vironite.retrievex.R;
import com.vironite.retrievex.data.MediaItem;
import com.vironite.retrievex.data.Repo;
import com.vironite.retrievex.util.Prefs;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen viewer for downloaded media: swipe between items, pinch-zoom images and play videos with
 * a play/pause button and seek bar. Tap anywhere to hide or show the controls.
 * <p>
 * Video gestures: double-tap the left / right side to skip 10 s back / forward (keep tapping to skip more),
 * double-tap the middle to play or pause, drag up/down on the left half for brightness and on the right half
 * for volume, and press and hold to play at 2x speed until you let go.
 */
public class MediaViewerActivity extends AppCompatActivity {
    private static final String EXTRA_IDS = "ids";
    private static final String EXTRA_INDEX = "index";
    private static final String STATE_INDEX = "state_index";
    private static final int WINDOW = 150;            // items kept on each side of the opened one
    private static final int MAX_IMAGE_PX = 2048;
    private static final int TYPE_IMAGE = 0;
    private static final int TYPE_VIDEO = 1;
    private static final int SEEK_STEP_MS = 10_000;     // one double-tap skip
    private static final long SEEK_STREAK_MS = 700;     // keep tapping within this time to keep skipping
    private static final float BOOST_SPEED = 2f;        // press-and-hold playback speed
    private static final int GESTURE_NONE = 0;
    private static final int GESTURE_IGNORE = 1;        // horizontal drag: left to the pager
    private static final int GESTURE_BRIGHTNESS = 2;
    private static final int GESTURE_VOLUME = 3;
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    /** Opens the viewer on {@code items.get(index)} with the neighbouring items available by swiping. */
    public static void start(Context c, List<MediaItem> items, int index) {
        if (items == null || items.isEmpty() || index < 0 || index >= items.size()) return;
        int from = Math.max(0, index - WINDOW);
        int to = Math.min(items.size(), index + WINDOW + 1);
        List<Long> ids = new ArrayList<>();
        int opened = 0;
        for (int i = from; i < to; i++) {
            MediaItem m = items.get(i);
            if (m.availability != MediaItem.AVAIL_OK && i != index) continue;
            if (i == index) opened = ids.size();
            ids.add(m.id);
        }
        long[] arr = new long[ids.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = ids.get(i);
        Intent i = new Intent(c, MediaViewerActivity.class);
        i.putExtra(EXTRA_IDS, arr);
        i.putExtra(EXTRA_INDEX, opened);
        if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    public static void startSingle(Context c, MediaItem item) {
        List<MediaItem> one = new ArrayList<>();
        one.add(item);
        start(c, one, 0);
    }

    /** Opens one library record by id; falls back to a system chooser when the record is gone. */
    public static void startById(Context c, long mediaId, String fallbackUri, String fallbackMime) {
        Intent i = new Intent(c, MediaViewerActivity.class);
        i.putExtra(EXTRA_IDS, new long[]{mediaId});
        i.putExtra(EXTRA_INDEX, 0);
        i.putExtra("fallback_uri", fallbackUri);
        i.putExtra("fallback_mime", fallbackMime);
        if (!(c instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<MediaItem> items = new ArrayList<>();
    private RecyclerView pager;
    private LinearLayoutManager layout;
    private PagerSnapHelper snap;
    private PageAdapter adapter;
    private TextView titleView;
    private TextView counterView;
    private View bar;
    private WindowInsetsControllerCompat insetsController;
    private AudioManager audio;
    private boolean muted;              // shared by every video of this viewer session
    private float shownBrightness = -1f;
    private int insetLeft, insetTop, insetRight, insetBottom;
    private boolean chrome = true;
    private int current = -1;
    private int startIndex;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_media_viewer);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        muted = Prefs.getBool(Prefs.AUTO_MUTE, false);
        if (Prefs.getBool(Prefs.REMEMBER_BRIGHTNESS, false)) {
            int saved = Prefs.getInt(Prefs.BRIGHTNESS_LEVEL, 0);
            if (saved > 0) applyBrightness(saved / 100f);
        }
        setupInsets();
        insetsController = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);
        insetsController.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);

        bar = findViewById(R.id.viewer_bar);
        titleView = findViewById(R.id.viewer_title);
        counterView = findViewById(R.id.viewer_counter);
        pager = findViewById(R.id.viewer_pager);
        layout = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        pager.setLayoutManager(layout);
        pager.setItemViewCacheSize(1);
        pager.setItemAnimator(null);
        snap = new PagerSnapHelper();
        snap.attachToRecyclerView(pager);
        adapter = new PageAdapter();
        pager.setAdapter(adapter);
        pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int state) {
                if (state == RecyclerView.SCROLL_STATE_IDLE) {
                    View v = snap.findSnapView(layout);
                    if (v != null) onPageSelected(layout.getPosition(v));
                }
            }
        });

        findViewById(R.id.viewer_back).setOnClickListener(v -> finish());
        findViewById(R.id.viewer_share).setOnClickListener(v -> {
            MediaItem m = currentItem();
            if (m == null) return;
            List<String> u = new ArrayList<>();
            List<String> t = new ArrayList<>();
            u.add(m.fileUri);
            t.add(m.mimeType);
            FileActions.shareUris(this, u, t);
        });
        findViewById(R.id.viewer_info).setOnClickListener(v -> {
            MediaItem m = currentItem();
            if (m != null) Dialogs.message(this, R.string.details, FileActions.details(this, m));
        });
        findViewById(R.id.viewer_open).setOnClickListener(v -> {
            MediaItem m = currentItem();
            if (m != null) {
                adapter.pauseAll();
                FileActions.openUri(this, m.fileUri, m.mimeType);
            }
        });

        long[] ids = getIntent().getLongArrayExtra(EXTRA_IDS);
        startIndex = savedInstanceState != null ? savedInstanceState.getInt(STATE_INDEX, -1)
                : getIntent().getIntExtra(EXTRA_INDEX, 0);
        if (startIndex < 0) startIndex = getIntent().getIntExtra(EXTRA_INDEX, 0);
        final long[] fIds = ids == null ? new long[0] : ids;
        IO.execute(() -> {
            final List<MediaItem> loaded = new ArrayList<>();
            for (long id : fIds) {
                MediaItem m = Repo.get().getMedia(id);
                if (m != null) loaded.add(m);
            }
            main.post(() -> {
                if (destroyed) return;
                if (loaded.isEmpty()) {
                    String uri = getIntent().getStringExtra("fallback_uri");
                    if (uri != null) FileActions.openUri(this, uri, getIntent().getStringExtra("fallback_mime"));
                    else Toast.makeText(this, R.string.viewer_load_failed, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                items.clear();
                items.addAll(loaded);
                adapter.notifyDataSetChanged();
                int idx = Math.min(Math.max(0, startIndex), items.size() - 1);
                layout.scrollToPositionWithOffset(idx, 0);
                pager.post(() -> onPageSelected(idx));
            });
        });
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_INDEX, Math.max(0, current));
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // keep the same item in view after a rotation
        final int pos = Math.max(0, current);
        pager.post(() -> layout.scrollToPositionWithOffset(pos, 0));
    }

    @Override
    protected void onPause() {
        adapter.pauseAll();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        adapter.releaseAll();
        super.onDestroy();
    }

    private MediaItem currentItem() {
        return current >= 0 && current < items.size() ? items.get(current) : null;
    }

    private void onPageSelected(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        if (pos == current) return;
        int old = current;
        current = pos;
        MediaItem m = items.get(pos);
        titleView.setText(m.filename);
        counterView.setText(getString(R.string.viewer_counter, pos + 1, items.size()));
        RecyclerView.ViewHolder oldHolder = old >= 0 ? pager.findViewHolderForAdapterPosition(old) : null;
        if (oldHolder instanceof VideoHolder) ((VideoHolder) oldHolder).stop();
        RecyclerView.ViewHolder h = pager.findViewHolderForAdapterPosition(pos);
        if (h instanceof VideoHolder) ((VideoHolder) h).play();
    }

    private void toggleChrome() {
        setChrome(!chrome);
    }

    private void setChrome(boolean show) {
        chrome = show;
        bar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) insetsController.show(WindowInsetsCompat.Type.systemBars());
        else insetsController.hide(WindowInsetsCompat.Type.systemBars());
        adapter.applyChrome(show);
    }

    /**
     * The pager always fills the whole window and never changes size, so hiding the system bars cannot move the
     * video. Only the top bar and the video controls are moved clear of the bars, using the bar sizes as they are
     * when shown (not the current, possibly zero, ones).
     */
    private void setupInsets() {
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        View root = findViewById(R.id.viewer_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets b = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            insetLeft = b.left;
            insetTop = b.top;
            insetRight = b.right;
            insetBottom = b.bottom;
            applyInsets();
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void applyInsets() {
        if (bar != null) {
            int side = (int) (4 * getResources().getDisplayMetrics().density);
            bar.setPadding(side + insetLeft, insetTop, side + insetRight, 0);
        }
        if (adapter != null) adapter.applyInsets();
    }

    private void applyMuteAll() {
        for (int i = 0; i < pager.getChildCount(); i++) {
            RecyclerView.ViewHolder h = pager.getChildViewHolder(pager.getChildAt(i));
            if (h instanceof VideoHolder) ((VideoHolder) h).applyMute();
        }
    }

    /** Remembers the brightness chosen with the swipe gesture when "Remember brightness" is on. */
    private void saveBrightness() {
        if (shownBrightness > 0f && Prefs.getBool(Prefs.REMEMBER_BRIGHTNESS, false)) {
            Prefs.putInt(Prefs.BRIGHTNESS_LEVEL, Math.max(1, Math.round(shownBrightness * 100f)));
        }
    }

    /** Current window brightness (0..1); falls back to the system setting while the window follows it. */
    private float readBrightness() {
        float b = getWindow().getAttributes().screenBrightness;
        if (b >= 0f) return b;
        try {
            return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Settings.SettingNotFoundException e) {
            return 0.5f;
        }
    }

    /** Sets the brightness of this window only; the system setting is not touched. */
    private void applyBrightness(float value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = value;
        getWindow().setAttributes(lp);
        shownBrightness = value;
    }

    // ------------------------------------------------------------------ pages

    private final class PageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override
        public int getItemViewType(int position) {
            return items.get(position).isVideoLike() ? TYPE_VIDEO : TYPE_IMAGE;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_VIDEO) {
                return new VideoHolder(inf.inflate(R.layout.item_viewer_video, parent, false));
            }
            return new ImageHolder(inf.inflate(R.layout.item_viewer_image, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int position) {
            MediaItem m = items.get(position);
            if (h instanceof VideoHolder) {
                VideoHolder v = (VideoHolder) h;
                v.bind(m);
                v.applyInsets();
                v.applyMute();
                v.setControlsVisible(chrome);
                // the first page is selected before its holder exists
                if (position == current) v.play();
            } else {
                ((ImageHolder) h).bind(m);
            }
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder h) {
            if (h instanceof VideoHolder) ((VideoHolder) h).release();
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        void applyInsets() {
            for (int i = 0; i < pager.getChildCount(); i++) {
                RecyclerView.ViewHolder h = pager.getChildViewHolder(pager.getChildAt(i));
                if (h instanceof VideoHolder) ((VideoHolder) h).applyInsets();
            }
        }

        void applyChrome(boolean show) {
            for (int i = 0; i < pager.getChildCount(); i++) {
                RecyclerView.ViewHolder h = pager.getChildViewHolder(pager.getChildAt(i));
                if (h instanceof VideoHolder) ((VideoHolder) h).setControlsVisible(show);
            }
        }

        void pauseAll() {
            for (int i = 0; i < pager.getChildCount(); i++) {
                RecyclerView.ViewHolder h = pager.getChildViewHolder(pager.getChildAt(i));
                if (h instanceof VideoHolder) ((VideoHolder) h).pause();
            }
        }

        void releaseAll() {
            for (int i = 0; i < pager.getChildCount(); i++) {
                RecyclerView.ViewHolder h = pager.getChildViewHolder(pager.getChildAt(i));
                if (h instanceof VideoHolder) ((VideoHolder) h).release();
            }
        }
    }

    // ------------------------------------------------------------------ image page

    private final class ImageHolder extends RecyclerView.ViewHolder {
        final ZoomImageView image;
        final ProgressBar progress;
        long boundId = -1;

        ImageHolder(View v) {
            super(v);
            image = v.findViewById(R.id.vi_image);
            progress = v.findViewById(R.id.vi_progress);
            image.setTapListener(MediaViewerActivity.this::toggleChrome);
        }

        void bind(final MediaItem m) {
            boundId = m.id;
            image.setImageDrawable(null);
            progress.setVisibility(View.VISIBLE);
            final Uri uri = Uri.parse(m.fileUri);
            IO.execute(() -> {
                Bitmap b = null;
                try {
                    if (boundId == m.id) b = decode(MediaViewerActivity.this, uri, MAX_IMAGE_PX);
                } catch (Throwable ignored) {
                    // handled below
                }
                final Bitmap result = b;
                main.post(() -> {
                    if (destroyed || boundId != m.id) return;
                    progress.setVisibility(View.GONE);
                    if (result != null) {
                        image.setImageBitmap(result);
                    } else {
                        image.setImageResource(R.drawable.ic_broken_image);
                        Toast.makeText(MediaViewerActivity.this, R.string.viewer_load_failed, Toast.LENGTH_SHORT).show();
                    }
                });
            });
        }
    }

    /** Decodes an image no larger than {@code maxPx} on its longest side and applies its EXIF rotation. */
    static Bitmap decode(Context c, Uri uri, int maxPx) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > maxPx) sample *= 2;
        o.inSampleSize = sample;
        o.inJustDecodeBounds = false;
        Bitmap bmp;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            bmp = BitmapFactory.decodeStream(in, null, o);
        }
        if (bmp == null) return null;
        int degrees = 0;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in != null) {
                int orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                if (orientation == ExifInterface.ORIENTATION_ROTATE_90) degrees = 90;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) degrees = 180;
                else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) degrees = 270;
            }
        } catch (Exception ignored) {
            // no EXIF data
        }
        if (degrees != 0) {
            Matrix mx = new Matrix();
            mx.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), mx, true);
            if (rotated != bmp) bmp.recycle();
            bmp = rotated;
        }
        return bmp;
    }

    // ------------------------------------------------------------------ video page

    private final class VideoHolder extends RecyclerView.ViewHolder {
        final VideoView video;
        final ImageView poster;
        final ProgressBar progress;
        final ImageButton centerPlay;
        final ImageButton toggle;
        final View controls;
        final SeekBar seek;
        final TextView pos;
        final TextView dur;
        final ImageButton muteBtn;
        final View hud;
        final ImageView hudIcon;
        final ProgressBar hudBar;
        final TextView hudText;
        final TextView seekLeft;
        final TextView seekRight;
        final GestureDetector gestures;
        final Runnable singleTap;
        final Runnable hideHud;
        final Runnable hideSeek;
        MediaPlayer player;
        boolean speedBoost;
        boolean tapPending;
        int gestureMode = GESTURE_NONE;
        float gestureStart;
        int lastVolumeIndex = -1;
        long streakUntil;
        int skipTotalMs;
        int skipSide;
        int skipTargetMs = -1;
        MediaItem item;
        boolean prepared;
        boolean loading;
        boolean wantPlay;
        boolean userSeeking;
        boolean controlsShown = true;
        final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                updateProgress();
                if (video.isPlaying()) main.postDelayed(this, 250);
            }
        };

        VideoHolder(View v) {
            super(v);
            video = v.findViewById(R.id.vv_video);
            poster = v.findViewById(R.id.vv_poster);
            progress = v.findViewById(R.id.vv_progress);
            centerPlay = v.findViewById(R.id.vv_center_play);
            toggle = v.findViewById(R.id.vv_toggle);
            controls = v.findViewById(R.id.vv_controls);
            seek = v.findViewById(R.id.vv_seek);
            pos = v.findViewById(R.id.vv_pos);
            dur = v.findViewById(R.id.vv_dur);
            muteBtn = v.findViewById(R.id.vv_mute);
            muteBtn.setOnClickListener(x -> {
                muted = !muted;
                applyMuteAll();
            });
            hud = v.findViewById(R.id.vv_hud);
            hudIcon = v.findViewById(R.id.vv_hud_icon);
            hudBar = v.findViewById(R.id.vv_hud_bar);
            hudText = v.findViewById(R.id.vv_hud_text);
            seekLeft = v.findViewById(R.id.vv_seek_left);
            seekRight = v.findViewById(R.id.vv_seek_right);

            singleTap = () -> {
                tapPending = false;
                toggleChrome();
            };
            hideHud = () -> hud.setVisibility(View.GONE);
            hideSeek = () -> {
                seekLeft.setVisibility(View.GONE);
                seekRight.setVisibility(View.GONE);
                skipTotalMs = 0;
                skipSide = 0;
                skipTargetMs = -1;
            };
            gestures = new GestureDetector(v.getContext(), new GestureDetector.OnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    gestureMode = GESTURE_NONE;
                    return true;
                }

                @Override
                public void onShowPress(MotionEvent e) {
                    // nothing to do
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    onTap(e.getX());
                    return true;
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    return e1 != null && onDrag(e1, e2);
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    beginSpeedBoost();
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    return false;
                }
            });
            v.setOnTouchListener((view, ev) -> {
                gestures.onTouchEvent(ev);
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) endGesture();
                return true;
            });
            centerPlay.setOnClickListener(x -> togglePlayback());
            toggle.setOnClickListener(x -> togglePlayback());
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (fromUser) {
                        pos.setText(time(p));
                        if (prepared) video.seekTo(p);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                    userSeeking = true;
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    userSeeking = false;
                    updateProgress();
                }
            });
        }

        void bind(MediaItem m) {
            release();
            resetGestureUi();
            item = m;
            prepared = false;
            loading = false;
            wantPlay = false;
            video.setVisibility(View.INVISIBLE);
            poster.setVisibility(View.VISIBLE);
            ImageLoader.loadMedia(poster, m, 1024);
            seek.setMax(Math.max(1, (int) m.duration));
            seek.setProgress(0);
            pos.setText(time(0));
            dur.setText(time(m.duration));
            progress.setVisibility(View.GONE);
            refreshButtons();
        }

        void setControlsVisible(boolean show) {
            controlsShown = show;
            controls.setVisibility(show ? View.VISIBLE : View.GONE);
            refreshButtons();
        }

        /** Starts playback of this page (loads the file the first time). */
        void play() {
            if (item == null) return;
            wantPlay = true;
            if (!prepared) {
                if (!loading) load();
                return;
            }
            if (video.getCurrentPosition() >= Math.max(1, video.getDuration()) - 200) video.seekTo(0);
            video.start();
            onPlaying();
        }

        void pause() {
            wantPlay = false;
            if (prepared && video.isPlaying()) video.pause();
            main.removeCallbacks(ticker);
            itemView.setKeepScreenOn(false);
            refreshButtons();
        }

        /** Leaves the page: stops playback and shows the poster frame again. */
        void stop() {
            resetGestureUi();
            wantPlay = false;
            if (prepared) video.pause();
            main.removeCallbacks(ticker);
            itemView.setKeepScreenOn(false);
            if (prepared) video.seekTo(0);
            video.setVisibility(View.INVISIBLE);
            poster.setVisibility(View.VISIBLE);
            seek.setProgress(0);
            pos.setText(time(0));
            refreshButtons();
        }

        void release() {
            main.removeCallbacks(ticker);
            main.removeCallbacks(singleTap);
            tapPending = false;
            speedBoost = false;
            player = null;
            itemView.setKeepScreenOn(false);
            try {
                video.stopPlayback();
            } catch (Exception ignored) {
                // already released
            }
            prepared = false;
            loading = false;
            wantPlay = false;
        }

        void applyInsets() {
            float d = getResources().getDisplayMetrics().density;
            controls.setPadding((int) (8 * d) + insetLeft, (int) (12 * d), (int) (16 * d) + insetRight, insetBottom);
        }

        /** Applies the shared mute state to this page's player and button. */
        void applyMute() {
            muteBtn.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
            muteBtn.setContentDescription(getString(muted ? R.string.cd_unmute : R.string.cd_mute));
            if (player != null) {
                try {
                    player.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
                } catch (RuntimeException ignored) {
                    // player not in a state that allows it; the next prepare applies it again
                }
            }
        }

        // -------------------------------------------------------------- gestures

        /** A tap that did not turn into a drag or a long press. */
        private void onTap(float x) {
            float w = Math.max(1, itemView.getWidth());
            int zone = x < w * 0.35f ? -1 : x > w * 0.65f ? 1 : 0;
            if (tapPending) {
                // second tap of a double tap
                main.removeCallbacks(singleTap);
                tapPending = false;
                if (zone == 0) togglePlayback();
                else skip(zone);
            } else if (zone != 0 && SystemClock.uptimeMillis() < streakUntil) {
                skip(zone);     // still skipping: every further tap on that side skips again
            } else {
                tapPending = true;
                main.postDelayed(singleTap, ViewConfiguration.getDoubleTapTimeout());
            }
        }

        /** Skips 10 s back (side -1) or forward (side 1) and shows how far this streak has gone. */
        private void skip(int side) {
            streakUntil = SystemClock.uptimeMillis() + SEEK_STREAK_MS;
            main.removeCallbacks(hideSeek);
            main.postDelayed(hideSeek, SEEK_STREAK_MS);
            if (!prepared) return;
            if (side != skipSide) {
                skipSide = side;
                skipTotalMs = 0;
                skipTargetMs = -1;
            }
            int dur = Math.max(1, video.getDuration());
            int base = skipTargetMs >= 0 ? skipTargetMs : video.getCurrentPosition();
            int target = Math.max(0, Math.min(dur, base + side * SEEK_STEP_MS));
            skipTargetMs = target;
            skipTotalMs += SEEK_STEP_MS;
            video.seekTo(target);
            seek.setProgress(target);
            pos.setText(time(target));
            TextView shown = side < 0 ? seekLeft : seekRight;
            TextView other = side < 0 ? seekRight : seekLeft;
            other.setVisibility(View.GONE);
            shown.setText(getString(side < 0 ? R.string.gesture_seek_back : R.string.gesture_seek_forward,
                    skipTotalMs / 1000));
            shown.setVisibility(View.VISIBLE);
        }

        /** Vertical drag: left half = brightness, right half = volume. Horizontal drags stay with the pager. */
        private boolean onDrag(MotionEvent start, MotionEvent now) {
            if (speedBoost) return false;
            if (gestureMode == GESTURE_NONE) {
                float tx = now.getX() - start.getX();
                float ty = now.getY() - start.getY();
                if (Math.abs(ty) < Math.abs(tx) * 1.2f) {
                    gestureMode = GESTURE_IGNORE;
                    return false;
                }
                if (start.getX() < itemView.getWidth() / 2f) {
                    gestureMode = GESTURE_BRIGHTNESS;
                    gestureStart = readBrightness();
                } else {
                    gestureMode = GESTURE_VOLUME;
                    int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    gestureStart = max > 0 ? (float) audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max : 0f;
                    lastVolumeIndex = -1;
                }
                // a tap-and-drag must not become a page swipe halfway through
                if (itemView.getParent() != null) itemView.getParent().requestDisallowInterceptTouchEvent(true);
                main.removeCallbacks(singleTap);
                tapPending = false;
            }
            if (gestureMode == GESTURE_IGNORE) return false;
            // dragging across 70% of the page height covers the whole range
            float range = Math.max(1, itemView.getHeight()) * 0.7f;
            float delta = (start.getY() - now.getY()) / range;
            if (gestureMode == GESTURE_BRIGHTNESS) {
                float level = Math.max(0.01f, Math.min(1f, gestureStart + delta));
                applyBrightness(level);
                showLevel(R.drawable.ic_brightness, level);
            } else {
                int max = Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
                float level = Math.max(0f, Math.min(1f, gestureStart + delta));
                int index = Math.round(level * max);
                if (index != lastVolumeIndex) {
                    lastVolumeIndex = index;
                    try {
                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0);
                    } catch (SecurityException ignored) {
                        // some devices block volume changes in Do Not Disturb
                    }
                }
                showLevel(index == 0 ? R.drawable.ic_volume_off : R.drawable.ic_volume_up, (float) index / max);
            }
            return true;
        }

        private void showLevel(int icon, float level) {
            int pct = Math.round(level * 100f);
            main.removeCallbacks(hideHud);
            hudIcon.setImageResource(icon);
            hudBar.setVisibility(View.VISIBLE);
            hudBar.setProgress(pct);
            hudText.setText(String.format(Locale.US, "%d%%", pct));
            hud.setVisibility(View.VISIBLE);
        }

        /** Press and hold: plays at 2x until the finger is lifted. */
        private void beginSpeedBoost() {
            if (!prepared || player == null || !video.isPlaying() || gestureMode != GESTURE_NONE) return;
            if (!setSpeed(BOOST_SPEED)) return;
            speedBoost = true;
            itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (itemView.getParent() != null) itemView.getParent().requestDisallowInterceptTouchEvent(true);
            main.removeCallbacks(hideHud);
            hudIcon.setImageResource(R.drawable.ic_fast_forward);
            hudBar.setVisibility(View.GONE);
            hudText.setText(R.string.gesture_speed);
            hud.setVisibility(View.VISIBLE);
        }

        private void endSpeedBoost() {
            if (!speedBoost) return;
            speedBoost = false;
            boolean playing = prepared && video.isPlaying();
            setSpeed(1f);
            // changing the speed of a paused player can start it on some devices
            if (prepared && !playing && video.isPlaying()) video.pause();
            hud.setVisibility(View.GONE);
        }

        private boolean setSpeed(float speed) {
            if (player == null) return false;
            try {
                player.setPlaybackParams(player.getPlaybackParams().setSpeed(speed));
                return true;
            } catch (RuntimeException e) {
                return false;       // this device or file doesn't support changing the speed
            }
        }

        /** Finger lifted (or the pager took the touch over). */
        private void endGesture() {
            if (gestureMode == GESTURE_BRIGHTNESS) saveBrightness();
            if (gestureMode == GESTURE_BRIGHTNESS || gestureMode == GESTURE_VOLUME) {
                main.removeCallbacks(hideHud);
                main.postDelayed(hideHud, 600);
            }
            gestureMode = GESTURE_NONE;
            endSpeedBoost();
        }

        private void resetGestureUi() {
            main.removeCallbacks(hideHud);
            main.removeCallbacks(hideSeek);
            main.removeCallbacks(singleTap);
            tapPending = false;
            gestureMode = GESTURE_NONE;
            streakUntil = 0;
            endSpeedBoost();
            hud.setVisibility(View.GONE);
            hideSeek.run();
        }

        private void togglePlayback() {
            if (prepared && video.isPlaying()) pause();
            else play();
        }

        private void load() {
            loading = true;
            progress.setVisibility(View.VISIBLE);
            final boolean loop = MediaItem.TYPE_GIF.equals(item.mediaType);
            video.setOnPreparedListener((MediaPlayer mp) -> {
                player = mp;
                prepared = true;
                loading = false;
                mp.setLooping(loop);
                applyMute();
                int d = video.getDuration();
                if (d > 0) {
                    seek.setMax(d);
                    dur.setText(time(d));
                }
                progress.setVisibility(View.GONE);
                mp.setOnInfoListener((p, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) poster.setVisibility(View.GONE);
                    return false;
                });
                if (wantPlay) {
                    video.setVisibility(View.VISIBLE);
                    video.start();
                    onPlaying();
                    // some devices don't report the first rendered frame; don't leave the poster up forever
                    main.postDelayed(() -> {
                        if (prepared && video.isPlaying()) poster.setVisibility(View.GONE);
                    }, 400);
                }
            });
            video.setOnCompletionListener(mp -> {
                main.removeCallbacks(ticker);
                itemView.setKeepScreenOn(false);
                seek.setProgress(seek.getMax());
                refreshButtons();
            });
            video.setOnErrorListener((mp, what, extra) -> {
                progress.setVisibility(View.GONE);
                player = null;
                speedBoost = false;
                prepared = false;
                loading = false;
                wantPlay = false;
                video.setVisibility(View.INVISIBLE);
                poster.setVisibility(View.VISIBLE);
                refreshButtons();
                Toast.makeText(MediaViewerActivity.this, R.string.viewer_video_error, Toast.LENGTH_LONG).show();
                return true;
            });
            video.setVisibility(View.VISIBLE);
            video.setVideoURI(Uri.parse(item.fileUri));
        }

        private void onPlaying() {
            itemView.setKeepScreenOn(true);
            main.removeCallbacks(ticker);
            main.post(ticker);
            refreshButtons();
        }

        private void updateProgress() {
            if (!prepared || userSeeking) return;
            int p = video.getCurrentPosition();
            seek.setProgress(p);
            pos.setText(time(p));
        }

        private void refreshButtons() {
            boolean playing = prepared && video.isPlaying();
            toggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            toggle.setContentDescription(getString(playing ? R.string.cd_pause : R.string.cd_play));
            // the large play button is the call to action while nothing is playing and the controls are visible
            centerPlay.setVisibility(!playing && controlsShown && progress.getVisibility() != View.VISIBLE
                    ? View.VISIBLE : View.GONE);
        }
    }

    private static String time(long ms) {
        long total = Math.max(0, Math.round(ms / 1000.0));
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
