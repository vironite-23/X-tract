package com.vironite.retrievex.util;

import android.content.Context;
import android.content.SharedPreferences;

/** Thin typed wrapper around SharedPreferences holding every user setting. */
public final class Prefs {
    private static SharedPreferences sp;

    // Download settings
    public static final String QUALITY = "quality";                 // best|original|smallest|1080|720|480|360
    public static final String IMAGE_SELECT = "image_select";       // all|none
    public static final String IMAGE_FORMAT = "image_format";       // original|jpg|png|webp
    public static final String DOWNLOAD_TREE = "download_tree";     // SAF tree uri or empty
    public static final String WIFI_ONLY = "wifi_only";
    public static final String MAX_CONCURRENT = "max_concurrent";
    public static final String AUTO_MERGE = "auto_merge";
    public static final String AUTO_CLEANUP = "auto_cleanup";
    public static final String NAME_TEMPLATE = "name_template";
    public static final String DUPLICATE_MODE = "duplicate_mode";   // rename|skip
    public static final String FALLBACK_SERVICE = "fallback_service";

    // X sign-in (non-secret flags only; the session itself lives in auth.SecureStore)
    public static final String X_USE_ACCOUNT = "x_use_account";           // use the signed-in account for restricted posts
    public static final String X_SESSION_EXPIRED = "x_session_expired";   // last session was rejected by X
    public static final String X_QUERY_ID = "x_query_id";                 // cached GraphQL operation id (public value)
    public static final String X_QUERY_ID_TIME = "x_query_id_time";

    // Library settings
    public static final String MEDIA_VIEW = "media_view";           // grid|list|detail
    public static final String MEDIA_SORT = "media_sort";
    public static final String MEDIA_FILTER = "media_filter";
    public static final String ACC_MEDIA_SORT = "acc_media_sort";
    public static final String ACC_MEDIA_FILTER = "acc_media_filter";
    public static final String ACCOUNT_SORT = "account_sort";
    public static final String THUMB_SIZE = "thumb_size";           // small|medium|large
    public static final String SHOW_ACCOUNT = "show_account";
    public static final String SHOW_SIZE = "show_size";

    // Video player
    public static final String REMEMBER_BRIGHTNESS = "remember_brightness";
    public static final String BRIGHTNESS_LEVEL = "brightness_level";     // 1..100, 0 = never set
    public static final String AUTO_MUTE = "auto_mute";

    // Appearance
    public static final String THEME = "theme";                     // system|light|dark
    public static final String GRID_COLUMNS = "grid_columns";       // auto|2|3|4
    public static final String LIST_DENSITY = "list_density";       // comfortable|compact

    public static final String DEFAULT_TEMPLATE = "{username}_{postid}_{index}";

    private Prefs() {
    }

    public static void init(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("retrievex_prefs", Context.MODE_PRIVATE);
        SettingsBackup.init(c);
    }

    public static String getString(String key, String def) {
        return sp.getString(key, def);
    }

    // Every write is synchronous (commit) so a setting is on disk before the call returns and survives the app
    // being killed right afterwards, e.g. by an update. Values are tiny, so this costs nothing noticeable.
    public static void putString(String key, String value) {
        sp.edit().putString(key, value).commit();
        SettingsBackup.changed(key);
    }

    public static boolean getBool(String key, boolean def) {
        return sp.getBoolean(key, def);
    }

    public static void putBool(String key, boolean value) {
        sp.edit().putBoolean(key, value).commit();
        SettingsBackup.changed(key);
    }

    /** Same as {@link #putBool}; kept for callers that want to stress that the write is synchronous. */
    public static void putBoolNow(String key, boolean value) {
        putBool(key, value);
    }

    public static int getInt(String key, int def) {
        return sp.getInt(key, def);
    }

    public static void putInt(String key, int value) {
        sp.edit().putInt(key, value).commit();
        SettingsBackup.changed(key);
    }

    public static long getLong(String key, long def) {
        return sp.getLong(key, def);
    }

    public static void putLong(String key, long value) {
        sp.edit().putLong(key, value).commit();
        SettingsBackup.changed(key);
    }

    public static void remove(String key) {
        sp.edit().remove(key).commit();
        SettingsBackup.changed(key);
    }

    /** The underlying store, for {@link SettingsBackup}. */
    static SharedPreferences store() {
        return sp;
    }

    public static String template() {
        String t = getString(NAME_TEMPLATE, DEFAULT_TEMPLATE);
        return (t == null || t.trim().isEmpty()) ? DEFAULT_TEMPLATE : t;
    }

    public static int maxConcurrent() {
        int v = getInt(MAX_CONCURRENT, 2);
        return Math.max(1, Math.min(5, v));
    }
}
