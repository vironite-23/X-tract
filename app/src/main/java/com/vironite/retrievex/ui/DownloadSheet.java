package com.vironite.retrievex.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;
import com.vironite.retrievex.R;
import com.vironite.retrievex.data.DownloadTask;
import com.vironite.retrievex.download.DownloadManager;
import com.vironite.retrievex.download.TaskFactory;
import com.vironite.retrievex.extract.FormatOption;
import com.vironite.retrievex.extract.FormatPicker;
import com.vironite.retrievex.extract.MediaEntry;
import com.vironite.retrievex.extract.PostInfo;
import com.vironite.retrievex.storage.FileStore;
import com.vironite.retrievex.util.Fmt;
import com.vironite.retrievex.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/** Bottom sheet that lets the user choose quality and confirms what will be downloaded. */
public final class DownloadSheet {
    public interface Callback {
        void onQueued(int count);
    }

    private final Activity act;
    private final PostInfo post;
    private final List<MediaEntry> selected;
    private final Callback callback;
    private final boolean allowMerge;
    private final List<MediaEntry> videos = new ArrayList<>();
    private final List<Integer> resolutions;
    private final int[] advChoice;

    private BottomSheetDialog dialog;
    private RadioGroup group;
    private TextInputLayout resLayout;
    private MaterialAutoCompleteTextView resDropdown;
    private TextView summary;
    private int resIndex;

    private DownloadSheet(Activity act, PostInfo post, List<MediaEntry> selected, Callback cb) {
        this.act = act;
        this.post = post;
        this.selected = selected;
        this.callback = cb;
        this.allowMerge = Prefs.getBool(Prefs.AUTO_MERGE, true);
        for (MediaEntry e : selected) if (e.isVideoLike()) videos.add(e);
        this.resolutions = FormatPicker.resolutions(videos, allowMerge);
        this.advChoice = new int[videos.size()];
    }

    public static void show(Activity act, PostInfo post, List<MediaEntry> selected, Callback cb) {
        new DownloadSheet(act, post, selected, cb).build();
    }

