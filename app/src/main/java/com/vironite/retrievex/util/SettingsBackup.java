package com.vironite.retrievex.util;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps a small copy of the settings in Documents/Retrieve-X/settings.json (no secrets: the X session is never
 * included) and loads it back when the app starts with no settings of its own, e.g. after it was uninstalled and
 * installed again. Everything here is best effort: if the file can't be read or written, the app simply runs on
 * its normal preferences.
 */
public final class SettingsBackup {
    private static final String NAME = "settings.json";
    private static final String DIR_NAME = "Retrieve-X";
    private static final String RELATIVE = "Documents/" + DIR_NAME + "/";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long DELAY_MS = 1500;

    /** Not copied: tied to this installation (folder grant, cached values, session state). */
    private static final Set<String> SKIP = new HashSet<>();

    static {
        SKIP.add(Prefs.DOWNLOAD_TREE);
        SKIP.add(Prefs.X_SESSION_EXPIRED);
        SKIP.add(Prefs.X_QUERY_ID);
        SKIP.add(Prefs.X_QUERY_ID_TIME);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Runnable SAVE = new Runnable() {
        @Override
        public void run() {
            IO.execute(new Runnable() {
                @Override
                public void run() {
                    saveNow();
                }
            });
        }
    };

    private static Context app;

    private SettingsBackup() {
    }

    static void init(Context c) {
        app = c.getApplicationContext();
    }

    /** Called after every settings write. Skips values that are not user settings. */
    static void changed(String key) {
        if (app == null || SKIP.contains(key)) return;
        MAIN.removeCallbacks(SAVE);
        MAIN.postDelayed(SAVE, DELAY_MS);
    }

    /**
     * Call once at startup, before the settings are used. When this installation has no user settings yet and a saved
     * copy exists, the copy is loaded.
     */
    public static void restoreIfFresh() {
        SharedPreferences sp = Prefs.store();
        if (app == null || sp == null) return;
        for (String k : sp.getAll().keySet()) {
            if (!SKIP.contains(k)) return;      // already has settings: never overwrite them
        }
        try {
            byte[] data = read();
            if (data == null || data.length == 0) return;
            JSONObject o = new JSONObject(new String(data, UTF8));
            SharedPreferences.Editor e = sp.edit();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                if (SKIP.contains(k)) continue;
                Object v = o.get(k);
                if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
                else if (v instanceof Integer) e.putInt(k, (Integer) v);
                else if (v instanceof Long) e.putLong(k, (Long) v);
                else if (v instanceof String) e.putString(k, (String) v);
            }
            e.commit();
        } catch (Exception ignored) {
            // no usable copy
        }
    }

    // ------------------------------------------------------------------ write

    private static void saveNow() {
        SharedPreferences sp = Prefs.store();
        if (app == null || sp == null) return;
        try {
            JSONObject o = new JSONObject();
            for (Map.Entry<String, ?> en : sp.getAll().entrySet()) {
                if (SKIP.contains(en.getKey())) continue;
                Object v = en.getValue();
                if (v instanceof Boolean || v instanceof Integer || v instanceof Long || v instanceof String) {
                    o.put(en.getKey(), v);
                }
            }
            byte[] data = o.toString().getBytes(UTF8);
            if (Build.VERSION.SDK_INT >= 29) writeMediaStore(data);
            else writeLegacy(data);
        } catch (Exception ignored) {
            // best effort
        }
    }

    private static Uri findMediaStore(ContentResolver cr, Uri collection) {
        try (Cursor c = cr.query(collection, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{RELATIVE + "%", NAME}, null)) {
            if (c != null && c.moveToFirst()) return android.content.ContentUris.withAppendedId(collection, c.getLong(0));
        } catch (Exception ignored) {
            // treat as not found
        }
        return null;
    }

    private static void writeMediaStore(byte[] data) throws Exception {
        ContentResolver cr = app.getContentResolver();
        Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = findMediaStore(cr, collection);
        if (uri == null) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE);
            uri = cr.insert(collection, v);
        }
        if (uri == null) return;
        try (OutputStream out = cr.openOutputStream(uri, "wt")) {
            if (out != null) out.write(data);
        }
    }

    private static File legacyFile() {
        return new File(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), DIR_NAME), NAME);
    }

    private static boolean legacyAllowed() {
        return ContextCompat.checkSelfPermission(app, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void writeLegacy(byte[] data) throws Exception {
        if (!legacyAllowed()) return;
        File f = legacyFile();
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    // ------------------------------------------------------------------ read

    private static byte[] read() throws Exception {
        InputStream in = null;
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = app.getContentResolver();
                Uri uri = findMediaStore(cr, MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY));
                if (uri == null) return null;
                in = cr.openInputStream(uri);
            } else {
                if (!legacyAllowed()) return null;
                File f = legacyFile();
                if (!f.isFile()) return null;
                in = new FileInputStream(f);
            }
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 256 * 1024) return null;     // not ours
            }
            return bos.toByteArray();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }
}
