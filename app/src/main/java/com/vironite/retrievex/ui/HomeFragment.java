package com.vironite.retrievex.ui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.vironite.retrievex.MainActivity;
import com.vironite.retrievex.R;
import com.vironite.retrievex.extract.ExtractException;
import com.vironite.retrievex.extract.ExtractorChain;
import com.vironite.retrievex.extract.MediaEntry;
import com.vironite.retrievex.extract.PostInfo;
import com.vironite.retrievex.extract.UrlParser;
import com.vironite.retrievex.storage.FileStore;
import com.vironite.retrievex.util.Fmt;
import com.vironite.retrievex.util.NetUtil;
import com.vironite.retrievex.util.Prefs;
import com.vironite.retrievex.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** URL input, media inspection and the entry point to the download options sheet. */
public class HomeFragment extends Fragment {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();

    private HomeViewModel state;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextInputEditText urlInput;
    private MaterialButton btnFetch;
    private MaterialButton btnDownload;
    private MaterialButton btnSelectAll;
    private LinearProgressIndicator progress;
    private View errorCard;
    private TextView errorText;
    private View resultContainer;
    private View helpCard;
    private RecyclerView mediaList;
    private TextView mediaSummary;

    private Runnable pendingAfterPermission;
    private boolean notificationAsked;

