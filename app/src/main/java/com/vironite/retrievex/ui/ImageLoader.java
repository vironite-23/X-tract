package com.vironite.retrievex.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import com.vironite.retrievex.R;
import com.vironite.retrievex.data.MediaItem;
import com.vironite.retrievex.extract.Http;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small thumbnail loader with a memory LRU cache, a disk cache for remote images and downsampled decoding. */
public final class ImageLoader {
    private interface Source {
        Bitmap load() throws Exception;
    }

    private static LruCache<String, Bitmap> cache;
    private static File diskDir;
    private static Context appContext;
    private static final ExecutorService EXEC = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageLoader() {
    }

    public static synchronized void init(Context c) {
        if (cache != null) return;
        appContext = c.getApplicationContext();
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        diskDir = new File(appContext.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        diskDir.mkdirs();
    }

    private static void load(final ImageView view, final String key, final Source source) {
        view.setTag(R.id.image_tag, key);
        Bitmap hit = cache.get(key);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        view.setImageDrawable(null);
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap b = null;
                try {
                    if (!key.equals(view.getTag(R.id.image_tag))) return; // view was recycled for another item
                    b = source.load();
                } catch (Throwable ignored) {
                    // leave the placeholder
                }
                if (b == null) return;
                cache.put(key, b);
                final Bitmap result = b;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (key.equals(view.getTag(R.id.image_tag))) view.setImageBitmap(result);
                    }
                });
            }
        });
    }

    /** Loads a remote HTTPS image (post thumbnails, avatars). */
    public static void loadUrl(ImageView view, final String url, final int px) {
        if (url == null || !url.startsWith("https://")) {
            view.setTag(R.id.image_tag, null);
            view.setImageDrawable(null);
            return;
        }
        load(view, "u:" + px + ":" + url, new Source() {
            @Override
            public Bitmap load() throws Exception {
                return loadRemote(url, px);
            }
        });
    }

    /** Loads a cached local file such as an avatar. */
    public static void loadFile(ImageView view, final String path, final int px) {
        if (path == null) {
            view.setTag(R.id.image_tag, null);
            view.setImageDrawable(null);
            return;
        }
        load(view, "f:" + px + ":" + path, new Source() {
            @Override
            public Bitmap load() throws Exception {
                return decodeFile(new File(path), px);
            }
        });
    }

    /** Loads the thumbnail of a downloaded library item (image or video). */
    public static void loadMedia(ImageView view, final MediaItem m, final int px) {
        if (m.availability != MediaItem.AVAIL_OK) {
            view.setTag(R.id.image_tag, null);
            view.setImageDrawable(null);
            return;
        }
        load(view, "m:" + px + ":" + m.fileUri, new Source() {
            @Override
            public Bitmap load() throws Exception {
                return loadLocal(Uri.parse(m.fileUri), !m.isImage(), px);
            }
        });
    }

    // ------------------------------------------------------------------ decoding

    /**
     * Decodes the real file instead of the system's pre-made thumbnail: those are only ~512 px, so they get blurry
     * as soon as a tile is bigger than that (detail view, large grid cells, high-density screens).
     */
    private static Bitmap loadLocal(Uri uri, boolean video, int px) throws IOException {
        if (video) {
            Bitmap frame = videoFrame(uri, px);
            if (frame != null) return frame;
            if (Build.VERSION.SDK_INT >= 29) {
                try {
                    return appContext.getContentResolver().loadThumbnail(uri, new Size(px, px), null);
                } catch (Exception ignored) {
                    // no thumbnail either
                }
            }
            return null;
        }
        Bitmap b = null;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                b = decodeWithDecoder(uri, px);
            } catch (Exception ignored) {
                // unsupported by ImageDecoder: use the manual path below
            }
        }
        if (b == null) b = decodeSampled(uri, px);
        if (b == null && Build.VERSION.SDK_INT >= 29) {
            try {
                b = appContext.getContentResolver().loadThumbnail(uri, new Size(px, px), null);
            } catch (Exception ignored) {
                // nothing left to try
            }
        }
        return b;
    }

    /**
     * Scale that makes the shorter side {@code px} long (the tile is cropped to fill, so the shorter side must
     * cover it), but never above 2x{@code px} on the longer side and never an enlargement.
     */
    private static float scaleFor(int w, int h, int px) {
        if (w <= 0 || h <= 0 || px <= 0) return 1f;
        float s = Math.min(px / (float) Math.min(w, h), 2f * px / Math.max(w, h));
        return Math.min(1f, s);
    }

    /** API 28+: decoded straight to the target size with proper filtering; rotation from EXIF is applied. */
    private static Bitmap decodeWithDecoder(Uri uri, final int px) throws IOException {
        ImageDecoder.Source src = ImageDecoder.createSource(appContext.getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
            @Override
            public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source source) {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                Size size = info.getSize();
                float s = scaleFor(size.getWidth(), size.getHeight(), px);
                if (s < 1f) {
                    decoder.setTargetSize(Math.max(1, Math.round(size.getWidth() * s)),
                            Math.max(1, Math.round(size.getHeight() * s)));
                }
            }
        });
    }

    /** Older devices: sampled decode (never smaller than needed), then an exact filtered scale and EXIF rotation. */
    private static Bitmap decodeSampled(Uri uri, int px) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = appContext.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        float s = scaleFor(o.outWidth, o.outHeight, px);
        int sample = 1;
        while (s * sample * 2 <= 1f) sample *= 2;     // largest power of two that still keeps >= the target size
        o.inSampleSize = sample;
        o.inJustDecodeBounds = false;
        Bitmap b;
        try (InputStream in = appContext.getContentResolver().openInputStream(uri)) {
            b = BitmapFactory.decodeStream(in, null, o);
        }
        if (b == null) return null;
        int tw = Math.max(1, Math.round(o.outWidth * s));
        int th = Math.max(1, Math.round(o.outHeight * s));
        if (b.getWidth() > tw * 1.05f || b.getHeight() > th * 1.05f) {
            Bitmap scaled = Bitmap.createScaledBitmap(b, tw, th, true);
            if (scaled != b) b.recycle();
            b = scaled;
        }
        int degrees = exifDegrees(uri);
        if (degrees != 0) {
            Matrix m = new Matrix();
            m.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (rotated != b) b.recycle();
            b = rotated;
        }
        return b;
    }

    private static int exifDegrees(Uri uri) {
        try (InputStream in = appContext.getContentResolver().openInputStream(uri)) {
            if (in == null) return 0;
            int o = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {
            // not a JPEG / no EXIF
        }
        return 0;
    }

    /** First frame of a video at the wanted size (the system thumbnail would be too small for big tiles). */
    private static Bitmap videoFrame(Uri uri, int px) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(appContext, uri);
            if (Build.VERSION.SDK_INT >= 27) {
                String ws = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
                String hs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
                int w = ws == null ? 0 : Integer.parseInt(ws);
                int h = hs == null ? 0 : Integer.parseInt(hs);
                float s = scaleFor(w, h, px);
                if (w > 0 && h > 0) {
                    Bitmap f = r.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
                    if (f != null) return f;
                }
            }
            Bitmap frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return frame == null ? null : scaleDown(frame, px);
        } catch (Exception e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static Bitmap scaleDown(Bitmap b, int px) {
        int max = Math.max(b.getWidth(), b.getHeight());
        if (max <= px * 2) return b;
        float s = (px * 2f) / max;
        return Bitmap.createScaledBitmap(b, Math.max(1, Math.round(b.getWidth() * s)),
                Math.max(1, Math.round(b.getHeight() * s)), true);
    }

    private static int sampleSize(int w, int h, int px) {
        int sample = 1;
        if (w <= 0 || h <= 0 || px <= 0) return 1;
        while (w / (sample * 2) >= px && h / (sample * 2) >= px) sample *= 2;
        return sample;
    }

    private static Bitmap decodeFile(File f, int px) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = sampleSize(o.outWidth, o.outHeight, px);
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private static String hash(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static Bitmap loadRemote(String url, int px) throws Exception {
        File f = new File(diskDir, hash(url));
        if (!f.exists() || f.length() == 0) {
            HttpURLConnection c = Http.open(url, null, Http.CONNECT_TIMEOUT, Http.READ_TIMEOUT);
            try {
                if (c.getResponseCode() >= 400) return null;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    int total = 0;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > 8 * 1024 * 1024) return null;
                        bos.write(buf, 0, n);
                    }
                }
                File tmp = new File(diskDir, f.getName() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(bos.toByteArray());
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.renameTo(f);
            } finally {
                c.disconnect();
            }
        }
        return decodeFile(f, px);
    }

    /** Frees thumbnails and cached remote images. */
    public static void clearAll() {
        if (cache != null) cache.evictAll();
        File[] files = diskDir == null ? null : diskDir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    @SuppressWarnings("unused")
    private static void closeQuietly(FileInputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // ignore
        }
    }
}
