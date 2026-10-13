package com.vironite.retrievex.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.vironite.retrievex.MainActivity;
import com.vironite.retrievex.R;
import com.vironite.retrievex.data.DownloadTask;

/** Notification channels and builders for the download service. */
public final class Notifications {
    public static final String CH_PROGRESS = "downloads_progress";
    public static final String CH_DONE = "downloads_done";
    public static final int ID_SERVICE = 1001;

    private Notifications() {
    }

    public static void createChannels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel p = new NotificationChannel(CH_PROGRESS, c.getString(R.string.ch_progress), NotificationManager.IMPORTANCE_LOW);
        p.setShowBadge(false);
        nm.createNotificationChannel(p);
        NotificationChannel d = new NotificationChannel(CH_DONE, c.getString(R.string.ch_done), NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(d);
    }

    private static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.putExtra(MainActivity.EXTRA_OPEN_TAB, "downloads");
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static Notification progress(Context c, int active, int waiting, int percent, String title) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CH_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentIntent(openApp(c))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);
        if (waiting > 0) b.setContentText(c.getString(R.string.notif_waiting, waiting));
        if (percent >= 0) b.setProgress(100, percent, false);
        else b.setProgress(0, 0, true);
        Intent cancel = new Intent(c, DownloadService.class).setAction(DownloadService.ACTION_CANCEL_ALL);
        b.addAction(0, c.getString(R.string.cancel_all), PendingIntent.getService(c, 1, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        return b.build();
    }

    public static void completed(Context c, DownloadTask t) {
        post(c, t, c.getString(R.string.notif_done), t.targetName, android.R.drawable.stat_sys_download_done);
    }

    public static void failed(Context c, DownloadTask t) {
        post(c, t, c.getString(R.string.notif_failed), t.targetName + (t.errorMessage != null ? " \u2013 " + t.errorMessage : ""),
                android.R.drawable.stat_notify_error);
    }

    private static void post(Context c, DownloadTask t, String title, String text, int icon) {
        try {
            Notification n = new NotificationCompat.Builder(c, CH_DONE)
                    .setSmallIcon(icon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openApp(c))
                    .setAutoCancel(true)
                    .build();
            NotificationManagerCompat.from(c).notify(t.id.hashCode(), n);
        } catch (SecurityException ignored) {
            // notification permission not granted; the app UI still shows the result
        }
    }
}
