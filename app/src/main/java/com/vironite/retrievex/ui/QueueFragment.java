package com.vironite.retrievex.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.vironite.retrievex.R;
import com.vironite.retrievex.data.DownloadTask;
import com.vironite.retrievex.download.DownloadManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Live view of the download queue with pause / resume / cancel / retry / open / share controls. */
public class QueueFragment extends Fragment implements DownloadManager.Listener {
    private TaskAdapter adapter;
    private TextView summary;
    private View empty;
    private RecyclerView recycler;
    private View header;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_queue, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        header = v.findViewById(R.id.downloads_header);
        if (getParentFragment() instanceof DownloadsFragment) {
            ((DownloadsFragment) getParentFragment()).bindHeader(header);
        }
        summary = v.findViewById(R.id.queue_summary);
        empty = v.findViewById(R.id.queue_empty);
        recycler = v.findViewById(R.id.queue_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(requireContext()));
        recycler.setItemAnimator(null);
        adapter = new TaskAdapter(new TaskAdapter.Listener() {
            @Override
            public void onPause(DownloadTask t) {
                DownloadManager.get().pause(t.id);
            }

            @Override
            public void onResume(DownloadTask t) {
                DownloadManager.get().resume(t.id);
            }

            @Override
            public void onCancel(final DownloadTask t) {
                Dialogs.confirm(requireContext(), R.string.cancel_download_title,
                        getString(R.string.cancel_download_message, t.targetName), R.string.yes_cancel,
                        () -> DownloadManager.get().cancel(t.id));
            }

            @Override
            public void onRetry(DownloadTask t) {
                DownloadManager.get().retry(t.id);
            }

            @Override
            public void onOpen(DownloadTask t) {
                if (t.mediaItemId > 0) {
                    MediaViewerActivity.startById(requireContext(), t.mediaItemId, t.destinationUri, t.mimeType);
                } else if (t.destinationUri != null) {
                    FileActions.openUri(requireContext(), t.destinationUri, t.mimeType);
                }
            }

            @Override
            public void onShare(DownloadTask t) {
                if (t.destinationUri == null) return;
                List<String> u = new ArrayList<>();
                List<String> m = new ArrayList<>();
                u.add(t.destinationUri);
                m.add(t.mimeType);
                FileActions.shareUris(requireContext(), u, m);
            }

            @Override
            public void onFolder(DownloadTask t) {
                FileActions.openFolder(requireContext());
            }

            @Override
            public void onRemove(DownloadTask t) {
                // Removes the history entry only; the downloaded file stays in the library.
                DownloadManager.get().remove(t.id);
            }
        });
        recycler.setAdapter(adapter);
        v.findViewById(R.id.btn_clear_finished).setOnClickListener(x -> Dialogs.confirm(requireContext(),
                R.string.clear_finished_title, getString(R.string.clear_finished_message), R.string.clear,
                () -> DownloadManager.get().clearFinished()));
    }

    @Override
    public void onDestroyView() {
        if (getParentFragment() instanceof DownloadsFragment) {
            ((DownloadsFragment) getParentFragment()).unbindHeader(header);
        }
        header = null;
        super.onDestroyView();
    }

    @Override
    public void onStart() {
        super.onStart();
        DownloadManager.get().addListener(this);
        reload();
    }

    @Override
    public void onStop() {
        DownloadManager.get().removeListener(this);
        super.onStop();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) reload();
    }

    private void reload() {
        if (adapter == null) return;
        adapter.setAll(DownloadManager.get().snapshot());
        refreshSummary();
    }

    private void refreshSummary() {
        if (summary == null) return;
        int active = 0;
        int waiting = 0;
        int done = 0;
        for (DownloadTask t : adapter.all()) {
            if (t.isActive()) active++;
            else if (t.status == DownloadTask.WAITING || t.status == DownloadTask.PAUSED) waiting++;
            else if (t.status == DownloadTask.COMPLETED) done++;
        }
        summary.setText(getString(R.string.queue_summary, active, waiting, done));
        boolean none = adapter.size() == 0;
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        recycler.setVisibility(none ? View.INVISIBLE : View.VISIBLE);
    }

    @Override
    public void onTaskChanged(DownloadTask snapshot) {
        if (adapter == null) return;
        adapter.update(snapshot);
        refreshSummary();
    }

    @Override
    public void onTaskRemoved(String id) {
        if (adapter == null) return;
        adapter.remove(id);
        refreshSummary();
    }

    @Override
    public void onReset() {
        reload();
    }

    /** Number of unfinished tasks, used for the badge text on the Queue toggle. */
    public static int unfinishedCount() {
        int n = 0;
        for (DownloadTask t : DownloadManager.get().snapshot()) if (!t.isTerminal()) n++;
        return n;
    }

    @SuppressWarnings("unused")
    private static <T> List<T> empty() {
        return Collections.emptyList();
    }
}
