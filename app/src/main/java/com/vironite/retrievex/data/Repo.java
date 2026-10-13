package com.vironite.retrievex.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;

import com.vironite.retrievex.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central data access object. All methods are synchronous and must be called off the main thread.
 * Change listeners are always invoked on the main thread.
 */
public final class Repo {
    public interface Listener {
        void onChanged();
    }

    public static final class Stats {
        public int fileCount;
        public long totalSize;
        public long videoSize;
        public long imageSize;
    }

    // Account sort keys
    public static final String ACC_ACTIVITY_DESC = "activity_desc";
    public static final String ACC_ACTIVITY_ASC = "activity_asc";
    public static final String ACC_NAME_ASC = "name_asc";
    public static final String ACC_NAME_DESC = "name_desc";
    public static final String ACC_COUNT_DESC = "count_desc";
    public static final String ACC_COUNT_ASC = "count_asc";
    public static final String ACC_SIZE_DESC = "size_desc";
    public static final String ACC_SIZE_ASC = "size_asc";
    public static final String ACC_VIDEOS_DESC = "videos_desc";
    public static final String ACC_IMAGES_DESC = "images_desc";
    public static final String ACC_ADDED_DESC = "added_desc";
    public static final String ACC_ADDED_ASC = "added_asc";

    private static Repo instance;

    public static synchronized void init(Context c) {
        if (instance == null) instance = new Repo(c.getApplicationContext());
    }

    public static Repo get() {
        return instance;
    }

    private final Database helper;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> mediaListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Listener> accountListeners = new CopyOnWriteArrayList<>();

    private Repo(Context c) {
        helper = new Database(c);
    }

    // ---------------------------------------------------------------- listeners

    public void addMediaListener(Listener l) {
        mediaListeners.addIfAbsent(l);
    }

    public void removeMediaListener(Listener l) {
        mediaListeners.remove(l);
    }

    public void addAccountListener(Listener l) {
        accountListeners.addIfAbsent(l);
    }

    public void removeAccountListener(Listener l) {
        accountListeners.remove(l);
    }

