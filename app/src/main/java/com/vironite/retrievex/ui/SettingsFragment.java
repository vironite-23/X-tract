package com.vironite.retrievex.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.vironite.retrievex.App;
import com.vironite.retrievex.R;
import com.vironite.retrievex.auth.Session;
import com.vironite.retrievex.auth.SessionManager;
import com.vironite.retrievex.data.DownloadTask;
import com.vironite.retrievex.data.MediaItem;
import com.vironite.retrievex.data.MediaQuery;
import com.vironite.retrievex.data.Repo;
import com.vironite.retrievex.download.DownloadManager;
import com.vironite.retrievex.extract.FormatPicker;
import com.vironite.retrievex.storage.FileStore;
import com.vironite.retrievex.storage.NameTemplate;
import com.vironite.retrievex.util.Fmt;
import com.vironite.retrievex.util.Prefs;
import com.vironite.retrievex.util.TextUtil;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** All app settings, built from small row helpers. Changes apply immediately. */
public class SettingsFragment extends Fragment {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private Repo.Stats stats;
    private long tempBytes;

    private final ActivityResultLauncher<Uri> folderPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                try {
                    requireContext().getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (SecurityException e) {
                    Toast.makeText(requireContext(), R.string.folder_permission_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                Prefs.putString(Prefs.DOWNLOAD_TREE, uri.toString());
                build();
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        root = v.findViewById(R.id.settings_root);
        root.setSaveEnabled(false);
        root.setSaveFromParentEnabled(false);
        build();
    }

    @Override
    public void onStart() {
        super.onStart();
        refreshStats();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) refreshStats();
    }

    private void refreshStats() {
        final Context appCtx = requireContext().getApplicationContext();
        IO.execute(() -> {
            final Repo.Stats s = Repo.get().stats();
            final long temp = FileStore.tempUsage(appCtx);
            main.post(() -> {
                if (!isAdded()) return;
                stats = s;
                tempBytes = temp;
                build();
            });
        });
    }

    // ------------------------------------------------------------------ builders

    private void build() {
        if (root == null || !isAdded()) return;
        root.removeAllViews();

        section(R.string.set_downloads);
        choice(R.string.set_quality, Prefs.QUALITY, FormatPicker.BEST,
                new String[]{FormatPicker.BEST, FormatPicker.ORIGINAL, FormatPicker.SMALLEST, "1080", "720", "480", "360"},
                new int[]{R.string.q_best, R.string.q_original, R.string.q_smallest, R.string.q_1080, R.string.q_720, R.string.q_480, R.string.q_360});
        choice(R.string.set_image_select, Prefs.IMAGE_SELECT, "all", new String[]{"all", "none"},
                new int[]{R.string.img_select_all, R.string.img_select_none});
        choice(R.string.set_image_format, Prefs.IMAGE_FORMAT, "original", new String[]{"original", "jpg", "png", "webp"},
                new int[]{R.string.fmt_original, R.string.fmt_jpg, R.string.fmt_png, R.string.fmt_webp});
        action(R.string.set_folder, FileStore.destinationLabel(requireContext()), this::chooseFolder);
        toggle(R.string.set_wifi_only, R.string.set_wifi_only_desc, Prefs.WIFI_ONLY, false);
        choice(R.string.set_concurrent, Prefs.MAX_CONCURRENT, "2", new String[]{"1", "2", "3", "4", "5"},
                new int[]{R.string.n1, R.string.n2, R.string.n3, R.string.n4, R.string.n5});
        toggle(R.string.set_auto_merge, R.string.set_auto_merge_desc, Prefs.AUTO_MERGE, true);
        toggle(R.string.set_auto_cleanup, R.string.set_auto_cleanup_desc, Prefs.AUTO_CLEANUP, true);
        action(R.string.set_template, Prefs.template(), this::editTemplate);
        choice(R.string.set_duplicates, Prefs.DUPLICATE_MODE, "rename", new String[]{"rename", "skip"},
                new int[]{R.string.dup_rename, R.string.dup_skip});
        toggle(R.string.set_fallback, R.string.set_fallback_desc, Prefs.FALLBACK_SERVICE, true);

        section(R.string.set_xaccount);
        buildXAccount();

        section(R.string.set_library);
        choice(R.string.set_default_view, Prefs.MEDIA_VIEW, "grid", new String[]{"grid", "list", "detail"},
                new int[]{R.string.view_grid, R.string.view_list, R.string.view_detail});
        choice(R.string.set_default_sort, Prefs.MEDIA_SORT, MediaQuery.SORT_NEWEST, SortOptions.MEDIA_KEYS, SortOptions.MEDIA_LABELS);
        choice(R.string.set_default_filter, Prefs.MEDIA_FILTER, MediaQuery.FILTER_ALL, SortOptions.FILTER_KEYS, SortOptions.FILTER_LABELS);
        choice(R.string.set_account_sort, Prefs.ACCOUNT_SORT, Repo.ACC_ACTIVITY_DESC, SortOptions.ACCOUNT_KEYS, SortOptions.ACCOUNT_LABELS);
        choice(R.string.set_thumb_size, Prefs.THUMB_SIZE, "medium", new String[]{"small", "medium", "large"},
                new int[]{R.string.size_small, R.string.size_medium, R.string.size_large});
        toggle(R.string.set_show_account, R.string.set_show_account_desc, Prefs.SHOW_ACCOUNT, true);
        toggle(R.string.set_show_size, R.string.set_show_size_desc, Prefs.SHOW_SIZE, true);

        section(R.string.set_video);
        toggle(R.string.set_remember_brightness, R.string.set_remember_brightness_desc, Prefs.REMEMBER_BRIGHTNESS, false);
        toggle(R.string.set_auto_mute, R.string.set_auto_mute_desc, Prefs.AUTO_MUTE, false);

        section(R.string.set_appearance);
        choice(R.string.set_theme, Prefs.THEME, "system", new String[]{"system", "light", "dark"},
                new int[]{R.string.theme_system, R.string.theme_light, R.string.theme_dark});
        choice(R.string.set_grid_columns, Prefs.GRID_COLUMNS, "auto", new String[]{"auto", "2", "3", "4"},
                new int[]{R.string.cols_auto, R.string.n2, R.string.n3, R.string.n4});
        choice(R.string.set_list_density, Prefs.LIST_DENSITY, "comfortable", new String[]{"comfortable", "compact"},
                new int[]{R.string.density_comfortable, R.string.density_compact});

        section(R.string.set_storage);
        if (stats != null) {
            info(getString(R.string.stat_files, stats.fileCount, Fmt.size(stats.totalSize)),
                    getString(R.string.stat_split, Fmt.size(stats.videoSize), Fmt.size(stats.imageSize)));
        }
        info(getString(R.string.stat_temp, Fmt.size(tempBytes)), null);
        action(R.string.set_clear_temp, getString(R.string.set_clear_temp_desc), this::clearTemp);
        action(R.string.set_review_missing, getString(R.string.set_review_missing_desc), this::reviewMissing);
        action(R.string.set_clear_history, getString(R.string.set_clear_history_desc), this::clearHistory);
        action(R.string.set_open_folder, getString(R.string.set_open_folder_desc), () -> FileActions.openFolder(requireContext()));

        section(R.string.set_about);
        info(getString(R.string.about_title), getString(R.string.about_text));
        info(getString(R.string.privacy_title), getString(R.string.privacy_text));
    }

    /** Optional sign-in with the user's own X account, for posts X restricts (age or sensitive content). */
    private void buildXAccount() {
        Session s = SessionManager.get();
        if (s != null) {
            String summary = s.screenName != null
                    ? getString(R.string.xa_signed_in_as_tap, s.screenName)
                    : getString(R.string.xa_signed_in_tap);
            action(R.string.xa_account, summary, this::confirmSignOut);
        } else if (SessionManager.isUnavailable()) {
            // The saved sign-in is still there; the Keystore just hasn't unlocked it yet.
            action(R.string.xa_unlock_title, getString(R.string.xa_unlock_pending), () -> IO.execute(() -> {
                SessionManager.retry();
                final boolean still = SessionManager.isUnavailable();
                main.post(() -> {
                    if (!isAdded()) return;
                    if (still) {
                        // Last resort when the Keystore keeps refusing: let the user erase it and sign in again.
                        Dialogs.confirm(requireContext(), R.string.xa_unlock_title, getString(R.string.xa_reset_message),
                                R.string.xa_reset, () -> {
                                    SessionManager.signOut();
                                    build();
                                });
                    } else {
                        build();
                    }
                });
            }));
        } else {
            action(R.string.xa_signin, getString(SessionManager.wasExpired() ? R.string.xa_expired_desc : R.string.xa_signin_desc),
                    () -> startActivity(new Intent(requireContext(), LoginActivity.class)));
        }
        // Always shown, so the choice is kept and visible across updates and sign-ins.
        toggle(R.string.xa_use, R.string.xa_use_desc, Prefs.X_USE_ACCOUNT, true);
        info(getString(R.string.xa_security_title), getString(R.string.xa_security_text));
    }

    private void confirmSignOut() {
        Dialogs.confirm(requireContext(), R.string.xa_signout_title, getString(R.string.xa_signout_message), R.string.xa_sign_out, () -> {
            // The local session and its encryption key are erased first; ending it on X is best effort.
            final Session old = SessionManager.signOut();
            Prefs.putBool(Prefs.X_SESSION_EXPIRED, false);
            Toast.makeText(requireContext(), R.string.xa_signed_out, Toast.LENGTH_SHORT).show();
            build();
            IO.execute(() -> SessionManager.revokeRemotely(old));
        });
    }

    private void section(int titleRes) {
        View v = LayoutInflater.from(requireContext()).inflate(R.layout.row_section, root, false);
        ((TextView) v.findViewById(R.id.section_title)).setText(titleRes);
        root.addView(v);
    }

    private View row() {
        View v = LayoutInflater.from(requireContext()).inflate(R.layout.row_setting, root, false);
        // Every row reuses the same child ids (row_switch, row_title...). If Android restored saved view state here
        // (theme change, rotation, system dark mode, process death), the state of ONE row would be applied to ALL
        // rows and could flip switches off. Rows are rebuilt from Prefs in build(), so nothing needs restoring.
        v.setSaveFromParentEnabled(false);
        v.setSaveEnabled(false);
        return v;
    }

    private void info(String title, String summary) {
        View v = row();
        ((TextView) v.findViewById(R.id.row_title)).setText(title);
        TextView s = v.findViewById(R.id.row_summary);
        if (summary == null) s.setVisibility(View.GONE);
        else s.setText(summary);
        v.setBackground(null);
        root.addView(v);
    }

    private void action(int titleRes, String summary, final Runnable r) {
        View v = row();
        ((TextView) v.findViewById(R.id.row_title)).setText(titleRes);
        TextView s = v.findViewById(R.id.row_summary);
        if (TextUtil.isEmpty(summary)) s.setVisibility(View.GONE);
        else s.setText(summary);
        v.setOnClickListener(x -> r.run());
        root.addView(v);
    }

    private void toggle(int titleRes, int descRes, final String key, boolean def) {
        View v = row();
        ((TextView) v.findViewById(R.id.row_title)).setText(titleRes);
        ((TextView) v.findViewById(R.id.row_summary)).setText(descRes);
        final MaterialSwitch sw = v.findViewById(R.id.row_switch);
        sw.setVisibility(View.VISIBLE);
        sw.setSaveEnabled(false);                 // never restore a stale/shared state into this switch
        sw.setChecked(Prefs.getBool(key, def));
        // Save only on a real tap. OnCheckedChangeListener also fires for programmatic changes such as state
        // restoration, which is what used to write "off" into the settings by itself; onClick never does.
        sw.setOnClickListener(x -> Prefs.putBool(key, sw.isChecked()));
        v.setOnClickListener(x -> sw.performClick());
        root.addView(v);
    }

    private void choice(int titleRes, final String key, String def, final String[] values, final int[] labels) {
        String current = Prefs.MAX_CONCURRENT.equals(key) ? String.valueOf(Prefs.maxConcurrent()) : Prefs.getString(key, def);
        final int idx = SortOptions.indexOf(values, current);
        View v = row();
        ((TextView) v.findViewById(R.id.row_title)).setText(titleRes);
        ((TextView) v.findViewById(R.id.row_summary)).setText(labels[idx]);
        final int title = titleRes;
        v.setOnClickListener(x -> {
            CharSequence[] items = new CharSequence[labels.length];
            for (int i = 0; i < labels.length; i++) items[i] = getString(labels[i]);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(title)
                    .setSingleChoiceItems(items, idx, (d, which) -> {
                        if (Prefs.MAX_CONCURRENT.equals(key)) Prefs.putInt(key, Integer.parseInt(values[which]));
                        else Prefs.putString(key, values[which]);
                        d.dismiss();
                        if (Prefs.THEME.equals(key)) App.applyTheme(values[which]);
                        build();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
        root.addView(v);
    }

    // ------------------------------------------------------------------ actions

    private void chooseFolder() {
        if (FileStore.customTree() == null) {
            folderPicker.launch(null);
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.set_folder)
                .setMessage(FileStore.destinationLabel(requireContext()))
                .setPositiveButton(R.string.choose_folder, (d, w) -> folderPicker.launch(null))
                .setNeutralButton(R.string.reset_default, (d, w) -> {
                    Prefs.remove(Prefs.DOWNLOAD_TREE);
                    build();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void editTemplate() {
        Dialogs.textInput(requireContext(), R.string.set_template, Prefs.template(), getString(R.string.template_hint),
                getString(R.string.template_help, NameTemplate.HELP), text -> {
                    String t = text.trim();
                    Prefs.putString(Prefs.NAME_TEMPLATE, t.isEmpty() ? Prefs.DEFAULT_TEMPLATE : t);
                    build();
                });
    }

    private void clearTemp() {
        final Context appCtx = requireContext().getApplicationContext();
        Dialogs.confirm(requireContext(), R.string.set_clear_temp, getString(R.string.clear_temp_message), R.string.clear, () ->
                IO.execute(() -> {
                    Set<String> keep = new HashSet<>();
                    for (DownloadTask t : DownloadManager.get().snapshot()) {
                        if (!t.isTerminal() || t.status == DownloadTask.FAILED) {
                            if (t.tempPath != null) keep.add(t.tempPath);
                            if (t.tempPath2 != null) keep.add(t.tempPath2);
                        }
                    }
                    final long freed = FileStore.clearTemp(appCtx, keep);
                    ImageLoader.clearAll();
                    main.post(() -> {
                        if (!isAdded()) return;
                        Toast.makeText(requireContext(), getString(R.string.freed, Fmt.size(freed)), Toast.LENGTH_SHORT).show();
                        refreshStats();
                    });
                }));
    }

    private void clearHistory() {
        Dialogs.confirm(requireContext(), R.string.set_clear_history, getString(R.string.clear_history_message), R.string.clear, () -> {
            int n = DownloadManager.get().clearHistory();
            Toast.makeText(requireContext(), getString(R.string.history_cleared, n), Toast.LENGTH_SHORT).show();
        });
    }

    /** Scans the whole library once (on demand) and lists records whose files are missing or unreachable. */
    private void reviewMissing() {
        final Context appCtx = requireContext().getApplicationContext();
        Toast.makeText(requireContext(), R.string.scanning, Toast.LENGTH_SHORT).show();
        IO.execute(() -> {
            long after = 0;
            while (true) {
                List<MediaItem> batch = Repo.get().allMedia(after, 200);
                if (batch.isEmpty()) break;
                for (MediaItem m : batch) {
                    after = Math.max(after, m.id);
                    int status = FileStore.availability(appCtx, Uri.parse(m.fileUri));
                    if (status != m.availability) Repo.get().setAvailability(m.id, status);
                }
            }
            final List<MediaItem> missing = Repo.get().missingMedia();
            main.post(() -> {
                if (!isAdded()) return;
                if (missing.isEmpty()) {
                    Dialogs.message(requireContext(), R.string.set_review_missing, getString(R.string.no_missing));
                    return;
                }
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (MediaItem m : missing) {
                    if (shown++ >= 15) break;
                    sb.append("\u2022 ").append(m.filename).append('\n');
                }
                if (missing.size() > 15) sb.append(getString(R.string.and_more, missing.size() - 15));
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.missing_count, missing.size()))
                        .setMessage(sb.toString().trim() + "\n\n" + getString(R.string.missing_remove_note))
                        .setPositiveButton(R.string.remove_missing_records, (d, w) -> IO.execute(() -> Repo.get().removeMissingRecords()))
                        .setNegativeButton(R.string.close, null)
                        .show();
            });
        });
    }

    @Override
    public void onDestroyView() {
        main.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }
}
