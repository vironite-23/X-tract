package com.vironite.retrievex.download;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.vironite.retrievex.R;
import com.vironite.retrievex.data.DownloadTask;

/** Foreground service that keeps the process alive while downloads are running. */
public class DownloadService extends Service implements DownloadManager.Listener {
    public static final String ACTION_CANCEL_ALL = "com.vironite.retrievex.CANCEL_ALL";
    private static volatile boolean running;

    public static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        DownloadManager.get().addListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL_ALL.equals(intent.getAction())) {
            DownloadManager.get().cancelAllActive();
        }
        enterForeground(buildNotification());
        if (!DownloadManager.get().hasRunningWork()) stopSelfNow();
        return START_NOT_STICKY;
    }

    private void enterForeground(Notification n) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(Notifications.ID_SERVICE, n);
        }
    }

    private Notification buildNotification() {
        DownloadManager m = DownloadManager.get();
        int active = m.activeCount();
        int waiting = m.waitingCount();
        String title = active > 0 ? getString(R.string.notif_downloading, active) : getString(R.string.notif_waiting_title);
        return Notifications.progress(this, active, waiting, m.aggregatePercent(), title);
    }

    private void stopSelfNow() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(Service.STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onTaskChanged(DownloadTask t) {
        if (!DownloadManager.get().hasRunningWork()) {
            stopSelfNow();
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(Notifications.ID_SERVICE, buildNotification());
    }

    @Override
    public void onTaskRemoved(String id) {
        // nothing to do
    }

    @Override
    public void onReset() {
        // nothing to do
    }

    @Override
    public void onDestroy() {
        running = false;
        DownloadManager.get().removeListener(this);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