    private void build() {
        dialog = new BottomSheetDialog(act);
        View v = LayoutInflater.from(act).inflate(R.layout.sheet_download, null);
        dialog.setContentView(v);

        View videoGroup = v.findViewById(R.id.video_group);
        View gifNote = v.findViewById(R.id.gif_note);
        group = v.findViewById(R.id.rg_quality);
        resLayout = v.findViewById(R.id.res_layout);
        resDropdown = v.findViewById(R.id.res_dropdown);
        final android.widget.LinearLayout advContainer = v.findViewById(R.id.advanced_container);
        summary = v.findViewById(R.id.summary_text);

        boolean hasGif = false;
        for (MediaEntry e : selected) if (MediaEntry.TYPE_GIF.equals(e.type)) hasGif = true;
        videoGroup.setVisibility(videos.isEmpty() ? View.GONE : View.VISIBLE);
        gifNote.setVisibility(hasGif ? View.VISIBLE : View.GONE);

        // Resolution dropdown (only resolutions that actually exist)
        if (resolutions.isEmpty()) {
            v.findViewById(R.id.rb_resolution).setVisibility(View.GONE);
        } else {
            List<String> labels = new ArrayList<>();
            for (Integer r : resolutions) labels.add(r + "p");
            resDropdown.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_list_item_1, labels));
            resDropdown.setOnItemClickListener((parent, view, position, id) -> {
                resIndex = position;
                updateSummary();
            });
        }

        // Advanced per-video format selection
        for (int i = 0; i < videos.size(); i++) {
            final int idx = i;
            final MediaEntry e = videos.get(i);
            final List<FormatOption> list = FormatPicker.usable(e, allowMerge);
            View row = LayoutInflater.from(act).inflate(R.layout.item_advanced_format, advContainer, false);
            TextInputLayout til = row.findViewById(R.id.adv_layout);
            MaterialAutoCompleteTextView dd = row.findViewById(R.id.adv_dropdown);
            til.setHint(act.getString(R.string.media_title, FileActions.typeLabel(act, e.type), e.index + 1));
            List<String> labels = new ArrayList<>();
            for (FormatOption f : list) labels.add(f.describe());
            dd.setAdapter(new ArrayAdapter<>(act, android.R.layout.simple_list_item_1, labels));
            FormatOption best = FormatPicker.pick(e, FormatPicker.BEST, allowMerge);
            advChoice[idx] = Math.max(0, list.indexOf(best));
            if (!labels.isEmpty()) dd.setText(labels.get(advChoice[idx]), false);
            dd.setOnItemClickListener((parent, view, position, id) -> {
                advChoice[idx] = position;
                updateSummary();
            });
            advContainer.addView(row);
        }

        // Initial mode from the saved default
        String def = Prefs.getString(Prefs.QUALITY, FormatPicker.BEST);
        int checked = R.id.rb_best;
        if (FormatPicker.ORIGINAL.equals(def)) checked = R.id.rb_original;
        else if (FormatPicker.SMALLEST.equals(def)) checked = R.id.rb_smallest;
        else if (!FormatPicker.BEST.equals(def) && !resolutions.isEmpty()) {
            checked = R.id.rb_resolution;
            try {
                int wanted = Integer.parseInt(def);
                int bestIdx = 0;
                int bestDiff = Integer.MAX_VALUE;
                for (int i = 0; i < resolutions.size(); i++) {
                    int diff = Math.abs(resolutions.get(i) - wanted);
                    if (diff < bestDiff) {
                        bestDiff = diff;
                        bestIdx = i;
                    }
                }
                resIndex = bestIdx;
            } catch (NumberFormatException ignored) {
                resIndex = 0;
            }
        }
        if (!resolutions.isEmpty()) resDropdown.setText(resolutions.get(resIndex) + "p", false);
        group.check(checked);
        updateModeViews(advContainer);
        group.setOnCheckedChangeListener((g, id) -> {
            updateModeViews(advContainer);
            updateSummary();
        });

        v.findViewById(R.id.sheet_cancel).setOnClickListener(x -> dialog.dismiss());
        v.findViewById(R.id.sheet_download).setOnClickListener(x -> startDownload());
        updateSummary();
        dialog.show();
    }

    private void updateModeViews(View advContainer) {
        int id = group.getCheckedRadioButtonId();
        resLayout.setVisibility(id == R.id.rb_resolution ? View.VISIBLE : View.GONE);
        advContainer.setVisibility(id == R.id.rb_advanced ? View.VISIBLE : View.GONE);
    }

    private String mode() {
        int id = group.getCheckedRadioButtonId();
        if (id == R.id.rb_original) return FormatPicker.ORIGINAL;
        if (id == R.id.rb_smallest) return FormatPicker.SMALLEST;
        if (id == R.id.rb_resolution && !resolutions.isEmpty()) return String.valueOf(resolutions.get(resIndex));
        if (id == R.id.rb_advanced) return "advanced";
        return FormatPicker.BEST;
    }

    private FormatOption chosen(MediaEntry e) {
        if (!e.isVideoLike()) return e.formats.isEmpty() ? null : e.formats.get(0);
        String m = mode();
        if ("advanced".equals(m)) {
            int vi = videos.indexOf(e);
            List<FormatOption> list = FormatPicker.usable(e, allowMerge);
            if (vi >= 0 && vi < advChoice.length && advChoice[vi] < list.size()) return list.get(advChoice[vi]);
        }
        return FormatPicker.pick(e, "advanced".equals(m) ? FormatPicker.BEST : m, allowMerge);
    }

    private void updateSummary() {
        StringBuilder sb = new StringBuilder();
        long total = 0;
        boolean allExact = true;
        boolean merge = false;
        String imagePref = Prefs.getString(Prefs.IMAGE_FORMAT, "original");
        for (MediaEntry e : selected) {
            FormatOption f = chosen(e);
            if (f == null) continue;
            sb.append("\u2022 ").append(act.getString(R.string.media_title, FileActions.typeLabel(act, e.type), e.index + 1)).append(": ");
            FormatOption sized = f;
            if (e.isVideoLike()) {
                sb.append(f.describe());
                if (f.needsMerge()) merge = true;
            } else {
                String ext = f.container != null ? f.container.toUpperCase() : "";
                if (!"original".equals(imagePref)) ext = imagePref.toUpperCase();
                sb.append(act.getString(R.string.image_original, ext));
                if (f.width > 0 && f.height > 0) sb.append(" \u00B7 ").append(Fmt.resolution(f.width, f.height));
                sized = FormatPicker.imageAs(f, imagePref); // drops a size measured for another format
                if (sized.estSize > 0) sb.append(" \u00B7 ").append(sized.sizeLabel());
            }
            if (sized.estSize > 0) total += sized.estSize;
            if (sized.estSize <= 0 || !sized.sizeExact) allExact = false;
            sb.append('\n');
        }
        if (total > 0) {
            sb.append('\n').append(act.getString(allExact ? R.string.total_size : R.string.est_size, Fmt.size(total))).append('\n');
        }
        sb.append(act.getString(R.string.destination, FileStore.destinationLabel(act))).append('\n');
        if (merge) sb.append(act.getString(R.string.merge_note)).append('\n');
        summary.setText(sb.toString().trim());
    }

    private void startDownload() {
        List<DownloadTask> tasks = new ArrayList<>();
        int ord = 0;
        for (MediaEntry e : selected) {
            FormatOption f = chosen(e);
            if (f == null) continue;
            tasks.add(TaskFactory.create(post, e, f, ord++));
        }
        if (tasks.isEmpty()) {
            dialog.dismiss();
            return;
        }
        DownloadManager.get().enqueue(tasks);
        dialog.dismiss();
        if (callback != null) callback.onQueued(tasks.size());
    }
}
