package com.vironite.retrievex.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite helper. Upgrades must never destroy user data: add new migration steps to
 * {@link #onUpgrade(SQLiteDatabase, int, int)} instead of recreating tables.
 */
public class Database extends SQLiteOpenHelper {
    public static final String NAME = "retrievex.db";
    public static final int VERSION = 1;

    public Database(Context c) {
        super(c.getApplicationContext(), NAME, null, VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE accounts ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "restId TEXT,"
                + "username TEXT NOT NULL,"
                + "usernameLower TEXT NOT NULL UNIQUE,"
                + "displayName TEXT,"
                + "profileUrl TEXT,"
                + "avatarUrl TEXT,"
                + "cachedAvatarPath TEXT,"
                + "createdAt INTEGER NOT NULL DEFAULT 0,"
                + "firstDownloadedAt INTEGER NOT NULL DEFAULT 0,"
                + "lastDownloadedAt INTEGER NOT NULL DEFAULT 0,"
                + "customLabel TEXT,"
                + "hidden INTEGER NOT NULL DEFAULT 0,"
                + "metaUpdatedAt INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_acc_rest ON accounts(restId)");
        db.execSQL("CREATE INDEX idx_acc_last ON accounts(lastDownloadedAt)");
        db.execSQL("CREATE INDEX idx_acc_created ON accounts(createdAt)");

        db.execSQL("CREATE TABLE media ("
                + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "postId TEXT,"
                + "postUrl TEXT,"
                + "accountId INTEGER NOT NULL DEFAULT 0,"
                + "filename TEXT NOT NULL,"
                + "fileUri TEXT NOT NULL UNIQUE,"
                + "mimeType TEXT,"
                + "mediaType TEXT NOT NULL,"
                + "width INTEGER NOT NULL DEFAULT 0,"
                + "height INTEGER NOT NULL DEFAULT 0,"
                + "duration INTEGER NOT NULL DEFAULT 0,"
                + "fileSize INTEGER NOT NULL DEFAULT 0,"
                + "videoCodec TEXT,"
                + "audioCodec TEXT,"
                + "frameRate REAL NOT NULL DEFAULT 0,"
                + "hasAudio INTEGER NOT NULL DEFAULT -1,"
                + "downloadedAt INTEGER NOT NULL,"
                + "sourceMetadata TEXT,"
                + "postText TEXT,"
                + "availability INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_media_downloaded ON media(downloadedAt)");
        db.execSQL("CREATE INDEX idx_media_name ON media(filename COLLATE NOCASE)");
        db.execSQL("CREATE INDEX idx_media_account ON media(accountId, downloadedAt)");
        db.execSQL("CREATE INDEX idx_media_type ON media(mediaType)");
        db.execSQL("CREATE INDEX idx_media_size ON media(fileSize)");
        db.execSQL("CREATE INDEX idx_media_height ON media(height)");
        db.execSQL("CREATE INDEX idx_media_post ON media(postId)");

        db.execSQL("CREATE TABLE tasks ("
                + "id TEXT PRIMARY KEY,"
                + "postUrl TEXT,"
                + "postId TEXT,"
                + "accountRestId TEXT,"
                + "accountUsername TEXT,"
                + "accountName TEXT,"
                + "accountAvatar TEXT,"
                + "mediaIndex INTEGER NOT NULL DEFAULT 0,"
                + "mediaType TEXT,"
                + "thumbUrl TEXT,"
                + "formatId TEXT,"
                + "formatJson TEXT,"
                + "targetName TEXT,"
                + "mimeType TEXT,"
                + "destinationUri TEXT,"
                + "tempPath TEXT,"
                + "tempPath2 TEXT,"
                + "status INTEGER NOT NULL DEFAULT 0,"
                + "bytesDownloaded INTEGER NOT NULL DEFAULT 0,"
                + "totalBytes INTEGER NOT NULL DEFAULT -1,"
                + "createdAt INTEGER NOT NULL DEFAULT 0,"
                + "startedAt INTEGER NOT NULL DEFAULT 0,"
                + "completedAt INTEGER NOT NULL DEFAULT 0,"
                + "errorMessage TEXT,"
                + "errorCode TEXT,"
                + "mediaItemId INTEGER NOT NULL DEFAULT 0,"
                + "postText TEXT,"
                + "postDate INTEGER NOT NULL DEFAULT 0,"
                + "duration INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_tasks_created ON tasks(createdAt)");
        db.execSQL("CREATE INDEX idx_tasks_status ON tasks(status)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 is the first schema. Future migrations go here as incremental steps, e.g.:
        // if (oldVersion < 2) { db.execSQL("ALTER TABLE media ADD COLUMN ..."); }
        // Never drop user tables during an upgrade.
    }
}
