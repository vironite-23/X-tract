package com.vironite.retrievex.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.vironite.retrievex.R;
import com.vironite.retrievex.data.Account;
import com.vironite.retrievex.data.Repo;
import com.vironite.retrievex.download.AvatarCache;
import com.vironite.retrievex.extract.ExtractException;
import com.vironite.retrievex.extract.FxTwitterExtractor;
import com.vironite.retrievex.util.TextUtil;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Account profile summary, statistics and that account's own media library. */
public class AccountDetailActivity extends AppCompatActivity {
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private long accountId;
    private Account account;
    private ImageView toolbarAvatar;
    private View titleBox;
    private TextView toolbarName;
    private TextView toolbarUser;
    private MaterialToolbar toolbar;

    private final Repo.Listener listener = new Repo.Listener() {
        @Override
        public void onChanged() {
            loadAccount();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_detail);
        InsetsHelper.apply(findViewById(R.id.detail_root));
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, 0);
        if (accountId <= 0) {
            finish();
            return;
        }
        toolbar = findViewById(R.id.detail_toolbar);
        titleBox = findViewById(R.id.toolbar_title_box);
        toolbarAvatar = findViewById(R.id.toolbar_avatar);
        toolbarName = findViewById(R.id.toolbar_name);
        toolbarUser = findViewById(R.id.toolbar_username);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.account_detail);
        toolbar.setOnMenuItemClickListener(this::onMenu);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.detail_container, MediaLibraryFragment.forAccount(accountId))
                    .commit();
        }
        loadAccount();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Repo.get().addAccountListener(listener);
        Repo.get().addMediaListener(listener);
        loadAccount();
    }

    @Override
    protected void onStop() {
        Repo.get().removeAccountListener(listener);
        Repo.get().removeMediaListener(listener);
        super.onStop();
    }

    private void loadAccount() {
        IO.execute(() -> {
            final Account a = Repo.get().getAccount(accountId);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (a == null || a.hidden) {
                    finish();
                    return;
                }
                account = a;
                bind(a);
            });
        });
    }

    private void bind(Account a) {
        AccountAdapter.loadAvatar(toolbarAvatar, a, (int) (38 * getResources().getDisplayMetrics().density));
        toolbarName.setText(a.label());
        toolbarUser.setText(a.handle());
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.detail_container);
        if (f instanceof MediaLibraryFragment) ((MediaLibraryFragment) f).bindAccount(a);
    }

    /** The account shown here, or null until it has been loaded. Used by the library to fill its profile block. */
    Account currentAccount() {
        return account;
    }

    /** Fades the account name and handle in next to the back arrow as the profile block scrolls away. */
    void onHeaderCollapsed(float progress) {
        if (titleBox != null) titleBox.setAlpha(progress);
    }

    private boolean onMenu(MenuItem item) {
        if (account == null) return false;
        int id = item.getItemId();
        if (id == R.id.action_open_profile) {
            FileActions.openPost(this, "https://x.com/" + account.username);
            return true;
        }
        if (id == R.id.action_refresh_account) {
            refreshMetadata();
            return true;
        }
        if (id == R.id.action_edit_label) {
            Dialogs.textInput(this, R.string.edit_label, account.customLabel != null ? account.customLabel : "",
                    getString(R.string.custom_label_hint), getString(R.string.custom_label_help), text -> {
                        final String t = text;
                        IO.execute(() -> Repo.get().setAccountLabel(accountId, t));
                    });
            return true;
        }
        if (id == R.id.action_remove_account) {
            Dialogs.confirm(this, R.string.remove_account_title, getString(R.string.remove_account_message, account.handle()),
                    R.string.remove, () -> IO.execute(() -> Repo.get().hideAccount(accountId)));
            return true;
        }
        return false;
    }

    /** Refreshes display name and avatar from the public profile. Triggered only by the user. */
    private void refreshMetadata() {
        final String username = account.username;
        Toast.makeText(this, R.string.refreshing, Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            String error = null;
            try {
                FxTwitterExtractor.UserInfo info = FxTwitterExtractor.fetchUser(username);
                Repo.get().updateAccountMeta(accountId, info.displayName, info.avatarUrl, info.restId);
                if (!TextUtil.isEmpty(info.avatarUrl)) AvatarCache.cache(getApplicationContext(), accountId, info.avatarUrl);
            } catch (ExtractException e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = getString(R.string.refresh_failed);
            }
            final String fError = error;
            main.post(() -> Toast.makeText(this, fError == null ? getString(R.string.refreshed) : fError, Toast.LENGTH_LONG).show());
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return false;
    }
}
