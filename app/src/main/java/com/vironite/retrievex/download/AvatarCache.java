package com.vironite.retrievex.download;

import android.content.Context;

import com.vironite.retrievex.data.Repo;
import com.vironite.retrievex.extract.Http;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

/** Stores account avatars in app-private storage so the Accounts library works offline. */
public final class AvatarCache {
    private AvatarCache() {
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "avatars");
        if (!d.exists()) //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        return d;
    }

    /** Downloads the avatar and records its path on the account. Best effort; failures are ignored. */
    public static void cache(Context c, long accountId, String url) {
        if (accountId <= 0 || url == null || !url.startsWith("https://")) return;
        File dst = new File(dir(c), accountId + ".jpg");
        File tmp = new File(dir(c), accountId + ".tmp");
        HttpURLConnection conn = null;
        try {
            conn = Http.open(url, null, Http.CONNECT_TIMEOUT, Http.READ_TIMEOUT);
            if (conn.getResponseCode() >= 400) return;
            long len = conn.getContentLengthLong();
            if (len > 5L * 1024 * 1024) return;
            try (InputStream in = conn.getInputStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 5L * 1024 * 1024) throw new IOException("avatar too large");
                    out.write(buf, 0, n);
                }
            }
            if (dst.exists() && !dst.delete()) return;
            if (tmp.renameTo(dst)) {
                Repo.get().setCachedAvatar(accountId, dst.getPath());
            }
        } catch (Exception ignored) {
            // avatars are optional
        } finally {
            if (conn != null) conn.disconnect();
            if (tmp.exists()) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }
}
