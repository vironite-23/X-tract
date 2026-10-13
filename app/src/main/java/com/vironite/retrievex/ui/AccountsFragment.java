package com.vironite.retrievex.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.textfield.TextInputEditText;
import com.vironite.retrievex.R;
import com.vironite.retrievex.data.Account;
import com.vironite.retrievex.data.Repo;
import com.vironite.retrievex.util.Prefs;
import com.vironite.retrievex.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local record of accounts the user has downloaded from, with live search and sorting. */
public class AccountsFragment extends Fragment {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private AccountAdapter adapter;
    private RecyclerView recycler;
    private View emptyView;
    private TextView emptyTitle;
    private TextView emptyMessage;
    private TextView sortLabel;
    private String sortKey;
    private String searchText = "";
    private int generation;
    private int totalAccounts;

    private final Runnable searchRunnable = new Runnable() {
        @Override
        public void run() {
            reload();
        }
    };

    private final Repo.Listener listener = new Repo.Listener() {
        @Override
        public void onChanged() {
            if (isAdded()) reload();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_accounts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        sortKey = Prefs.getString(Prefs.ACCOUNT_SORT, Repo.ACC_ACTIVITY_DESC);
        recycler = v.findViewById(R.id.accounts_recycler);
        emptyView = v.findViewById(R.id.empty_view);
        emptyTitle = v.findViewById(R.id.empty_title);
        emptyMessage = v.findViewById(R.id.empty_message);
        sortLabel = v.findViewById(R.id.sort_label);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setItemAnimator(null);
        adapter = new AccountAdapter(a -> {
            Intent i = new Intent(requireContext(), AccountDetailActivity.class);
            i.putExtra(AccountDetailActivity.EXTRA_ACCOUNT_ID, a.id);
            startActivity(i);
        });
        recycler.setAdapter(adapter);

        TextInputEditText input = v.findViewById(R.id.search_input);
        input.setText(searchText);
        input.addTextChangedListener(new TextWatcher() {
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
                main.postDelayed(searchRunnable, 200);
            }
        });
        v.findViewById(R.id.btn_sort).setOnClickListener(this::showSortMenu);
        updateSortLabel();
    }

    @Override
    public void onStart() {
        super.onStart();
        Repo.get().addAccountListener(listener);
        String saved = Prefs.getString(Prefs.ACCOUNT_SORT, sortKey);
        if (!saved.equals(sortKey)) {
            sortKey = saved;
            updateSortLabel();
        }
        reload();
    }

    @Override
    public void onStop() {
        Repo.get().removeAccountListener(listener);
        main.removeCallbacks(searchRunnable);
        super.onStop();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) reload();
    }

    private void reload() {
        if (adapter == null || !isAdded()) return;
        final int gen = ++generation;
        final String search = searchText;
        final String sort = sortKey;
        IO.execute(() -> {
            List<Account> list;
            int total;
            try {
                list = Repo.get().queryAccounts(search, sort);
                total = TextUtil.isEmpty(search) ? list.size() : Repo.get().queryAccounts("", sort).size();
            } catch (Exception e) {
                list = new ArrayList<>();
                total = 0;
            }
            final List<Account> fList = list;
            final int fTotal = total;
            main.post(() -> {
                if (gen != generation || !isAdded()) return;
                totalAccounts = fTotal;
                adapter.submitList(fList);
                boolean empty = fList.isEmpty();
                emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
                recycler.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
                if (empty) {
                    boolean searching = !TextUtil.isEmpty(searchText) && totalAccounts > 0;
                    emptyTitle.setText(searching ? R.string.no_results : R.string.no_accounts);
                    emptyMessage.setText(searching ? R.string.no_results_hint_accounts : R.string.no_accounts_hint);
                }
            });
        });
    }

    private void showSortMenu(View anchor) {
        PopupMenu pm = new PopupMenu(requireContext(), anchor);
        Menu menu = pm.getMenu();
        for (int i = 0; i < SortOptions.ACCOUNT_KEYS.length; i++) {
            MenuItem mi = menu.add(Menu.NONE, i, i, SortOptions.ACCOUNT_LABELS[i]);
            mi.setCheckable(true);
            mi.setChecked(SortOptions.ACCOUNT_KEYS[i].equals(sortKey));
        }
        menu.setGroupCheckable(Menu.NONE, true, true);
        pm.setOnMenuItemClickListener(item -> {
            sortKey = SortOptions.ACCOUNT_KEYS[item.getItemId()];
            Prefs.putString(Prefs.ACCOUNT_SORT, sortKey);
            updateSortLabel();
            reload();
            recycler.scrollToPosition(0);
            return true;
        });
        pm.show();
    }

    private void updateSortLabel() {
        if (sortLabel == null) return;
        int idx = SortOptions.indexOf(SortOptions.ACCOUNT_KEYS, sortKey);
        sortLabel.setText(getString(R.string.sorted_by, getString(SortOptions.ACCOUNT_LABELS[idx])));
    }

    @Override
    public void onDestroyView() {
        main.removeCallbacksAndMessages(null);
        super.onDestroyView();
    }
}
