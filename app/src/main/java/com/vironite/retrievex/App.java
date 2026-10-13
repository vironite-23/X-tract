package com.vironite.retrievex;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.vironite.retrievex.auth.SessionManager;
import com.vironite.retrievex.data.Repo;
import com.vironite.retrievex.download.DownloadManager;
import com.vironite.retrievex.download.Notifications;
import com.vironite.retrievex.ui.ImageLoader;
import com.vironite.retrievex.util.Prefs;
import com.vironite.retrievex.util.SettingsBackup;

/** Application entry point: initialises preferences, theme, notification channels and the download manager. */
public class App extends Application {
    private static App instance;

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Prefs.init(this);
        SettingsBackup.restoreIfFresh();
        SessionManager.init(this);
        SessionManager.warmUp();
        Repo.init(this);
        applyTheme(Prefs.getString(Prefs.THEME, "system"));
        ImageLoader.init(this);
        Notifications.createChannels(this);
        DownloadManager.init(this);
    }

    public static void applyTheme(String mode) {
        int m;
        if ("light".equals(mode)) {
            m = AppCompatDelegate.MODE_NIGHT_NO;
        } else if ("dark".equals(mode)) {
            m = AppCompatDelegate.MODE_NIGHT_YES;
        } else {
            m = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
        AppCompatDelegate.setDefaultNightMode(m);
    }
}
