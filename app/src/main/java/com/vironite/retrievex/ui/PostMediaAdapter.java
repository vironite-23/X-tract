package com.vironite.retrievex.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.vironite.retrievex.R;
import com.vironite.retrievex.extract.FormatOption;
import com.vironite.retrievex.extract.FormatPicker;
import com.vironite.retrievex.extract.MediaEntry;
import com.vironite.retrievex.util.Fmt;
import com.vironite.retrievex.util.Prefs;

import java.util.List;

/** Selectable list of the media items found in a post. */
public class PostMediaAdapter extends RecyclerView.Adapter<PostMediaAdapter.VH> {
    public interface Listener {
        void onSelectionChanged();
    }

    private final List<MediaEntry> entries;
    private final boolean[] selected;
    private final Listener listener;

    public PostMediaAdapter(List<MediaEntry> entries, boolean[] selected, Listener listener) {
        this.entries = entries;
        this.selected = selected;
        this.listener = listener;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_post_media, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final int pos = position;
        MediaEntry e = entries.get(position);
        Context c = h.itemView.getContext();
        int px = (int) (96 * c.getResources().getDisplayMetrics().density);
        ImageLoader.loadUrl(h.thumb, e.thumbUrl, px);
        h.play.setVisibility(e.isVideoLike() ? View.VISIBLE : View.GONE);

        String type = FileActions.typeLabel(c, e.type);
        h.title.setText(c.getString(R.string.media_title, type, position + 1));

        // Describe the file that will actually be saved by default (its own resolution and real size),
        // not the original upload, so the preview agrees with the downloaded media.
        FormatOption shown = null;
        if (e.isVideoLike()) {
            shown = FormatPicker.pick(e, Prefs.getString(Prefs.QUALITY, FormatPicker.BEST),
                    Prefs.getBool(Prefs.AUTO_MERGE, true));
        } else if (!e.formats.isEmpty()) {
            shown = e.formats.get(0);
        }
        int w = shown != null && shown.width > 0 && shown.height > 0 ? shown.width : e.width;
        int hh = shown != null && shown.width > 0 && shown.height > 0 ? shown.height : e.height;
        StringBuilder details = new StringBuilder();
        if (w > 0 && hh > 0) details.append(Fmt.resolution(w, hh));
        if (e.durationMs > 0 && e.isVideoLike()) {
            if (details.length() > 0) details.append(" \u00B7 ");
            details.append(Fmt.duration(e.durationMs));
        }
        if (shown != null && shown.estSize > 0 && (e.isVideoLike()
                || shown.sizeFor == null || shown.sizeFor.equals(Prefs.getString(Prefs.IMAGE_FORMAT, "original")))) {
            if (details.length() > 0) details.append(" \u00B7 ");
            details.append(shown.sizeLabel());
        }
        h.details.setText(details);
        h.details.setVisibility(details.length() == 0 ? View.GONE : View.VISIBLE);

        if (e.isVideoLike()) {
            StringBuilder labels = new StringBuilder();
            for (FormatOption f : e.formats) {
                String l = f.resolutionLabel();
                if (l.isEmpty()) continue;
                if (labels.length() > 0) labels.append(", ");
                labels.append(l);
            }
            String text = labels.length() > 0
                    ? c.getString(R.string.formats_available, e.formats.size(), labels.toString())
                    : c.getString(R.string.formats_count, e.formats.size());
            h.formats.setText(text);
        } else {
            FormatOption f = e.formats.isEmpty() ? null : e.formats.get(0);
            h.formats.setText(f != null && f.container != null
                    ? c.getString(R.string.image_original, f.container.toUpperCase()) : c.getString(R.string.image_original_plain));
        }

        h.check.setOnCheckedChangeListener(null);
        h.check.setChecked(selected[position]);
        h.check.setOnCheckedChangeListener((b, checked) -> {
            selected[pos] = checked;
            listener.onSelectionChanged();
        });
        h.itemView.setOnClickListener(v -> h.check.toggle());
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final ImageView play;
        final TextView title;
        final TextView details;
        final TextView formats;
        final CheckBox check;

        VH(View v) {
            super(v);
            thumb = v.findViewById(R.id.pm_thumb);
            play = v.findViewById(R.id.pm_play);
            title = v.findViewById(R.id.pm_title);
            details = v.findViewById(R.id.pm_details);
            formats = v.findViewById(R.id.pm_formats);
            check = v.findViewById(R.id.pm_check);
        }
    }
}