    private final ActivityResultLauncher<String> storageLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                Runnable r = pendingAfterPermission;
                pendingAfterPermission = null;
                if (granted && r != null) {
                    ensurePermissions(r);
                } else if (!granted) {
                    Toast.makeText(requireContext(), R.string.storage_permission_needed, Toast.LENGTH_LONG).show();
                }
            });

    private final ActivityResultLauncher<String> notificationLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                Runnable r = pendingAfterPermission;
                pendingAfterPermission = null;
                if (r != null) r.run(); // downloads work without notifications
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        state = new ViewModelProvider(this).get(HomeViewModel.class);
        urlInput = v.findViewById(R.id.url_input);
        btnFetch = v.findViewById(R.id.btn_fetch);
        btnDownload = v.findViewById(R.id.btn_download);
        btnSelectAll = v.findViewById(R.id.btn_select_all);
        progress = v.findViewById(R.id.fetch_progress);
        errorCard = v.findViewById(R.id.error_card);
        errorText = v.findViewById(R.id.error_text);
        resultContainer = v.findViewById(R.id.result_container);
        helpCard = v.findViewById(R.id.help_card);
        mediaList = v.findViewById(R.id.media_list);
        mediaSummary = v.findViewById(R.id.media_summary);
        mediaList.setLayoutManager(new LinearLayoutManager(requireContext()));

        v.findViewById(R.id.btn_paste).setOnClickListener(x -> paste());
        btnFetch.setOnClickListener(x -> fetch());
        v.findViewById(R.id.btn_error_retry).setOnClickListener(x -> fetch());
        btnDownload.setOnClickListener(x -> onDownloadClicked());
        btnSelectAll.setOnClickListener(x -> toggleSelectAll());
        urlInput.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                fetch();
                return true;
            }
            return false;
        });
        urlInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (state.error != null && !state.loading) {
                    state.error = null;
                    render();
                }
            }
        });
        render();
    }

    /** Called by MainActivity when a link is shared to the app. Inspects the post but never downloads on its own. */
    public void receiveSharedUrl(String url) {
        if (urlInput == null) {
            // view not created yet: remember through the view model once it exists
            main.post(() -> {
                if (urlInput != null) receiveSharedUrl(url);
            });
            return;
        }
        urlInput.setText(url);
        fetch();
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        String text = null;
        if (cm != null && cm.hasPrimaryClip()) {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence cs = clip.getItemAt(0).coerceToText(requireContext());
                if (cs != null) text = cs.toString();
            }
        }
        if (TextUtil.isEmpty(text)) {
            Toast.makeText(requireContext(), R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String url = UrlParser.findUrl(text);
        urlInput.setText(url != null ? url : text.trim());
        urlInput.setSelection(urlInput.getText() == null ? 0 : urlInput.getText().length());
        if (url == null) {
            state.error = getString(R.string.clipboard_no_link);
            render();
        }
    }

    private void hideKeyboard() {
        View v = getView();
        if (v == null) return;
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void fetch() {
        if (state.loading) return;
        final String raw = urlInput.getText() == null ? "" : urlInput.getText().toString().trim();
        hideKeyboard();
        try {
            UrlParser.parse(raw);
        } catch (ExtractException e) {
            state.error = e.getMessage();
            render();
            return;
        }
        if (!NetUtil.isOnline(requireContext())) {
            state.error = getString(R.string.no_connection);
            render();
            return;
        }
        state.loading = true;
        state.error = null;
        final int gen = ++state.generation;
        final HomeViewModel st = state;
        render();
        EXEC.execute(() -> {
            PostInfo info = null;
            String error = null;
            try {
                info = ExtractorChain.extract(raw);
                // Replace bitrate-based guesses with the real sizes so the preview matches the saved files.
                try {
                    com.vironite.retrievex.extract.SizeProbe.resolve(info, Prefs.getString(Prefs.IMAGE_FORMAT, "original"));
                } catch (Exception ignored) {
                    // estimates stay in place
                }
            } catch (ExtractException e) {
                error = e.getMessage();
            } catch (Exception e) {
                error = "Something went wrong while reading the post.";
            }
            final PostInfo fInfo = info;
            final String fError = error;
            main.post(() -> {
                if (gen != st.generation) return;
                st.loading = false;
                st.error = fError;
                if (fInfo != null) {
                    st.post = fInfo;
                    st.selected = defaultSelection(fInfo);
                } else {
                    st.post = null;
                    st.selected = null;
                }
                if (isAdded() && getView() != null) render();
            });
        });
    }

    private boolean[] defaultSelection(PostInfo info) {
        boolean[] sel = new boolean[info.media.size()];
        boolean images = "all".equals(Prefs.getString(Prefs.IMAGE_SELECT, "all"));
        boolean anyVideo = false;
        for (MediaEntry e : info.media) if (e.isVideoLike()) anyVideo = true;
        for (int i = 0; i < sel.length; i++) {
            MediaEntry e = info.media.get(i);
            sel[i] = e.isVideoLike() || images || !anyVideo && info.media.size() == 1;
        }
        return sel;
    }

    private void render() {
        if (getView() == null) return;
        progress.setVisibility(state.loading ? View.VISIBLE : View.GONE);
        btnFetch.setEnabled(!state.loading);
        boolean hasError = state.error != null;
        errorCard.setVisibility(hasError ? View.VISIBLE : View.GONE);
        if (hasError) errorText.setText(state.error);

        boolean hasPost = state.post != null && !state.loading;
        resultContainer.setVisibility(hasPost ? View.VISIBLE : View.GONE);
        helpCard.setVisibility(hasPost || state.loading ? View.GONE : View.VISIBLE);
        if (!hasPost) return;

        View root = getView();
        PostInfo p = state.post;
        ImageView avatar = root.findViewById(R.id.post_avatar);
        int px = (int) (48 * getResources().getDisplayMetrics().density);
        ImageLoader.loadUrl(avatar, p.avatarUrl, px);
        TextView name = root.findViewById(R.id.post_name);
        TextView user = root.findViewById(R.id.post_username);
        TextView text = root.findViewById(R.id.post_text);
        TextView date = root.findViewById(R.id.post_date);
        name.setText(TextUtil.isEmpty(p.displayName) ? getString(R.string.unknown_account) : p.displayName);
        user.setText(TextUtil.isEmpty(p.username) ? "" : "@" + p.username);
        user.setVisibility(TextUtil.isEmpty(p.username) ? View.GONE : View.VISIBLE);
        text.setText(p.text);
        text.setVisibility(TextUtil.isEmpty(p.text) ? View.GONE : View.VISIBLE);
        date.setText(Fmt.dateTime(requireContext(), p.createdAtMs));
        date.setVisibility(p.createdAtMs > 0 ? View.VISIBLE : View.GONE);

        PostMediaAdapter adapter = new PostMediaAdapter(p.media, state.selected, this::updateSelectionUi);
        mediaList.setAdapter(adapter);
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        if (state.post == null || state.selected == null || getView() == null) return;
        int videos = 0;
        int images = 0;
        for (MediaEntry e : state.post.media) {
            if (e.isVideoLike()) videos++;
            else images++;
        }
        mediaSummary.setText(getString(R.string.media_summary, state.post.media.size(), videos, images));
        int n = 0;
        for (boolean b : state.selected) if (b) n++;
        btnDownload.setEnabled(n > 0);
        btnDownload.setText(getString(R.string.download_count, n));
        btnSelectAll.setText(n == state.selected.length ? R.string.deselect_all : R.string.select_all);
    }

    private void toggleSelectAll() {
        if (state.selected == null) return;
        boolean all = true;
        for (boolean b : state.selected) if (!b) all = false;
        for (int i = 0; i < state.selected.length; i++) state.selected[i] = !all;
        RecyclerView.Adapter<?> a = mediaList.getAdapter();
        if (a != null) a.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void onDownloadClicked() {
        if (state.post == null || state.selected == null) return;
        final List<MediaEntry> chosen = new ArrayList<>();
        for (int i = 0; i < state.selected.length; i++) {
            if (state.selected[i]) chosen.add(state.post.media.get(i));
        }
        if (chosen.isEmpty()) return;
        final PostInfo post = state.post;
        ensurePermissions(() -> DownloadSheet.show(requireActivity(), post, chosen, count -> {
            View root = getView();
            if (root == null) return;
            Snackbar.make(root, getString(R.string.queued_n, count), Snackbar.LENGTH_LONG)
                    .setAction(R.string.view, v -> {
                        if (getActivity() instanceof MainActivity) ((MainActivity) getActivity()).showDownloads(true);
                    }).show();
        }));
    }

    /** Requests only the permissions that are actually needed for the current Android version and destination. */
    private void ensurePermissions(Runnable proceed) {
        Context c = requireContext();
        if (Build.VERSION.SDK_INT <= 28 && FileStore.customTree() == null
                && ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterPermission = proceed;
            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationAsked
                && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationAsked = true;
            pendingAfterPermission = proceed;
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            return;
        }
        proceed.run();
    }
}
