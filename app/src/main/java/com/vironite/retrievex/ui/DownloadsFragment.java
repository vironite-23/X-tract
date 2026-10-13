package com.vironite.retrievex.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.vironite.retrievex.R;
import com.vironite.retrievex.data.DownloadTask;
import com.vironite.retrievex.download.DownloadManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Downloads tab: toggles between the persistent media library and the live download queue.
 *
 * <p>The title row with the Library/Queue toggle is drawn by the two child screens themselves (so the
 * library can scroll it away while keeping its filter chips pinned). Each child hands its header view to
 * {@link #bindHeader(View)}; this fragment keeps every toggle in sync.
 */
public class DownloadsFragment extends Fragment implements DownloadManager.Listener {
    private static final String TAG_LIB = "lib";
    private static final String TAG_QUEUE = "queue";
    private static final String STATE_QUEUE = "queue_visible";

    private final List<MaterialButtonToggleGroup> toggles = new ArrayList<>();
    private boolean queueVisible;
    private boolean syncing;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_downloads, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        FragmentManager fm = getChildFragmentManager();
        if (savedInstanceState == null) {
            fm.beginTransaction()
                    .add(R.id.downloads_container, new MediaLibraryFragment(), TAG_LIB)
                    .add(R.id.downloads_container, new QueueFragment(), TAG_QUEUE)
                    .commitNow();
        } else {
            queueVisible = savedInstanceState.getBoolean(STATE_QUEUE, false);
        }
        applyVisibility();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_QUEUE, queueVisible);
    }

    @Override
    public void onStart() {
        super.onStart();
        DownloadManager.get().addListener(this);
        updateBadge();
    }

    @Override
    public void onStop() {
        DownloadManager.get().removeListener(this);
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        toggles.clear();
        super.onDestroyView();
    }

    /** Called by a child screen with its title row; wires the Library/Queue toggle inside it. */
    void bindHeader(@Nullable View header) {
        if (header == null) return;
        final MaterialButtonToggleGroup group = header.findViewById(R.id.downloads_toggle);
        if (group == null || toggles.contains(group)) return;
        toggles.add(group);
        syncing = true;
        group.check(queueVisible ? R.id.toggle_queue : R.id.toggle_library);
        syncing = false;
        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked || syncing) return;
            setQueueVisible(checkedId == R.id.toggle_queue);
        });
        updateBadge();
    }

    /** Called by a child screen when its view is destroyed. */
    void unbindHeader(@Nullable View header) {
        if (header == null) return;
        MaterialButtonToggleGroup group = header.findViewById(R.id.downloads_toggle);
        if (group != null) toggles.remove(group);
    }

    public void showQueue(boolean queue) {
        setQueueVisible(queue);
    }

    private void setQueueVisible(boolean queue) {
        queueVisible = queue;
        syncToggles();
        applyVisibility();
    }

    private void syncToggles() {
        syncing = true;
        for (MaterialButtonToggleGroup g : toggles) {
            g.check(queueVisible ? R.id.toggle_queue : R.id.toggle_library);
        }
        syncing = false;
    }

    private void applyVisibility() {
        FragmentManager fm = getChildFragmentManager();
        Fragment lib = fm.findFragmentByTag(TAG_LIB);
        Fragment queue = fm.findFragmentByTag(TAG_QUEUE);
        if (lib == null || queue == null) return;
        FragmentTransaction tx = fm.beginTransaction();
        if (queueVisible) {
            tx.hide(lib).show(queue);
        } else {
            tx.show(lib).hide(queue);
        }
        tx.commitAllowingStateLoss();
    }

    private void updateBadge() {
        int n = QueueFragment.unfinishedCount();
        for (MaterialButtonToggleGroup g : toggles) {
            MaterialButton queueButton = g.findViewById(R.id.toggle_queue);
            if (queueButton == null) continue;
            queueButton.setText(n > 0 ? getString(R.string.queue_with_count, n) : getString(R.string.queue));
        }
    }

    @Override
    public void onTaskChanged(DownloadTask snapshot) {
        updateBadge();
    }

    @Override
    public void onTaskRemoved(String id) {
        updateBadge();
    }

    @Override
    public void onReset() {
        updateBadge();
    }
}
