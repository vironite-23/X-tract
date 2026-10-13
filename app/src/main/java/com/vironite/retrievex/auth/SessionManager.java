package com.vironite.retrievex.auth;

import android.content.Context;

import com.vironite.retrievex.util.Prefs;

/**
 * Single entry point for the signed-in X session. The session is kept encrypted at rest
 * ({@link SecureStore}) and cached in memory only while the app process is alive.
 */
public final class SessionManager {
    private static final String KEY = "x_session";

    private static SecureStore store;
    private static Session cached;
    private static boolean loaded;
    private static boolean unavailable;   // a saved session exists but the Keystore couldn't unlock it yet

    private SessionManager() {
    }

    public static synchronized void init(Context c) {
        store = new SecureStore(c);
        cached = null;
        loaded = false;
        unavailable = false;
    }

    /** The current session, or null when signed out (or while the saved one can't be unlocked yet, see {@link #isUnavailable()}). */
    public static synchronized Session get() {
        if (!loaded) {
            String json = store == null ? null : store.get(KEY);
            if (json == null && store != null && store.lastReadWasTemporary()) {
                // Keystore hiccup (typically right after an app update): keep the saved session and try again later.
                unavailable = true;
                return null;
            }
            unavailable = false;
            if (json == null && store != null && store.lastReadWasLost()) {
                // The saved session could not be recovered; say so in Settings instead of silently signing out.
                Prefs.putBool(Prefs.X_SESSION_EXPIRED, true);
            }
            cached = json == null ? null : Session.fromJson(json);
            if (json != null && cached == null) {
                store.remove(KEY);
                Prefs.putBool(Prefs.X_SESSION_EXPIRED, true);
            }
            loaded = true;
        }
        return cached;
    }

    /** True while a saved session exists that couldn't be unlocked yet. It is retried automatically on the next use. */
    public static synchronized boolean isUnavailable() {
        get();
        return unavailable;
    }

    /** Tries again to unlock the saved session now. Call off the main thread when possible. */
    public static synchronized void retry() {
        if (!loaded) get();
    }

    /**
     * Unlocks the saved session in the background right after launch, retrying a few times, so a Keystore that is
     * still starting up after an app update doesn't make the user look signed out.
     */
    public static void warmUp() {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 6; i++) {
                retry();
                if (isLoaded()) return;
                try {
                    Thread.sleep(500L * (i + 1));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "session-warmup");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized boolean isLoaded() {
        return loaded;
    }

    public static boolean isSignedIn() {
        return get() != null;
    }

    /** Persists a verified session. Returns false (and keeps the user signed out) if it could not be encrypted. */
    public static synchronized boolean save(Session s) {
        if (store == null || !store.put(KEY, s.toJson())) {
            cached = null;
            loaded = true;
            return false;
        }
        cached = s;
        loaded = true;
        Prefs.putBool(Prefs.X_SESSION_EXPIRED, false);
        return true;
    }

    /** X rotates the ct0 (CSRF) cookie from time to time; keep the stored copy current. */
    public static synchronized void updateCt0(String newCt0) {
        Session s = get();
        if (s == null || !Session.validCt0(newCt0) || newCt0.equals(s.ct0)) return;
        save(s.withCt0(newCt0));
    }

    /** Wipes the session and its encryption key. Returns the removed session (for a server-side logout) or null. */
    public static synchronized Session signOut() {
        Session old = get();
        cached = null;
        loaded = true;
        unavailable = false;
        if (store != null) store.clear();
        return old;
    }

    /** Called when X rejects the session: wipe it and remember that the user needs to sign in again. */
    public static void expire() {
        signOut();
        Prefs.putBool(Prefs.X_SESSION_EXPIRED, true);
    }

    public static boolean wasExpired() {
        return Prefs.getBool(Prefs.X_SESSION_EXPIRED, false);
    }

    /**
     * Best-effort server-side logout so a copied token would stop working. Never throws; call off the main thread.
     */
    public static void revokeRemotely(Session s) {
        if (s == null) return;
        try {
            AuthHttp.request("POST", "https://x.com/i/api/1.1/account/logout.json", s,
                    "redirect_after_logout=https%3A%2F%2Fx.com%2Faccount%2Fswitch", 64 * 1024, 6000);
        } catch (Exception ignored) {
            // The local session is already gone; nothing else to do.
        }
    }
}
