package com.vironite.retrievex.storage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import com.vironite.retrievex.data.MediaItem;
import com.vironite.retrievex.util.Prefs;
import com.vironite.retrievex.util.TextUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Temp files, publishing to MediaStore / a user-chosen SAF folder, and file operations on stored media. */
public final class FileStore {
    public static final String APP_DIR = "Retrieve-X";

    public static class DuplicateException extends IOException {
        public DuplicateException(String name) {
            super("File already exists: " + name);
        }
    }

    public static final class Published {
        public Uri uri;
        public String name;
        public long size;
    }

    private FileStore() {
    }

    // ------------------------------------------------------------------ temp storage

    public static File tempDir(Context c) {
        File base = c.getExternalFilesDir("tmp");
        if (base == null) base = new File(c.getFilesDir(), "tmp");
        if (!base.exists()) //noinspection ResultOfMethodCallIgnored
            base.mkdirs();
        return base;
    }

    public static long tempUsage(Context c) {
        long total = 0;
        File[] files = tempDir(c).listFiles();
        if (files != null) for (File f : files) total += f.length();
        return total;
    }

    public static long freeSpace(File dir) {
        try {
            StatFs s = new StatFs(dir.getPath());
            return s.getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /** Deletes temp files that are not in {@code keep}. Returns the number of bytes freed. */
    public static long clearTemp(Context c, Set<String> keep) {
        long freed = 0;
        File[] files = tempDir(c).listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (keep != null && keep.contains(f.getPath())) continue;
            long len = f.length();
            if (f.delete()) freed += len;
        }
        return freed;
    }

    // ------------------------------------------------------------------ destination

    public static Uri customTree() {
        String s = Prefs.getString(Prefs.DOWNLOAD_TREE, "");
        return TextUtil.isEmpty(s) ? null : Uri.parse(s);
    }

    public static String destinationLabel(Context c) {
        Uri tree = customTree();
        if (tree != null) {
            try {
                String id = DocumentsContract.getTreeDocumentId(tree);
                int colon = id.indexOf(':');
                String path = colon >= 0 ? id.substring(colon + 1) : id;
                return path.isEmpty() ? "Selected folder" : path;
            } catch (Exception e) {
                return "Selected folder";
            }
        }
        return "Movies/" + APP_DIR + " \u00B7 Pictures/" + APP_DIR;
    }

    private static boolean isVideo(String mediaType) {
        return !MediaItem.TYPE_IMAGE.equals(mediaType);
    }

    private static String splitBase(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String splitExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    private static String numbered(String name, int n) {
        return splitBase(name) + " (" + n + ")" + splitExt(name);
    }

    // ------------------------------------------------------------------ publish

    /**
     * Copies a finished temp file into its final public location.
     *
     * @param skipIfExists when true, throws {@link DuplicateException} instead of creating "name (1)"
     */
    public static Published publish(Context c, File src, String name, String mime, String mediaType,
                                    boolean skipIfExists) throws IOException {
        Uri tree = customTree();
        if (tree != null) return publishToTree(c, tree, src, name, mime, skipIfExists);
        if (Build.VERSION.SDK_INT >= 29) return publishMediaStore(c, src, name, mime, mediaType, skipIfExists);
        return publishLegacy(c, src, name, mime, mediaType, skipIfExists);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    private static Published publishMediaStore(Context c, File src, String name, String mime, String mediaType,
                                               boolean skipIfExists) throws IOException {
        ContentResolver r = c.getContentResolver();
        boolean video = isVideo(mediaType);
        String relDir = (video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES) + "/" + APP_DIR;
        Uri collection = video ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        if (skipIfExists && mediaStoreHasName(r, collection, relDir + "/", name)) throw new DuplicateException(name);

        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relDir);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = r.insert(collection, v);
        if (uri == null) throw new IOException("Could not create the file in MediaStore");
        try {
            OutputStream out = r.openOutputStream(uri, "w");
            if (out == null) throw new IOException("Could not open the destination file");
            try (InputStream in = new FileInputStream(src)) {
                copy(in, out);
            } finally {
                out.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            r.update(uri, done, null, null);
        } catch (IOException | RuntimeException e) {
            try {
                r.delete(uri, null, null);
            } catch (Exception ignored) {
                // nothing more to do
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        Published p = new Published();
        p.uri = uri;
        p.name = queryName(r, uri, name);
        p.size = src.length();
        return p;
    }

    private static boolean mediaStoreHasName(ContentResolver r, Uri collection, String relPath, String name) {
        Cursor c = null;
        try {
            c = r.query(collection, new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND " + MediaStore.MediaColumns.RELATIVE_PATH + " = ?",
                    new String[]{name, relPath}, null);
            return c != null && c.moveToFirst();
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.close();
        }
    }

    private static String queryName(ContentResolver r, Uri uri, String fallback) {
        Cursor c = null;
        try {
            c = r.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (!TextUtil.isEmpty(n)) return n;
            }
        } catch (Exception ignored) {
            // use fallback
        } finally {
            if (c != null) c.close();
        }
        return fallback;
    }

    @SuppressWarnings("deprecation")
    private static Published publishLegacy(Context c, File src, String name, String mime, String mediaType,
                                           boolean skipIfExists) throws IOException {
        boolean video = isVideo(mediaType);
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), APP_DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new SecurityException("Cannot create " + dir.getName());
        File dst = new File(dir, name);
        if (dst.exists()) {
            if (skipIfExists) throw new DuplicateException(name);
            int n = 1;
            while (dst.exists()) dst = new File(dir, numbered(name, n++));
        }
        try (InputStream in = new FileInputStream(src); java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            copy(in, out);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            throw e;
        }
        final Uri[] result = new Uri[1];
        final CountDownLatch latch = new CountDownLatch(1);
        MediaScannerConnection.scanFile(c, new String[]{dst.getPath()}, new String[]{mime},
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        result[0] = uri;
                        latch.countDown();
                    }
                });
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Published p = new Published();
        p.uri = result[0] != null ? result[0] : Uri.fromFile(dst);
        p.name = dst.getName();
        p.size = dst.length();
        return p;
    }

    private static Set<String> childNames(ContentResolver r, Uri tree, Uri parent) {
        Set<String> names = new HashSet<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        Cursor c = null;
        try {
            c = r.query(children, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            while (c != null && c.moveToNext()) names.add(c.getString(0));
        } catch (Exception ignored) {
            // treat as empty
        } finally {
            if (c != null) c.close();
        }
        return names;
    }

    private static Published publishToTree(Context c, Uri tree, File src, String name, String mime,
                                           boolean skipIfExists) throws IOException {
        ContentResolver r = c.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Set<String> existing = childNames(r, tree, parent);
        String finalName = name;
        if (existing.contains(name)) {
            if (skipIfExists) throw new DuplicateException(name);
            int n = 1;
            while (existing.contains(numbered(name, n))) n++;
            finalName = numbered(name, n);
        }
        Uri doc;
        try {
            doc = DocumentsContract.createDocument(r, parent, mime, finalName);
        } catch (SecurityException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not create the file in the selected folder", e);
        }
        if (doc == null) throw new IOException("Could not create the file in the selected folder");
        try {
            OutputStream out = r.openOutputStream(doc, "w");
            if (out == null) throw new IOException("Could not open the destination file");
            try (InputStream in = new FileInputStream(src)) {
                copy(in, out);
            } finally {
                out.close();
            }
        } catch (IOException e) {
            try {
                DocumentsContract.deleteDocument(r, doc);
            } catch (Exception ignored) {
                // nothing more to do
            }
            throw e;
        }
        Published p = new Published();
        p.uri = doc;
        p.name = queryDocName(r, doc, finalName);
        p.size = src.length();
        return p;
    }

    private static String queryDocName(ContentResolver r, Uri doc, String fallback) {
        Cursor c = null;
        try {
            c = r.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (!TextUtil.isEmpty(n)) return n;
            }
        } catch (Exception ignored) {
            // fallback
        } finally {
            if (c != null) c.close();
        }
        return fallback;
    }

    // ------------------------------------------------------------------ operations on stored files

    /** Returns a {@link MediaItem} availability constant for the given file URI. */
    public static int availability(Context c, Uri uri) {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = c.getContentResolver().openFileDescriptor(uri, "r");
            return pfd != null ? MediaItem.AVAIL_OK : MediaItem.AVAIL_MISSING;
        } catch (FileNotFoundException e) {
            return MediaItem.AVAIL_MISSING;
        } catch (SecurityException e) {
            return MediaItem.AVAIL_INACCESSIBLE;
        } catch (Exception e) {
            return MediaItem.AVAIL_INACCESSIBLE;
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    /** Deletes a stored file. Throws SecurityException when the user must grant access first. */
    public static boolean delete(Context c, Uri uri) {
        ContentResolver r = c.getContentResolver();
        if (DocumentsContract.isDocumentUri(c, uri)) {
            try {
                return DocumentsContract.deleteDocument(r, uri);
            } catch (FileNotFoundException e) {
                return true;
            }
        }
        if ("file".equals(uri.getScheme())) {
            File f = new File(uri.getPath());
            return !f.exists() || f.delete();
        }
        return r.delete(uri, null, null) > 0;
    }

    /** Renames a stored file and returns its (possibly new) URI, or null when the rename failed. */
    public static Uri rename(Context c, Uri uri, String newName) {
        ContentResolver r = c.getContentResolver();
        try {
            if (DocumentsContract.isDocumentUri(c, uri)) {
                return DocumentsContract.renameDocument(r, uri, newName);
            }
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);
            return r.update(uri, v, null, null) > 0 ? uri : null;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    public static long size(Context c, Uri uri) {
        ParcelFileDescriptor pfd = null;
        try {
            pfd = c.getContentResolver().openFileDescriptor(uri, "r");
            return pfd != null ? pfd.getStatSize() : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                    // ignore
                }
            }
        }
    }

    /** Intent that tries to show the folder containing downloads in the system file manager. */
    public static Intent folderIntent(Context c) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        Uri tree = customTree();
        if (tree != null) {
            Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            i.setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR);
        } else {
            Uri doc = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
                    "primary:Movies/" + APP_DIR);
            i.setDataAndType(doc, DocumentsContract.Document.MIME_TYPE_DIR);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
