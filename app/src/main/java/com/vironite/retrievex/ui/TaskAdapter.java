package com.vironite.retrievex.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.vironite.retrievex.R;
import com.vironite.retrievex.data.DownloadTask;
import com.vironite.retrievex.util.Fmt;
import com.vironite.retrievex.util.TextUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Adapter for the download queue. Progress ticks update a single row instead of the whole list. */
public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.VH> {
    public interface Listener {
        void onPause(DownloadTask t);

        void onResume(DownloadTask t);

        void onCancel(DownloadTask t);

        void onRetry(DownloadTask t);

        void onOpen(DownloadTask t);

        void onShare(DownloadTask t);

        void onFolder(DownloadTask t);

        void onRemove(DownloadTask t);
    }

    private final List<DownloadTask> list = new ArrayList<>();
    private final Listener listener;
    private static final Object PROGRESS = new Object();

    public TaskAdapter(Listener l) {
        this.listener = l;
    }

    private static final Comparator<DownloadTask> ORDER = new Comparator<DownloadTask>() {
        @Override
        public int compare(DownloadTask a, DownloadTask b) {
            boolean ta = a.isTerminal();
            boolean tb = b.isTerminal();
            if (ta != tb) return ta ? 1 : -1;
            if (!ta) return Long.compare(a.createdAt, b.createdAt);
            long ca = a.completedAt > 0 ? a.completedAt : a.createdAt;
            long cb = b.completedAt > 0 ? b.completedAt : b.createdAt;
            return Long.compare(cb, ca);
        }
    };

    public void setAll(List<DownloadTask> tasks) {
        list.clear();
        list.addAll(tasks);
        Collections.sort(list, ORDER);
        notifyDataSetChanged();
    }

    public int size() {
        return list.size();
    }

    public List<DownloadTask> all() {
        return list;
    }

    public void update(DownloadTask t) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(t.id)) {
                boolean statusChanged = list.get(i).status != t.status || !TextUtil.nz(list.get(i).note).equals(TextUtil.nz(t.note));
                list.set(i, t);
                if (statusChanged) {
                    Collections.sort(list, ORDER);
                    notifyDataSetChanged();
                } else {
                    notifyItemChanged(i, PROGRESS);
                }
                return;
            }
        }
        list.add(t);
        Collections.sort(list, ORDER);
        notifyDataSetChanged();
    }

    public void remove(String id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                notifyItemRemoved(i);
                return;
            }
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_task, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        bind(h, list.get(position));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position, @NonNull List<Object> payloads) {
        bind(h, list.get(position));
    }

    @Override
    public int getItemCount() {
        return list.size();
    }

    private void bind(VH h, final DownloadTask t) {
        Context c = h.itemView.getContext();
        h.title.setText(t.targetName);
        h.status.setText(statusText(c, t));
        int color;
        if (t.status == DownloadTask.FAILED) color = R.color.status_error;
        else if (t.status == DownloadTask.COMPLETED) color = R.color.status_ok;
        else if (t.status == DownloadTask.PAUSED || t.status == DownloadTask.WAITING) color = R.color.status_warn;
        else color = 0;
        if (color != 0) h.status.setTextColor(c.getColor(color));
        else {
            android.util.TypedValue tv = new android.util.TypedValue();
            c.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true);
            if (tv.resourceId != 0) h.status.setTextColor(c.getColorStateList(tv.resourceId));
            else h.status.setTextColor(tv.data);
        }

        boolean showBar = t.status == DownloadTask.DOWNLOADING || t.status == DownloadTask.FETCHING
                || t.status == DownloadTask.MERGING || t.status == DownloadTask.PAUSED || t.status == DownloadTask.WAITING;
        h.progress.setVisibility(showBar ? View.VISIBLE : View.GONE);
        if (showBar) {
            int pct = t.percent();
            boolean indeterminate = pct < 0 && t.status != DownloadTask.PAUSED && t.status != DownloadTask.WAITING;
            if (indeterminate != h.progress.isIndeterminate()) {
                h.progress.setVisibility(View.INVISIBLE);
                h.progress.setIndeterminate(indeterminate);
                h.progress.setVisibility(View.VISIBLE);
            }
            if (!indeterminate) h.progress.setProgress(Math.max(0, pct));
        }

        h.detail.setText(detailText(c, t));
        h.detail.setVisibility(h.detail.getText().length() == 0 ? View.GONE : View.VISIBLE);

        boolean active = t.status == DownloadTask.DOWNLOADING || t.status == DownloadTask.FETCHING || t.status == DownloadTask.WAITING;
        boolean completedWithFile = t.status == DownloadTask.COMPLETED && t.destinationUri != null;
        vis(h.pause, active);
        vis(h.resume, t.status == DownloadTask.PAUSED);
        vis(h.cancel, !t.isTerminal());
        vis(h.retry, t.status == DownloadTask.FAILED || t.status == DownloadTask.CANCELED);
        vis(h.open, completedWithFile);
        vis(h.share, completedWithFile);
        vis(h.folder, completedWithFile);
        vis(h.remove, t.isTerminal());

        h.pause.setOnClickListener(v -> listener.onPause(t));
        h.resume.setOnClickListener(v -> listener.onResume(t));
        h.cancel.setOnClickListener(v -> listener.onCancel(t));
        h.retry.setOnClickListener(v -> listener.onRetry(t));
        h.open.setOnClickListener(v -> listener.onOpen(t));
        h.share.setOnClickListener(v -> listener.onShare(t));
        h.folder.setOnClickListener(v -> listener.onFolder(t));
        h.remove.setOnClickListener(v -> listener.onRemove(t));
        h.itemView.setOnClickListener(completedWithFile ? v -> listener.onOpen(t) : null);
        h.itemView.setClickable(completedWithFile);
    }

    private static void vis(View v, boolean show) {
        v.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static String statusText(Context c, DownloadTask t) {
        switch (t.status) {
            case DownloadTask.WAITING:
                return "wifi".equals(t.note) ? c.getString(R.string.st_waiting_wifi) : c.getString(R.string.st_waiting);
            case DownloadTask.FETCHING:
                return c.getString(R.string.st_fetching);
            case DownloadTask.DOWNLOADING: {
                int p = t.percent();
                String s = p >= 0 ? c.getString(R.string.st_downloading_pct, p) : c.getString(R.string.st_downloading);
                String sp = Fmt.speed(t.speed);
                return sp.isEmpty() ? s : s + " \u00B7 " + sp;
            }
            case DownloadTask.MERGING:
                return c.getString(R.string.st_merging);
            case DownloadTask.COMPLETED:
                return "skipped".equals(t.note) ? c.getString(R.string.st_skipped) : c.getString(R.string.st_completed);
            case DownloadTask.FAILED:
                return TextUtil.isEmpty(t.errorMessage) ? c.getString(R.string.st_failed) : t.errorMessage;
            case DownloadTask.PAUSED:
                return TextUtil.isEmpty(t.errorMessage) ? c.getString(R.string.st_paused) : c.getString(R.string.st_paused) + " \u00B7 " + t.errorMessage;
            case DownloadTask.CANCELED:
                return c.getString(R.string.st_canceled);
            default:
                return "";
        }
    }

    private static String detailText(Context c, DownloadTask t) {
        StringBuilder sb = new StringBuilder();
        if (!TextUtil.isEmpty(t.accountUsername)) sb.append("@").append(t.accountUsername);
        if (t.status == DownloadTask.COMPLETED) {
            if (t.totalBytes > 0) append(sb, Fmt.size(t.totalBytes));
            append(sb, Fmt.dateTime(c, t.completedAt));
        } else if (t.status == DownloadTask.DOWNLOADING || t.status == DownloadTask.MERGING || t.status == DownloadTask.PAUSED) {
            if (t.totalBytes > 0) append(sb, Fmt.size(t.bytesDownloaded) + " / " + Fmt.size(t.totalBytes));
            else if (t.bytesDownloaded > 0) append(sb, Fmt.size(t.bytesDownloaded));
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, String part) {
        if (TextUtil.isEmpty(part)) return;
        if (sb.length() > 0) sb.append(" \u00B7 ");
        sb.append(part);
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView status;
        final TextView detail;
        final LinearProgressIndicator progress;
        final ImageButton pause;
        final ImageButton resume;
        final ImageButton cancel;
        final ImageButton retry;
        final ImageButton open;
        final ImageButton share;
        final ImageButton folder;
        final ImageButton remove;

        VH(View v) {
            super(v);
            title = v.findViewById(R.id.task_title);
            status = v.findViewById(R.id.task_status);
            detail = v.findViewById(R.id.task_detail);
            progress = v.findViewById(R.id.task_progress);
            pause = v.findViewById(R.id.task_btn_pause);
            resume = v.findViewById(R.id.task_btn_resume);
            cancel = v.findViewById(R.id.task_btn_cancel);
            retry = v.findViewById(R.id.task_btn_retry);
            open = v.findViewById(R.id.task_btn_open);
            share = v.findViewById(R.id.task_btn_share);
            folder = v.findViewById(R.id.task_btn_folder);
            remove = v.findViewById(R.id.task_btn_remove);
        }
    }
}