    private void fireMedia() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : mediaListeners) l.onChanged();
            }
        });
    }

    private void fireAccounts() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : accountListeners) l.onChanged();
            }
        });
    }

    // ---------------------------------------------------------------- accounts

    public static String normalizeUsername(String u) {
        if (u == null) return "";
        String s = u.trim();
        if (s.startsWith("@")) s = s.substring(1);
        return s;
    }

    /**
     * Finds or creates the account for a successful download and updates its activity timestamps.
     * Returns 0 when no account information is available (unknown account).
     */
    private long touchAccount(SQLiteDatabase db, String restId, String username, String displayName,
                              String avatarUrl, long now) {
        String uname = normalizeUsername(username);
        String rid = TextUtil.isEmpty(restId) ? null : restId.trim();
        if (uname.isEmpty() && rid == null) return 0;

        long id = 0;
        String existingRest = null;
        String existingUser = null;
        if (rid != null) {
            Cursor c = db.rawQuery("SELECT _id, restId, username FROM accounts WHERE restId = ? LIMIT 1", new String[]{rid});
            try {
                if (c.moveToFirst()) {
                    id = c.getLong(0);
                    existingRest = c.getString(1);
                    existingUser = c.getString(2);
                }
            } finally {
                c.close();
            }
        }
        if (id == 0 && !uname.isEmpty()) {
            Cursor c = db.rawQuery("SELECT _id, restId, username FROM accounts WHERE usernameLower = ? LIMIT 1",
                    new String[]{uname.toLowerCase(Locale.ROOT)});
            try {
                if (c.moveToFirst()) {
                    id = c.getLong(0);
                    existingRest = c.getString(1);
                    existingUser = c.getString(2);
                }
            } finally {
                c.close();
            }
        }

        if (id == 0) {
            if (uname.isEmpty()) return 0; // cannot create a record without a username; keep as unknown
            ContentValues v = new ContentValues();
            v.put("restId", rid);
            v.put("username", uname);
            v.put("usernameLower", uname.toLowerCase(Locale.ROOT));
            v.put("displayName", displayName);
            v.put("profileUrl", "https://x.com/" + uname);
            v.put("avatarUrl", avatarUrl);
            v.put("createdAt", now);
            v.put("firstDownloadedAt", now);
            v.put("lastDownloadedAt", now);
            v.put("hidden", 0);
            return db.insertOrThrow("accounts", null, v);
        }

        ContentValues v = new ContentValues();
        if (rid != null && TextUtil.isEmpty(existingRest)) v.put("restId", rid);
        if (!uname.isEmpty() && !uname.equals(existingUser)) {
            // A more reliable identifier may reveal a changed username; avoid colliding with another row.
            Cursor c = db.rawQuery("SELECT _id FROM accounts WHERE usernameLower = ? AND _id <> ? LIMIT 1",
                    new String[]{uname.toLowerCase(Locale.ROOT), String.valueOf(id)});
            boolean taken;
            try {
                taken = c.moveToFirst();
            } finally {
                c.close();
            }
            if (!taken) {
                v.put("username", uname);
                v.put("usernameLower", uname.toLowerCase(Locale.ROOT));
                v.put("profileUrl", "https://x.com/" + uname);
            }
        }
        if (!TextUtil.isEmpty(displayName)) v.put("displayName", displayName);
        if (!TextUtil.isEmpty(avatarUrl)) v.put("avatarUrl", avatarUrl);
        v.put("hidden", 0);
        v.put("lastDownloadedAt", now);
        db.execSQL("UPDATE accounts SET firstDownloadedAt = ? WHERE _id = ? AND firstDownloadedAt = 0",
                new Object[]{now, id});
        db.update("accounts", v, "_id = ?", new String[]{String.valueOf(id)});
        return id;
    }

    private static final String ACCOUNT_SELECT =
            "SELECT a._id, a.restId, a.username, a.displayName, a.profileUrl, a.avatarUrl, a.cachedAvatarPath,"
                    + " a.createdAt, a.firstDownloadedAt, a.lastDownloadedAt, a.customLabel, a.hidden, a.metaUpdatedAt,"
                    + " COALESCE(s.cnt, 0), COALESCE(s.vids, 0), COALESCE(s.imgs, 0), COALESCE(s.sz, 0)"
                    + " FROM accounts a LEFT JOIN ("
                    + "   SELECT accountId, COUNT(*) AS cnt,"
                    + "   SUM(CASE WHEN mediaType IN ('video','gif') THEN 1 ELSE 0 END) AS vids,"
                    + "   SUM(CASE WHEN mediaType = 'image' THEN 1 ELSE 0 END) AS imgs,"
                    + "   SUM(fileSize) AS sz FROM media GROUP BY accountId"
                    + " ) s ON s.accountId = a._id ";

    private static Account readAccount(Cursor c) {
        Account a = new Account();
        a.id = c.getLong(0);
        a.restId = c.getString(1);
        a.username = c.getString(2);
        a.displayName = c.getString(3);
        a.profileUrl = c.getString(4);
        a.avatarUrl = c.getString(5);
        a.cachedAvatarPath = c.getString(6);
        a.createdAt = c.getLong(7);
        a.firstDownloadedAt = c.getLong(8);
        a.lastDownloadedAt = c.getLong(9);
        a.customLabel = c.getString(10);
        a.hidden = c.getInt(11) != 0;
        a.metaUpdatedAt = c.getLong(12);
        a.mediaCount = c.getInt(13);
        a.videoCount = c.getInt(14);
        a.imageCount = c.getInt(15);
        a.totalSize = c.getLong(16);
        return a;
    }

    public Account getAccount(long id) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery(ACCOUNT_SELECT + "WHERE a._id = ?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readAccount(c) : null;
        } finally {
            c.close();
        }
    }

    public List<Account> queryAccounts(String search, String sort) {
        StringBuilder sql = new StringBuilder(ACCOUNT_SELECT);
        sql.append("WHERE a.hidden = 0");
        List<String> args = new ArrayList<>();
        if (!TextUtil.isEmpty(search)) {
            String like = "%" + TextUtil.escapeLike(search.trim()) + "%";
            sql.append(" AND (a.username LIKE ? ESCAPE '\\' OR a.displayName LIKE ? ESCAPE '\\'"
                    + " OR a.profileUrl LIKE ? ESCAPE '\\' OR a.customLabel LIKE ? ESCAPE '\\')");
            for (int i = 0; i < 4; i++) args.add(like);
        }
        sql.append(" ORDER BY ").append(accountOrder(sort));
        List<Account> out = new ArrayList<>();
        Cursor c = helper.getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]));
        try {
            while (c.moveToNext()) out.add(readAccount(c));
        } finally {
            c.close();
        }
        return out;
    }

    private static String accountOrder(String sort) {
        String tail = ", a.lastDownloadedAt DESC, a._id DESC";
        if (sort == null) sort = ACC_ACTIVITY_DESC;
        switch (sort) {
            case ACC_ACTIVITY_ASC:
                return "a.lastDownloadedAt ASC, a._id ASC";
            case ACC_NAME_ASC:
                return "a.usernameLower ASC" + tail;
            case ACC_NAME_DESC:
                return "a.usernameLower DESC" + tail;
            case ACC_COUNT_DESC:
                return "COALESCE(s.cnt, 0) DESC" + tail;
            case ACC_COUNT_ASC:
                return "COALESCE(s.cnt, 0) ASC" + tail;
            case ACC_SIZE_DESC:
                return "COALESCE(s.sz, 0) DESC" + tail;
            case ACC_SIZE_ASC:
                return "COALESCE(s.sz, 0) ASC" + tail;
            case ACC_VIDEOS_DESC:
                return "COALESCE(s.vids, 0) DESC" + tail;
            case ACC_IMAGES_DESC:
                return "COALESCE(s.imgs, 0) DESC" + tail;
            case ACC_ADDED_DESC:
                return "a.createdAt DESC, a._id DESC";
            case ACC_ADDED_ASC:
                return "a.createdAt ASC, a._id ASC";
            case ACC_ACTIVITY_DESC:
            default:
                return "a.lastDownloadedAt DESC, a._id DESC";
        }
    }

    public void setAccountLabel(long id, String label) {
        ContentValues v = new ContentValues();
        v.put("customLabel", TextUtil.isEmpty(label) ? null : label.trim());
        helper.getWritableDatabase().update("accounts", v, "_id = ?", new String[]{String.valueOf(id)});
        fireAccounts();
    }

    /** Removes an account from the Accounts library without touching its media. */
    public void hideAccount(long id) {
        ContentValues v = new ContentValues();
        v.put("hidden", 1);
        helper.getWritableDatabase().update("accounts", v, "_id = ?", new String[]{String.valueOf(id)});
        fireAccounts();
    }

    public void updateAccountMeta(long id, String displayName, String avatarUrl, String restId) {
        ContentValues v = new ContentValues();
        if (!TextUtil.isEmpty(displayName)) v.put("displayName", displayName);
        if (!TextUtil.isEmpty(avatarUrl)) v.put("avatarUrl", avatarUrl);
        if (!TextUtil.isEmpty(restId)) v.put("restId", restId);
        v.put("metaUpdatedAt", System.currentTimeMillis());
        helper.getWritableDatabase().update("accounts", v, "_id = ?", new String[]{String.valueOf(id)});
        fireAccounts();
    }

    public void setCachedAvatar(long id, String path) {
        ContentValues v = new ContentValues();
        v.put("cachedAvatarPath", path);
        helper.getWritableDatabase().update("accounts", v, "_id = ?", new String[]{String.valueOf(id)});
        fireAccounts();
    }

    // ---------------------------------------------------------------- media

    private static final String MEDIA_SELECT =
            "SELECT m._id, m.postId, m.postUrl, m.accountId, m.filename, m.fileUri, m.mimeType, m.mediaType,"
                    + " m.width, m.height, m.duration, m.fileSize, m.videoCodec, m.audioCodec, m.frameRate,"
                    + " m.hasAudio, m.downloadedAt, m.sourceMetadata, m.postText, m.availability,"
                    + " a.username, a.displayName, a.customLabel, a.cachedAvatarPath, a.avatarUrl"
                    + " FROM media m LEFT JOIN accounts a ON a._id = m.accountId ";

    private static MediaItem readMedia(Cursor c) {
        MediaItem m = new MediaItem();
        m.id = c.getLong(0);
        m.postId = c.getString(1);
        m.postUrl = c.getString(2);
        m.accountId = c.getLong(3);
        m.filename = c.getString(4);
        m.fileUri = c.getString(5);
        m.mimeType = c.getString(6);
        m.mediaType = c.getString(7);
        m.width = c.getInt(8);
        m.height = c.getInt(9);
        m.duration = c.getLong(10);
        m.fileSize = c.getLong(11);
        m.videoCodec = c.getString(12);
        m.audioCodec = c.getString(13);
        m.frameRate = c.getFloat(14);
        m.hasAudio = c.getInt(15);
        m.downloadedAt = c.getLong(16);
        m.sourceMetadata = c.getString(17);
        m.postText = c.getString(18);
        m.availability = c.getInt(19);
        m.accountUsername = c.getString(20);
        m.accountDisplayName = c.getString(21);
        m.accountCustomLabel = c.getString(22);
        m.accountAvatarPath = c.getString(23);
        m.accountAvatarUrl = c.getString(24);
        return m;
    }

    private static void appendWhere(StringBuilder sql, List<String> args, MediaQuery q) {
        List<String> conds = new ArrayList<>();
        if (q.accountId > 0) {
            conds.add("m.accountId = ?");
            args.add(String.valueOf(q.accountId));
        }
        if (!TextUtil.isEmpty(q.search)) {
            String like = "%" + TextUtil.escapeLike(q.search.trim()) + "%";
            conds.add("(m.filename LIKE ? ESCAPE '\\' OR a.username LIKE ? ESCAPE '\\'"
                    + " OR a.displayName LIKE ? ESCAPE '\\' OR a.customLabel LIKE ? ESCAPE '\\'"
                    + " OR m.postUrl LIKE ? ESCAPE '\\' OR m.mediaType LIKE ? ESCAPE '\\'"
                    + " OR m.postText LIKE ? ESCAPE '\\')");
            for (int i = 0; i < 7; i++) args.add(like);
        }
        String f = q.filter == null ? MediaQuery.FILTER_ALL : q.filter;
        switch (f) {
            case MediaQuery.FILTER_VIDEOS:
                conds.add("m.mediaType = 'video'");
                break;
            case MediaQuery.FILTER_IMAGES:
                conds.add("m.mediaType = 'image'");
                break;
            case MediaQuery.FILTER_GIFS:
                conds.add("m.mediaType = 'gif'");
                break;
            case MediaQuery.FILTER_AUDIO:
                conds.add("m.mediaType = 'video' AND m.hasAudio = 1");
                break;
            case MediaQuery.FILTER_NO_AUDIO:
                conds.add("m.mediaType = 'video' AND m.hasAudio = 0");
                break;
            default:
                break;
        }
        if (!conds.isEmpty()) {
            sql.append("WHERE ");
            for (int i = 0; i < conds.size(); i++) {
                if (i > 0) sql.append(" AND ");
                sql.append(conds.get(i));
            }
            sql.append(' ');
        }
    }

    private static String mediaOrder(String sort) {
        String tail = ", m.downloadedAt DESC, m._id DESC";
        if (sort == null) sort = MediaQuery.SORT_NEWEST;
        switch (sort) {
            case MediaQuery.SORT_OLDEST:
                return "m.downloadedAt ASC, m._id ASC";
            case MediaQuery.SORT_NAME_ASC:
                return "m.filename COLLATE NOCASE ASC" + tail;
            case MediaQuery.SORT_NAME_DESC:
                return "m.filename COLLATE NOCASE DESC" + tail;
            case MediaQuery.SORT_SIZE_DESC:
                return "m.fileSize DESC" + tail;
            case MediaQuery.SORT_SIZE_ASC:
                return "m.fileSize ASC" + tail;
            case MediaQuery.SORT_USER_ASC:
                return "(a.username IS NULL) ASC, a.username COLLATE NOCASE ASC" + tail;
            case MediaQuery.SORT_USER_DESC:
                return "(a.username IS NULL) ASC, a.username COLLATE NOCASE DESC" + tail;
            case MediaQuery.SORT_TYPE:
                return "m.mediaType ASC" + tail;
            case MediaQuery.SORT_RESOLUTION:
                return "m.height DESC, m.width DESC" + tail;
            case MediaQuery.SORT_NEWEST:
            default:
                return "m.downloadedAt DESC, m._id DESC";
        }
    }

    public List<MediaItem> queryMedia(MediaQuery q) {
        StringBuilder sql = new StringBuilder(MEDIA_SELECT);
        List<String> args = new ArrayList<>();
        appendWhere(sql, args, q);
        sql.append("ORDER BY ").append(mediaOrder(q.sort));
        sql.append(" LIMIT ").append(Math.max(1, q.limit)).append(" OFFSET ").append(Math.max(0, q.offset));
        List<MediaItem> out = new ArrayList<>();
        Cursor c = helper.getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]));
        try {
            while (c.moveToNext()) out.add(readMedia(c));
        } finally {
            c.close();
        }
        return out;
    }

    public int countMedia(MediaQuery q) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM media m LEFT JOIN accounts a ON a._id = m.accountId ");
        List<String> args = new ArrayList<>();
        appendWhere(sql, args, q);
        Cursor c = helper.getReadableDatabase().rawQuery(sql.toString(), args.toArray(new String[0]));
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public MediaItem getMedia(long id) {
        Cursor c = helper.getReadableDatabase().rawQuery(MEDIA_SELECT + "WHERE m._id = ?", new String[]{String.valueOf(id)});
        try {
            return c.moveToFirst() ? readMedia(c) : null;
        } finally {
            c.close();
        }
    }

    public List<MediaItem> getMediaByIds(List<Long> ids) {
        List<MediaItem> out = new ArrayList<>();
        for (Long id : ids) {
            MediaItem m = getMedia(id);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** Records a completed download. The account and media rows are updated in one transaction. */
    public long recordDownload(DownloadTask t, MediaItem m) {
        SQLiteDatabase db = helper.getWritableDatabase();
        long mediaId;
        long accountId;
        db.beginTransaction();
        try {
            long now = System.currentTimeMillis();
            accountId = touchAccount(db, t.accountRestId, t.accountUsername, t.accountName, t.accountAvatar, now);
            ContentValues v = new ContentValues();
            v.put("postId", m.postId);
            v.put("postUrl", m.postUrl);
            v.put("accountId", accountId);
            v.put("filename", m.filename);
            v.put("fileUri", m.fileUri);
            v.put("mimeType", m.mimeType);
            v.put("mediaType", m.mediaType);
            v.put("width", m.width);
            v.put("height", m.height);
            v.put("duration", m.duration);
            v.put("fileSize", m.fileSize);
            v.put("videoCodec", m.videoCodec);
            v.put("audioCodec", m.audioCodec);
            v.put("frameRate", m.frameRate);
            v.put("hasAudio", m.hasAudio);
            v.put("downloadedAt", now);
            v.put("sourceMetadata", m.sourceMetadata);
            v.put("postText", m.postText);
            v.put("availability", MediaItem.AVAIL_OK);
            mediaId = db.insertWithOnConflict("media", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        fireMedia();
        fireAccounts();
        return mediaId;
    }

    public long accountIdFor(String username) {
        String u = normalizeUsername(username);
        if (u.isEmpty()) return 0;
        Cursor c = helper.getReadableDatabase().rawQuery("SELECT _id FROM accounts WHERE usernameLower = ?",
                new String[]{u.toLowerCase(Locale.ROOT)});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    public void deleteMedia(List<Long> ids) {
        if (ids.isEmpty()) return;
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            for (Long id : ids) {
                db.delete("media", "_id = ?", new String[]{String.valueOf(id)});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        fireMedia();
        fireAccounts();
    }

    public void updateMediaName(long id, String filename, String fileUri) {
        ContentValues v = new ContentValues();
        v.put("filename", filename);
        if (fileUri != null) v.put("fileUri", fileUri);
        helper.getWritableDatabase().update("media", v, "_id = ?", new String[]{String.valueOf(id)});
        fireMedia();
    }

    /** Updates availability only when it actually changed; returns true if a row was updated. */
    public boolean setAvailability(long id, int status) {
        ContentValues v = new ContentValues();
        v.put("availability", status);
        int n = helper.getWritableDatabase().update("media", v, "_id = ? AND availability <> ?",
                new String[]{String.valueOf(id), String.valueOf(status)});
        return n > 0;
    }

    public List<MediaItem> allMedia(long afterId, int limit) {
        List<MediaItem> out = new ArrayList<>();
        Cursor c = helper.getReadableDatabase().rawQuery(
                MEDIA_SELECT + "WHERE m._id > ? ORDER BY m._id ASC LIMIT " + limit,
                new String[]{String.valueOf(afterId)});
        try {
            while (c.moveToNext()) out.add(readMedia(c));
        } finally {
            c.close();
        }
        return out;
    }

    public List<MediaItem> missingMedia() {
        List<MediaItem> out = new ArrayList<>();
        Cursor c = helper.getReadableDatabase().rawQuery(
                MEDIA_SELECT + "WHERE m.availability <> 0 ORDER BY m.filename COLLATE NOCASE ASC", null);
        try {
            while (c.moveToNext()) out.add(readMedia(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** Removes records whose files are known to be missing. Does not touch any files. */
    public int removeMissingRecords() {
        int n = helper.getWritableDatabase().delete("media", "availability = ?",
                new String[]{String.valueOf(MediaItem.AVAIL_MISSING)});
        if (n > 0) {
            fireMedia();
            fireAccounts();
        }
        return n;
    }

    public Stats stats() {
        Stats s = new Stats();
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(fileSize),0),"
                        + " COALESCE(SUM(CASE WHEN mediaType IN ('video','gif') THEN fileSize ELSE 0 END),0),"
                        + " COALESCE(SUM(CASE WHEN mediaType = 'image' THEN fileSize ELSE 0 END),0) FROM media", null);
        try {
            if (c.moveToFirst()) {
                s.fileCount = c.getInt(0);
                s.totalSize = c.getLong(1);
                s.videoSize = c.getLong(2);
                s.imageSize = c.getLong(3);
            }
        } finally {
            c.close();
        }
        return s;
    }

    // ---------------------------------------------------------------- tasks

    private static final String TASK_COLS = "id, postUrl, postId, accountRestId, accountUsername, accountName,"
            + " accountAvatar, mediaIndex, mediaType, thumbUrl, formatId, formatJson, targetName, mimeType,"
            + " destinationUri, tempPath, tempPath2, status, bytesDownloaded, totalBytes, createdAt, startedAt,"
            + " completedAt, errorMessage, errorCode, mediaItemId, postText, postDate, duration";

    private static DownloadTask readTask(Cursor c) {
        DownloadTask t = new DownloadTask();
        int i = 0;
        t.id = c.getString(i++);
        t.postUrl = c.getString(i++);
        t.postId = c.getString(i++);
        t.accountRestId = c.getString(i++);
        t.accountUsername = c.getString(i++);
        t.accountName = c.getString(i++);
        t.accountAvatar = c.getString(i++);
        t.mediaIndex = c.getInt(i++);
        t.mediaType = c.getString(i++);
        t.thumbUrl = c.getString(i++);
        t.formatId = c.getString(i++);
        t.formatJson = c.getString(i++);
        t.targetName = c.getString(i++);
        t.mimeType = c.getString(i++);
        t.destinationUri = c.getString(i++);
        t.tempPath = c.getString(i++);
        t.tempPath2 = c.getString(i++);
        t.status = c.getInt(i++);
        t.bytesDownloaded = c.getLong(i++);
        t.totalBytes = c.getLong(i++);
        t.createdAt = c.getLong(i++);
        t.startedAt = c.getLong(i++);
        t.completedAt = c.getLong(i++);
        t.errorMessage = c.getString(i++);
        t.errorCode = c.getString(i++);
        t.mediaItemId = c.getLong(i++);
        t.postText = c.getString(i++);
        t.postDate = c.getLong(i++);
        t.duration = c.getLong(i);
        return t;
    }

    public List<DownloadTask> listTasks() {
        List<DownloadTask> out = new ArrayList<>();
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT " + TASK_COLS + " FROM tasks ORDER BY createdAt ASC, rowid ASC", null);
        try {
            while (c.moveToNext()) out.add(readTask(c));
        } finally {
            c.close();
        }
        return out;
    }

    public void saveTask(DownloadTask t) {
        ContentValues v = new ContentValues();
        v.put("id", t.id);
        v.put("postUrl", t.postUrl);
        v.put("postId", t.postId);
        v.put("accountRestId", t.accountRestId);
        v.put("accountUsername", t.accountUsername);
        v.put("accountName", t.accountName);
        v.put("accountAvatar", t.accountAvatar);
        v.put("mediaIndex", t.mediaIndex);
        v.put("mediaType", t.mediaType);
        v.put("thumbUrl", t.thumbUrl);
        v.put("formatId", t.formatId);
        v.put("formatJson", t.formatJson);
        v.put("targetName", t.targetName);
        v.put("mimeType", t.mimeType);
        v.put("destinationUri", t.destinationUri);
        v.put("tempPath", t.tempPath);
        v.put("tempPath2", t.tempPath2);
        v.put("status", t.status);
        v.put("bytesDownloaded", t.bytesDownloaded);
        v.put("totalBytes", t.totalBytes);
        v.put("createdAt", t.createdAt);
        v.put("startedAt", t.startedAt);
        v.put("completedAt", t.completedAt);
        v.put("errorMessage", t.errorMessage);
        v.put("errorCode", t.errorCode);
        v.put("mediaItemId", t.mediaItemId);
        v.put("postText", t.postText);
        v.put("postDate", t.postDate);
        v.put("duration", t.duration);
        helper.getWritableDatabase().insertWithOnConflict("tasks", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void deleteTask(String id) {
        helper.getWritableDatabase().delete("tasks", "id = ?", new String[]{id});
    }

    /** Removes finished task rows (history only). Media files and library records are untouched. */
    public int clearFinishedTasks() {
        return helper.getWritableDatabase().delete("tasks", "status IN (?, ?, ?)",
                new String[]{String.valueOf(DownloadTask.COMPLETED), String.valueOf(DownloadTask.FAILED),
                        String.valueOf(DownloadTask.CANCELED)});
    }
}
