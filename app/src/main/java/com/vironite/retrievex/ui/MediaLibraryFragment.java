package com.vironite.retrievex.ui;

import android.app.RecoverableSecurityException;
import android.content.Context;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.vironite.retrievex.MainActivity;
import com.vironite.retrievex.R;
import com.vironite.retrievex.data.Account;
import com.vironite.retrievex.data.MediaItem;
import com.vironite.retrievex.data.MediaQuery;
import com.vironite.retrievex.data.Repo;
import com.vironite.retrievex.storage.FileStore;
import com.vironite.retrievex.util.Fmt;
import com.vironite.retrievex.util.Prefs;
import com.vironite.retrievex.util.TextUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Persistent media library with live search, sorting, filters, grid/list modes and multi-selection.
 * Used for the Downloads tab (all media) and for a single account's media (argument "account_id").
 */
public class MediaLibraryFragment extends Fragment {
    public static final String ARG_ACCOUNT_ID = "account_id";
    private static final int PAGE = 120;
    private static final long VERIFY_TTL_MS = 30_000;
    private static final int VERIFY_CAP = 150;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    public static MediaLibraryFragment forAccount(long accountId) {
        MediaLibraryFragment f = new MediaLibraryFragment();
        Bundle b = new Bundle();
        b.putLong(ARG_ACCOUNT_ID, accountId);
        f.setArguments(b);
        return f;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private long accountId;
    private String sortKey;
    private String filterKey;
    private String searchText = "";
    private String viewMode = MediaAdapter.VIEW_GRID;

    private final List<MediaItem> items = new ArrayList<>();
    private final Set<Long> selected = new LinkedHashSet<>();
    private final Map<Long, Long> verifiedAt = new HashMap<>();
    private boolean loading;
    private boolean hasMore;
    private int generation;
    private int totalForScope;

    private MediaAdapter adapter;
    private RecyclerView recycler;
    private View emptyView;
    private TextView emptyTitle;
    private TextView emptyMessage;
    private MaterialButton emptyAction;
    private LinearProgressIndicator progress;
    private View searchBar;
    private View selectionBar;
    private TextView selCount;
    private TextInputEditText searchInput;
    private ImageButton btnView;
    private ChipGroup chips;
    private AppBarLayout appBar;
    private View titleHeader;
    private View accountProfile;
    private boolean wasSelecting;
    private OnBackPressedCallback backCallback;

    private final Runnable searchRunnable = new Runnable() {
        @Override
        public void run() {
            reload();
        }
    };

    private final Repo.Listener mediaListener = new Repo.Listener() {
        @Override
        public void onChanged() {
            if (isAdded()) reload();
        }
    };

    private List<MediaItem> pendingDelete;
    private final ActivityResultLauncher<IntentSenderRequest> deleteLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                List<MediaItem> list = pendingDelete;
                pendingDelete = null;
                if (list == null) return;
                if (result.getResultCode() == android.app.Activity.RESULT_OK) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        // the system deleted the files; drop the records
                        recordDeleted(list);
                    } else {
                        deleteItems(list); // API 29: retry now that access was granted
                    }
                } else {
                    Toast.makeText(requireContext(), R.string.delete_not_allowed, Toast.LENGTH_SHORT).show();
                }
            });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_media_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        accountId = getArguments() != null ? getArguments().getLong(ARG_ACCOUNT_ID, 0) : 0;
        String sortPref = accountId > 0 ? Prefs.ACC_MEDIA_SORT : Prefs.MEDIA_SORT;
        String filterPref = accountId > 0 ? Prefs.ACC_MEDIA_FILTER : Prefs.MEDIA_FILTER;
        sortKey = Prefs.getString(sortPref, MediaQuery.SORT_NEWEST);
        filterKey = Prefs.getString(filterPref, MediaQuery.FILTER_ALL);
        viewMode = readViewMode();

        recycler = v.findViewById(R.id.media_recycler);
        emptyView = v.findViewById(R.id.empty_view);
        emptyTitle = v.findViewById(R.id.empty_title);
        emptyMessage = v.findViewById(R.id.empty_message);
        emptyAction = v.findViewById(R.id.empty_action);
        progress = v.findViewById(R.id.lib_progress);
        searchBar = v.findViewById(R.id.search_bar);
        selectionBar = v.findViewById(R.id.selection_bar);
        selCount = v.findViewById(R.id.sel_count);
        searchInput = v.findViewById(R.id.search_input);
        btnView = v.findViewById(R.id.btn_view);
        chips = v.findViewById(R.id.filter_chips);
        appBar = v.findViewById(R.id.lib_appbar);
        titleHeader = v.findViewById(R.id.downloads_header);
        setupHeader(v);

        adapter = new MediaAdapter(selected, new MediaAdapter.Listener() {
            @Override
            public void onClick(MediaItem item) {
                onItemClick(item);
            }

            @Override
            public void onLongClick(MediaItem item) {
                toggleSelection(item);
            }

            @Override
            public void onMore(MediaItem item, View anchor) {
                showItemMenu(item, anchor);
            }

            @Override
            public void onPostShare(List<MediaItem> group) {
                shareGroup(group);
            }

            @Override
            public void onPostMore(List<MediaItem> group, View anchor) {
                showPostMenu(group, anchor);
            }
        });
        adapter.setHasStableIds(false);
        recycler.setAdapter(adapter);
        recycler.setItemAnimator(null);
        applyLayout();

        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || !hasMore || loading) return;
                RecyclerView.LayoutManager lm = rv.getLayoutManager();
                int last = 0;
                if (lm instanceof GridLayoutManager) last = ((GridLayoutManager) lm).findLastVisibleItemPosition();
                else if (lm instanceof LinearLayoutManager) last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
                if (last >= adapter.getItemCount() - 12) load(false);
            }
        });

        // filter chips
        for (int i = 0; i < SortOptions.FILTER_KEYS.length; i++) {
            Chip chip = (Chip) chips.getChildAt(i);
            chip.setText(SortOptions.FILTER_LABELS[i]);
            chip.setChecked(SortOptions.FILTER_KEYS[i].equals(filterKey));
        }
        chips.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            int idx = group.indexOfChild(group.findViewById(ids.get(0)));
            if (idx < 0 || idx >= SortOptions.FILTER_KEYS.length) return;
            filterKey = SortOptions.FILTER_KEYS[idx];
            Prefs.putString(accountId > 0 ? Prefs.ACC_MEDIA_FILTER : Prefs.MEDIA_FILTER, filterKey);
            clearSelection();
            reload();
        });

        // search
        searchInput.setText(searchText);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                searchText = s.toString();
                main.removeCallbacks(searchRunnable);
                main.postDelayed(searchRunnable, 250);
            }
        });
        searchInput.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(tv.getWindowToken(), 0);
                return true;
            }
            return false;
        });

        v.findViewById(R.id.btn_sort).setOnClickListener(this::showSortMenu);
        btnView.setOnClickListener(x -> {
            viewMode = nextViewMode(viewMode);
            Prefs.putString(Prefs.MEDIA_VIEW, viewMode);
            applyLayout();
        });
        v.findViewById(R.id.btn_sel_close).setOnClickListener(x -> clearSelection());
        v.findViewById(R.id.btn_sel_all).setOnClickListener(x -> selectAllLoaded());
        v.findViewById(R.id.btn_sel_share).setOnClickListener(x -> FileActions.share(requireContext(), selectedItems()));
        v.findViewById(R.id.btn_sel_delete).setOnClickListener(x -> confirmDelete(selectedItems()));

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                clearSelection();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), backCallback);
        updateSelectionUi();
    }

    /**
     * The top part of the library (title row in Downloads, avatar/name/statistics in an account's library,
     * plus the search bar) scrolls out of view while the filter chips stay pinned.
     */
    private void setupHeader(View root) {
        Fragment parent = getParentFragment();
        boolean inDownloads = accountId <= 0 && parent instanceof DownloadsFragment;
        if (titleHeader != null) {
            titleHeader.setVisibility(inDownloads ? View.VISIBLE : View.GONE);
            if (inDownloads) ((DownloadsFragment) parent).bindHeader(titleHeader);
        }
        accountProfile = root.findViewById(R.id.account_profile);
        if (accountProfile != null) accountProfile.setVisibility(accountId > 0 ? View.VISIBLE : View.GONE);
        if (accountId > 0) {
            if (appBar != null) appBar.addOnOffsetChangedListener((ab, offset) -> reportCollapse(offset));
            if (getActivity() instanceof AccountDetailActivity) {
                bindAccount(((AccountDetailActivity) getActivity()).currentAccount());
            }
        }
    }

    /** Tells the account screen how far the profile block is collapsed (0 = fully shown, 1 = gone). */
    private void reportCollapse(int offset) {
        if (accountProfile == null || !(getActivity() instanceof AccountDetailActivity)) return;
        int h = accountProfile.getHeight();
        float p = 0f;
        if (h > 0) p = Math.max(0f, Math.min(1f, (-offset - h * 0.5f) / (h * 0.5f)));
        ((AccountDetailActivity) getActivity()).onHeaderCollapsed(p);
    }

    /** Fills the profile block of an account's library. Called by the account screen whenever the account changes. */
    public void bindAccount(@Nullable Account a) {
        View root = getView();
        if (a == null || root == null || accountId <= 0) return;
        ImageView avatar = root.findViewById(R.id.detail_avatar);
        TextView nameView = root.findViewById(R.id.detail_name);
        TextView userView = root.findViewById(R.id.detail_username);
        TextView statsView = root.findViewById(R.id.detail_stats);
        if (avatar == null || nameView == null || userView == null || statsView == null) return;
        nameView.setText(a.label());
        userView.setText(a.handle());
        AccountAdapter.loadAvatar(avatar, a, (int) (64 * getResources().getDisplayMetrics().density));
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.detail_total, a.mediaCount, a.videoCount, a.imageCount)).append('\n');
        sb.append(getString(R.string.detail_storage, Fmt.size(a.totalSize))).append('\n');
        sb.append(getString(R.string.detail_first, Fmt.dateTime(requireContext(), a.firstDownloadedAt))).append('\n');
        sb.append(getString(R.string.detail_last, Fmt.dateTime(requireContext(), a.lastDownloadedAt)));
        statsView.setText(sb);
    }

    @Override
    public void onStart() {
        super.onStart();
        Repo.get().addMediaListener(mediaListener);
        // pick up changes made from Settings (default view / sort / filter / appearance)
        String sortPref = accountId > 0 ? Prefs.ACC_MEDIA_SORT : Prefs.MEDIA_SORT;
        sortKey = Prefs.getString(sortPref, sortKey);
        viewMode = readViewMode();
        applyLayout();
        reload();
    }

    @Override
    public void onStop() {
        Repo.get().removeMediaListener(mediaListener);
        main.removeCallbacks(searchRunnable);
        super.onStop();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) reload();
    }

    // ------------------------------------------------------------------ layout & appearance

    private void applyLayout() {
        if (recycler == null) return;
        Context c = requireContext();
        float d = getResources().getDisplayMetrics().density;
        String thumb = Prefs.getString(Prefs.THUMB_SIZE, "medium");
        int targetDp = "small".equals(thumb) ? 90 : "large".equals(thumb) ? 170 : 125;
        int listDp = "small".equals(thumb) ? 56 : "large".equals(thumb) ? 96 : 72;
        String colsPref = Prefs.getString(Prefs.GRID_COLUMNS, "auto");
        int cols;
        if ("auto".equals(colsPref)) {
            float widthDp = getResources().getDisplayMetrics().widthPixels / d;
            cols = Math.max(2, Math.round(widthDp / targetDp));
        } else {
            try {
                cols = Integer.parseInt(colsPref);
            } catch (NumberFormatException e) {
                cols = 3;
            }
        }
        boolean compact = "compact".equals(Prefs.getString(Prefs.LIST_DENSITY, "comfortable"));
        boolean gridMode = MediaAdapter.VIEW_GRID.equals(viewMode);
        // Only swap the layout manager when the layout really changes. Replacing it (as happened on every
        // onStart, e.g. after watching a video or minimizing the app) throws the scroll position away.
        RecyclerView.LayoutManager current = recycler.getLayoutManager();
        boolean keepManager;
        if (gridMode) {
            keepManager = current instanceof GridLayoutManager && ((GridLayoutManager) current).getSpanCount() == cols;
        } else {
            keepManager = current instanceof LinearLayoutManager && !(current instanceof GridLayoutManager);
        }
        if (!keepManager) {
            recycler.setLayoutManager(gridMode ? new GridLayoutManager(c, cols) : new LinearLayoutManager(c));
        }
        // detail posts run edge to edge like a feed; grid and list keep their outer margin
        int pad = MediaAdapter.VIEW_DETAIL.equals(viewMode) ? 0 : (int) (8 * d);
        recycler.setPadding(pad, pad, pad, pad);
        int gridPx = (int) (targetDp * d * 1.5f);
        adapter.configure(viewMode, Prefs.getBool(Prefs.SHOW_ACCOUNT, true), Prefs.getBool(Prefs.SHOW_SIZE, true),
                compact, Math.max(160, Math.min(512, gridPx)), listDp);
        // the button shows the layout a tap will switch to: grid -> list -> detail -> grid
        String next = nextViewMode(viewMode);
        btnView.setImageResource(MediaAdapter.VIEW_LIST.equals(next) ? R.drawable.ic_view_list
                : MediaAdapter.VIEW_DETAIL.equals(next) ? R.drawable.ic_view_detail : R.drawable.ic_grid_view);
        btnView.setContentDescription(getString(MediaAdapter.VIEW_LIST.equals(next) ? R.string.cd_switch_to_list
                : MediaAdapter.VIEW_DETAIL.equals(next) ? R.string.cd_switch_to_detail : R.string.cd_switch_to_grid));
    }

    private static String readViewMode() {
        String v = Prefs.getString(Prefs.MEDIA_VIEW, MediaAdapter.VIEW_GRID);
        if (MediaAdapter.VIEW_LIST.equals(v) || MediaAdapter.VIEW_DETAIL.equals(v)) return v;
        return MediaAdapter.VIEW_GRID;
    }

    private static String nextViewMode(String current) {
        if (MediaAdapter.VIEW_GRID.equals(current)) return MediaAdapter.VIEW_LIST;
        if (MediaAdapter.VIEW_LIST.equals(current)) return MediaAdapter.VIEW_DETAIL;
        return MediaAdapter.VIEW_GRID;
    }

    // ------------------------------------------------------------------ loading

    private MediaQuery buildQuery(int limit, int offset) {
        MediaQuery q = new MediaQuery();
        q.search = searchText;
        q.sort = sortKey;
        q.filter = filterKey;
        q.accountId = accountId;
        q.limit = limit;
        q.offset = offset;
        return q;
    }

    private void reload() {
        load(true);
    }

    private void load(final boolean reset) {
        if (adapter == null || !isAdded()) return;
        final int gen = ++generation;
        final int limit = reset ? Math.max(PAGE, items.size()) : PAGE;
        final int offset = reset ? 0 : items.size();
        loading = true;
        if (items.isEmpty()) progress.setVisibility(View.VISIBLE);
        final Context appCtx = requireContext().getApplicationContext();
        IO.execute(() -> {
            List<MediaItem> res;
            int total;
            try {
                res = Repo.get().queryMedia(buildQuery(limit, offset));
                verify(appCtx, res);
                MediaQuery all = new MediaQuery();
                all.accountId = accountId;
                total = res.isEmpty() ? Repo.get().countMedia(all) : -1;
            } catch (Exception e) {
                res = new ArrayList<>();
                total = -1;
            }
            final List<MediaItem> fRes = res;
            final int fTotal = total;
            main.post(() -> {
                if (gen != generation || !isAdded()) return;
                loading = false;
                progress.setVisibility(View.INVISIBLE);
                if (reset) items.clear();
                items.addAll(fRes);
                hasMore = fRes.size() >= (reset ? limit : PAGE);
                if (fTotal >= 0) totalForScope = fTotal;
                else if (!items.isEmpty()) totalForScope = Math.max(totalForScope, items.size());
                // drop selections that no longer exist
                Set<Long> ids = new LinkedHashSet<>();
                for (MediaItem m : items) ids.add(m.id);
                selected.retainAll(ids);
                adapter.setItems(items);
                updateSelectionUi();
                updateEmpty();
            });
        });
    }

    /** Checks that files still exist (only recently unverified items) and records the result. */
    private void verify(Context c, List<MediaItem> list) {
        long now = System.currentTimeMillis();
        int checked = 0;
        for (MediaItem m : list) {
            Long last = verifiedAt.get(m.id);
            if (last != null && now - last < VERIFY_TTL_MS) continue;
            if (checked++ >= VERIFY_CAP) break;
            int status = FileStore.availability(c, Uri.parse(m.fileUri));
            if (status != m.availability) {
                Repo.get().setAvailability(m.id, status);
                m.availability = status;
            }
            verifiedAt.put(m.id, now);
        }
    }

    private void updateEmpty() {
        boolean empty = items.isEmpty() && !loading;
        recycler.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) return;
        boolean filtering = !TextUtil.isEmpty(searchText) || !MediaQuery.FILTER_ALL.equals(filterKey);
        if (filtering && totalForScope > 0) {
            emptyTitle.setText(R.string.no_results);
            emptyMessage.setText(R.string.no_results_hint);
            emptyAction.setText(R.string.clear_filters);
            emptyAction.setVisibility(View.VISIBLE);
            emptyAction.setOnClickListener(x -> clearFilters());
        } else {
            emptyTitle.setText(accountId > 0 ? R.string.no_account_media : R.string.no_downloads);
            emptyMessage.setText(accountId > 0 ? R.string.no_account_media_hint : R.string.no_downloads_hint);
            if (accountId > 0 || !(getActivity() instanceof MainActivity)) {
                emptyAction.setVisibility(View.GONE);
            } else {
                emptyAction.setVisibility(View.VISIBLE);
                emptyAction.setText(R.string.paste_a_link);
                emptyAction.setOnClickListener(x -> ((MainActivity) requireActivity()).showHome());
            }
        }
    }

    private void clearFilters() {
        searchInput.setText("");
        searchText = "";
        filterKey = MediaQuery.FILTER_ALL;
        Prefs.putString(accountId > 0 ? Prefs.ACC_MEDIA_FILTER : Prefs.MEDIA_FILTER, filterKey);
        ((Chip) chips.getChildAt(0)).setChecked(true);
        reload();
    }

    // ------------------------------------------------------------------ sorting

    private void showSortMenu(View anchor) {
        androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(requireContext(), anchor);
        final String[] keys = accountId > 0 ? SortOptions.ACCOUNT_MEDIA_KEYS : SortOptions.MEDIA_KEYS;
        int[] labels = accountId > 0 ? SortOptions.ACCOUNT_MEDIA_LABELS : SortOptions.MEDIA_LABELS;
        Menu menu = pm.getMenu();
        for (int i = 0; i < keys.length; i++) {
            MenuItem mi = menu.add(Menu.NONE, i, i, labels[i]);
            mi.setCheckable(true);
            mi.setChecked(keys[i].equals(sortKey));
        }
        menu.setGroupCheckable(Menu.NONE, true, true);
        pm.setOnMenuItemClickListener(item -> {
            sortKey = keys[item.getItemId()];
            Prefs.putString(accountId > 0 ? Prefs.ACC_MEDIA_SORT : Prefs.MEDIA_SORT, sortKey);
            reload();
            recycler.scrollToPosition(0);
            return true;
        });
        pm.show();
    }

    // ------------------------------------------------------------------ selection

    private List<MediaItem> selectedItems() {
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem m : items) if (selected.contains(m.id)) out.add(m);
        return out;
    }

    private void toggleSelection(MediaItem m) {
        if (selected.contains(m.id)) selected.remove(m.id);
        else selected.add(m.id);
        adapter.notifySelectionChanged(m.id);
        updateSelectionUi();
    }

    private void selectAllLoaded() {
        for (MediaItem m : items) selected.add(m.id);
        adapter.notifyAllSelectionChanged();
        updateSelectionUi();
    }

    private void clearSelection() {
        if (selected.isEmpty()) return;
        selected.clear();
        adapter.notifyAllSelectionChanged();
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        boolean sel = !selected.isEmpty();
        if (selectionBar == null) return;
        // the selection bar lives in the collapsible part of the top bar: bring it back into view
        if (sel && !wasSelecting && appBar != null) appBar.setExpanded(true, true);
        wasSelecting = sel;
        selectionBar.setVisibility(sel ? View.VISIBLE : View.GONE);
        searchBar.setVisibility(sel ? View.GONE : View.VISIBLE);
        selCount.setText(getString(R.string.n_selected, selected.size()));
        if (backCallback != null) backCallback.setEnabled(sel);
    }

    // ------------------------------------------------------------------ item actions

    private void onItemClick(MediaItem m) {
        if (!selected.isEmpty()) {
            toggleSelection(m);
            return;
        }
        if (m.availability == MediaItem.AVAIL_MISSING) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.file_missing_title)
                    .setMessage(R.string.file_missing_message)
                    .setPositiveButton(R.string.remove_record, (d, w) -> removeRecords(singleList(m)))
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }
        if (m.availability == MediaItem.AVAIL_INACCESSIBLE) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.file_inaccessible_title)
                    .setMessage(R.string.file_inaccessible_message)
                    .setPositiveButton(R.string.ok, null)
                    .setNeutralButton(R.string.remove_record, (d, w) -> removeRecords(singleList(m)))
                    .show();
            return;
        }
        List<MediaItem> group = adapter.isDetail() ? adapter.groupOf(m) : null;
        if (group != null && group.indexOf(m) >= 0) {
            FileActions.open(requireContext(), group, group.indexOf(m));
            return;
        }
        int index = items.indexOf(m);
        if (index >= 0) FileActions.open(requireContext(), items, index);
        else FileActions.open(requireContext(), m);
    }

    private static List<MediaItem> singleList(MediaItem m) {
        List<MediaItem> l = new ArrayList<>();
        l.add(m);
        return l;
    }

    private void showItemMenu(final MediaItem m, View anchor) {
        androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(requireContext(), anchor);
        pm.getMenu().add(Menu.NONE, 1, 1, R.string.details);
        pm.getMenu().add(Menu.NONE, 2, 2, R.string.share);
        pm.getMenu().add(Menu.NONE, 3, 3, R.string.rename);
        pm.getMenu().add(Menu.NONE, 4, 4, R.string.open_original_post);
        pm.getMenu().add(Menu.NONE, 5, 5, R.string.delete);
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    Dialogs.message(requireContext(), R.string.details, FileActions.details(requireContext(), m));
                    break;
                case 2:
                    if (m.availability == MediaItem.AVAIL_OK) FileActions.share(requireContext(), singleList(m));
                    else Toast.makeText(requireContext(), R.string.cannot_access_file, Toast.LENGTH_SHORT).show();
                    break;
                case 3:
                    renameItem(m);
                    break;
                case 4:
                    FileActions.openPost(requireContext(), m.postUrl);
                    break;
                case 5:
                    confirmDelete(singleList(m));
                    break;
                default:
                    break;
            }
            return true;
        });
        pm.show();
    }

    private void shareGroup(List<MediaItem> group) {
        List<MediaItem> ok = new ArrayList<>();
        for (MediaItem m : group) if (m.availability == MediaItem.AVAIL_OK) ok.add(m);
        if (ok.isEmpty()) Toast.makeText(requireContext(), R.string.cannot_access_file, Toast.LENGTH_SHORT).show();
        else FileActions.share(requireContext(), ok);
    }

    /** Menu of the detail layout's post card. Details and rename only apply when the post has a single file. */
    private void showPostMenu(final List<MediaItem> group, View anchor) {
        if (group == null || group.isEmpty()) return;
        final MediaItem first = group.get(0);
        final boolean hasText = !TextUtil.isEmpty(first.postText);
        androidx.appcompat.widget.PopupMenu pm = new androidx.appcompat.widget.PopupMenu(requireContext(), anchor);
        Menu menu = pm.getMenu();
        if (group.size() == 1) menu.add(Menu.NONE, 1, 1, R.string.details);
        if (group.size() == 1) menu.add(Menu.NONE, 2, 2, R.string.rename);
        menu.add(Menu.NONE, 3, 3, R.string.open_original_post);
        if (hasText) menu.add(Menu.NONE, 4, 4, R.string.copy_post_text);
        if (group.size() > 1) menu.add(Menu.NONE, 5, 5, R.string.select_post_media);
        menu.add(Menu.NONE, 6, 6, R.string.delete);
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    Dialogs.message(requireContext(), R.string.details, FileActions.details(requireContext(), first));
                    break;
                case 2:
                    renameItem(first);
                    break;
                case 3:
                    FileActions.openPost(requireContext(), first.postUrl);
                    break;
                case 4:
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("post", first.postText));
                        Toast.makeText(requireContext(), R.string.post_text_copied, Toast.LENGTH_SHORT).show();
                    }
                    break;
                case 5:
                    for (MediaItem m : group) selected.add(m.id);
                    adapter.notifyAllSelectionChanged();
                    updateSelectionUi();
                    break;
                case 6:
                    confirmDelete(new ArrayList<>(group));
                    break;
                default:
                    break;
            }
            return true;
        });
        pm.show();
    }

    private void renameItem(final MediaItem m) {
        String name = m.filename;
        int dot = name.lastIndexOf('.');
        final String ext = dot > 0 ? name.substring(dot) : "";
        String base = dot > 0 ? name.substring(0, dot) : name;
        Dialogs.textInput(requireContext(), R.string.rename, base, getString(R.string.file_name), null, text -> {
            final String clean = TextUtil.sanitizeFileName(text);
            if (clean.isEmpty()) {
                Toast.makeText(requireContext(), R.string.invalid_name, Toast.LENGTH_SHORT).show();
                return;
            }
            final String newName = clean + ext;
            if (newName.equals(m.filename)) return;
            final Context appCtx = requireContext().getApplicationContext();
            IO.execute(() -> {
                Uri res = null;
                boolean denied = false;
                try {
                    res = FileStore.rename(appCtx, Uri.parse(m.fileUri), newName);
                } catch (SecurityException e) {
                    denied = true;
                } catch (Exception ignored) {
                    // handled as failure below
                }
                final Uri fRes = res;
                final boolean fDenied = denied;
                if (fRes != null) Repo.get().updateMediaName(m.id, newName, fRes.toString());
                main.post(() -> {
                    if (!isAdded()) return;
                    if (fRes == null) {
                        Toast.makeText(requireContext(), fDenied ? R.string.rename_not_allowed : R.string.rename_failed,
                                Toast.LENGTH_LONG).show();
                    }
                });
            });
        });
    }

    // ------------------------------------------------------------------ deletion

    private void confirmDelete(final List<MediaItem> list) {
        if (list.isEmpty()) return;
        String msg = list.size() == 1
                ? getString(R.string.delete_one_message, list.get(0).filename)
                : getString(R.string.delete_many_message, list.size());
        Dialogs.confirm(requireContext(), R.string.delete_title, msg, R.string.delete, () -> deleteItems(list));
    }

    private void removeRecords(final List<MediaItem> list) {
        final List<Long> ids = new ArrayList<>();
        for (MediaItem m : list) ids.add(m.id);
        IO.execute(() -> Repo.get().deleteMedia(ids));
    }

    private void recordDeleted(final List<MediaItem> list) {
        removeRecords(list);
        selected.clear();
        updateSelectionUi();
    }

    /** Deletes files and records. Items the app may not delete directly go through the system consent flow. */
    private void deleteItems(final List<MediaItem> list) {
        final Context appCtx = requireContext().getApplicationContext();
        IO.execute(() -> {
            final List<MediaItem> done = new ArrayList<>();
            final List<MediaItem> needAccess = new ArrayList<>();
            IntentSender consent = null;
            int failed = 0;
            for (MediaItem m : list) {
                Uri uri = Uri.parse(m.fileUri);
                if (m.availability == MediaItem.AVAIL_MISSING || FileStore.availability(appCtx, uri) == MediaItem.AVAIL_MISSING) {
                    done.add(m); // nothing left on storage, only the record remains
                    continue;
                }
                try {
                    if (FileStore.delete(appCtx, uri)) done.add(m);
                    else failed++;
                } catch (SecurityException e) {
                    needAccess.add(m);
                    if (Build.VERSION.SDK_INT == 29 && consent == null && e instanceof RecoverableSecurityException) {
                        consent = ((RecoverableSecurityException) e).getUserAction().getActionIntent().getIntentSender();
                    }
                } catch (Exception e) {
                    failed++;
                }
            }
            if (!done.isEmpty()) removeRecords(done);
            final int fFailed = failed;
            final IntentSender fConsent = consent;
            main.post(() -> {
                if (!isAdded()) return;
                selected.removeAll(idsOf(done));
                updateSelectionUi();
                if (!needAccess.isEmpty()) requestAccess(needAccess, fConsent);
                else if (fFailed > 0) Toast.makeText(requireContext(), getString(R.string.delete_failed_n, fFailed), Toast.LENGTH_LONG).show();
            });
        });
    }

    private static List<Long> idsOf(List<MediaItem> l) {
        List<Long> ids = new ArrayList<>();
        for (MediaItem m : l) ids.add(m.id);
        return ids;
    }

    private void requestAccess(List<MediaItem> list, IntentSender api29Consent) {
        try {
            IntentSender sender = null;
            if (Build.VERSION.SDK_INT >= 30) {
                List<Uri> uris = new ArrayList<>();
                for (MediaItem m : list) uris.add(Uri.parse(m.fileUri));
                sender = MediaStore.createDeleteRequest(requireContext().getContentResolver(), uris).getIntentSender();
            } else if (Build.VERSION.SDK_INT == 29) {
                sender = api29Consent;
            }
            if (sender == null) {
                Toast.makeText(requireContext(), R.string.delete_not_allowed, Toast.LENGTH_LONG).show();
                return;
            }
            pendingDelete = list;
            deleteLauncher.launch(new IntentSenderRequest.Builder(sender).build());
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.delete_not_allowed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onDestroyView() {
        main.removeCallbacksAndMessages(null);
        if (getParentFragment() instanceof DownloadsFragment) {
            ((DownloadsFragment) getParentFragment()).unbindHeader(titleHeader);
        }
        titleHeader = null;
        accountProfile = null;
        appBar = null;
        super.onDestroyView();
    }
}
