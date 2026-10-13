package com.vironite.retrievex.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.vironite.retrievex.R;
import com.vironite.retrievex.data.Account;
import com.vironite.retrievex.util.Fmt;
import com.vironite.retrievex.util.TextUtil;

/** Account cards. The whole card is clickable. */
public class AccountAdapter extends ListAdapter<Account, AccountAdapter.VH> {
    public interface Listener {
        void onClick(Account a);
    }

    private static final DiffUtil.ItemCallback<Account> DIFF = new DiffUtil.ItemCallback<Account>() {
        @Override
        public boolean areItemsTheSame(@NonNull Account a, @NonNull Account b) {
            return a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull Account a, @NonNull Account b) {
            return a.mediaCount == b.mediaCount && a.videoCount == b.videoCount && a.imageCount == b.imageCount
                    && a.totalSize == b.totalSize && a.lastDownloadedAt == b.lastDownloadedAt
                    && TextUtil.nz(a.label()).equals(TextUtil.nz(b.label()))
                    && TextUtil.nz(a.username).equals(TextUtil.nz(b.username))
                    && TextUtil.nz(a.cachedAvatarPath).equals(TextUtil.nz(b.cachedAvatarPath))
                    && TextUtil.nz(a.avatarUrl).equals(TextUtil.nz(b.avatarUrl));
        }
    };

    private final Listener listener;

    public AccountAdapter(Listener l) {
        super(DIFF);
        this.listener = l;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_account, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final Account a = getItem(position);
        Context c = h.itemView.getContext();
        h.name.setText(a.label());
        h.username.setText(a.handle());
        h.stats.setText(c.getString(R.string.account_stats, a.mediaCount, a.videoCount, a.imageCount));
        StringBuilder act = new StringBuilder(c.getString(R.string.last_download, Fmt.relative(a.lastDownloadedAt)));
        if (a.totalSize > 0) act.append(" \u00B7 ").append(Fmt.size(a.totalSize));
        h.activity.setText(act);
        loadAvatar(h.avatar, a, (int) (56 * c.getResources().getDisplayMetrics().density));
        h.itemView.setOnClickListener(v -> listener.onClick(a));
    }

    public static void loadAvatar(ImageView view, Account a, int px) {
        if (a.cachedAvatarPath != null && new java.io.File(a.cachedAvatarPath).exists()) {
            ImageLoader.loadFile(view, a.cachedAvatarPath, px);
        } else {
            ImageLoader.loadUrl(view, a.avatarUrl, px);
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name;
        final TextView username;
        final TextView stats;
        final TextView activity;

        VH(View v) {
            super(v);
            avatar = v.findViewById(R.id.acc_avatar);
            name = v.findViewById(R.id.acc_name);
            username = v.findViewById(R.id.acc_username);
            stats = v.findViewById(R.id.acc_stats);
            activity = v.findViewById(R.id.acc_activity);
        }
    }
}
