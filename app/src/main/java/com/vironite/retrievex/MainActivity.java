package com.vironite.retrievex;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;
import com.vironite.retrievex.extract.UrlParser;
import com.vironite.retrievex.ui.AccountsFragment;
import com.vironite.retrievex.ui.DownloadsFragment;
import com.vironite.retrievex.ui.HomeFragment;
import com.vironite.retrievex.ui.InsetsHelper;
import com.vironite.retrievex.ui.SettingsFragment;

/** Hosts the four primary destinations. Fragments are kept alive (show/hide) so state is preserved. */
public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_OPEN_TAB = "open_tab";
    private static final String STATE_TAB = "tab";
    private static final String[] TAGS = {"home", "downloads", "accounts", "settings"};

    private BottomNavigationView nav;
    private String current = "home";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        InsetsHelper.apply(findViewById(R.id.main_root));
        nav = findViewById(R.id.bottom_nav);

        FragmentManager fm = getSupportFragmentManager();
        if (savedInstanceState == null) {
            fm.beginTransaction()
                    .add(R.id.main_container, new HomeFragment(), "home")
                    .add(R.id.main_container, new DownloadsFragment(), "downloads")
                    .add(R.id.main_container, new AccountsFragment(), "accounts")
                    .add(R.id.main_container, new SettingsFragment(), "settings")
                    .commitNow();
        } else {
            current = savedInstanceState.getString(STATE_TAB, "home");
        }
        showTab(current);
        nav.setSelectedItemId(idFor(current));

        nav.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                showTab(tagFor(item.getItemId()));
                return true;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!"home".equals(current)) {
                    nav.setSelectedItemId(R.id.nav_home);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState == null) handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_TAB, current);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) && intent.getType() != null && intent.getType().startsWith("text/")) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            String url = UrlParser.findUrl(text);
            nav.setSelectedItemId(R.id.nav_home);
            if (url == null) {
                Toast.makeText(this, R.string.share_no_link, Toast.LENGTH_LONG).show();
            } else {
                Fragment f = getSupportFragmentManager().findFragmentByTag("home");
                if (f instanceof HomeFragment) ((HomeFragment) f).receiveSharedUrl(url);
            }
            // consume the share so a configuration change doesn't process it again
            intent.setAction(Intent.ACTION_MAIN);
            intent.removeExtra(Intent.EXTRA_TEXT);
        } else if ("downloads".equals(intent.getStringExtra(EXTRA_OPEN_TAB))) {
            showDownloads(true);
            intent.removeExtra(EXTRA_OPEN_TAB);
        }
    }

    private static int idFor(String tag) {
        switch (tag) {
            case "downloads":
                return R.id.nav_downloads;
            case "accounts":
                return R.id.nav_accounts;
            case "settings":
                return R.id.nav_settings;
            default:
                return R.id.nav_home;
        }
    }

    private static String tagFor(int id) {
        if (id == R.id.nav_downloads) return "downloads";
        if (id == R.id.nav_accounts) return "accounts";
        if (id == R.id.nav_settings) return "settings";
        return "home";
    }

    private void showTab(String tag) {
        current = tag;
        FragmentManager fm = getSupportFragmentManager();
        FragmentTransaction tx = fm.beginTransaction();
        for (String t : TAGS) {
            Fragment f = fm.findFragmentByTag(t);
            if (f == null) continue;
            if (t.equals(tag)) tx.show(f);
            else tx.hide(f);
        }
        tx.commitAllowingStateLoss();
    }

    /** Switches to the Downloads tab, optionally showing the queue instead of the library. */
    public void showDownloads(boolean queue) {
        nav.setSelectedItemId(R.id.nav_downloads);
        Fragment f = getSupportFragmentManager().findFragmentByTag("downloads");
        if (f instanceof DownloadsFragment) ((DownloadsFragment) f).showQueue(queue);
    }

    public void showHome() {
        nav.setSelectedItemId(R.id.nav_home);
    }
}
