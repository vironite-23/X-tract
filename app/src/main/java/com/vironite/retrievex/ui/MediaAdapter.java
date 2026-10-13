package com.vironite.retrievex.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.vironite.retrievex.R;
import com.vironite.retrievex.data.MediaItem;
import com.vironite.retrievex.util.Fmt;
import com.vironite.retrievex.util.TextUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grid / list / detail adapter for the media library with multi-selection and partial updates. In
 * the detail layout every row is one post, laid out like X: avatar, name, date, caption and a
 * rounded media block (1-4 files in a grid) without any card background.
 */
public class MediaAdapter extends ListAdapter<MediaItem, RecyclerView.ViewHolder> {
    public interface Listener {
        void onClick(MediaItem item);

        void onLongClick(MediaItem item);

        void onMore(MediaItem item, View anchor);

        void onPostShare(List<MediaItem> group);

        void onPostMore(List<MediaItem> group, View anchor);
    }

    public static final String VIEW_GRID = "grid";
    public static final String VIEW_LIST = "list";
    public static final String VIEW_DETAIL = "detail";

    private static final int TYPE_GRID = 0;
    private static final int TYPE_LIST = 1;
    private static final int TYPE_DETAIL = 2;
    private static final int CAPTION_COLLAPSED_LINES = 6;
    private static final Object SELECTION = new Object();

    private static final DiffUtil.ItemCallback<MediaItem> DIFF = new DiffUtil.ItemCallback<
            MediaItem>() {
        @Override
        public boolean areItemsTheSame(@NonNull MediaItem a, @NonNull MediaItem b) {
            return a.id == b.id;
        }

        @Override
        public boolean areContentsTheSame(@NonNull MediaItem a, @NonNull MediaItem b) {
            return TextUtil.nz(a.filename).equals(TextUtil.nz(b.filename))
                    && TextUtil.nz(a.fileUri).equals(TextUtil.nz(b.fileUri))
                    && a.availability == b.availability
                    && a.fileSize == b.fileSize
                    && a.height == b.height
                    && a.hasAudio == b.hasAudio
                    && TextUtil.nz(a.accountLabel()).equals(TextUtil.nz(b.accountLabel()))
                    && TextUtil.nz(a.groupSig).equals(TextUtil.nz(b.groupSig))
                    && TextUtil.nz(a.postText).equals(TextUtil.nz(b.postText))
                    && TextUtil.nz(a.accountDisplayName).equals(TextUtil.nz(b.accountDisplayName))
                    && TextUtil.nz(a.accountAvatarPath).equals(TextUtil.nz(b.accountAvatarPath))
                    && TextUtil.nz(a.accountAvatarUrl).equals(TextUtil.nz(b.accountAvatarUrl));
        }
    };

    private final Listener listener;
    private final Set<Long> selected;
    private String viewMode = VIEW_GRID;
    private List<MediaItem> source = new ArrayList<>();
    private final Map<
            Long, List<MediaItem>> groups = new HashMap<>(); // lead id -> files of that post
    private final Map<Long, Long> leadOf = new HashMap<>(); // file id -> lead id
    private final Set<Long> expandedCaptions = new HashSet<>();
    private boolean showAccount = true;
    private boolean showSize = true;
    private boolean compact;
    private int thumbPx = 256;
    private int listThumbDp = 72;

    public MediaAdapter(Set<Long> selected, Listener listener) {
        super(DIFF);
        this.selected = selected;
        this.listener = listener;
    }

    public void configure(String mode, boolean showAccount, boolean showSize, boolean compact, int thumbPx, int listThumbDp) {
        String m = VIEW_LIST.equals(mode) || VIEW_DETAIL.equals(mode) ? mode : VIEW_GRID;
        boolean modeChanged = !m.equals(this.viewMode);
        boolean changed = modeChanged || this.showAccount != showAccount || this.showSize != showSize
                || this.compact != compact || this.thumbPx != thumbPx || this.listThumbDp != listThumbDp;
        this.viewMode = m;
        this.showAccount = showAccount;
        this.showSize = showSize;
        this.compact = compact;
        this.thumbPx = thumbPx;
        this.listThumbDp = listThumbDp;
        if (modeChanged) {
            // Different row layouts: clear first so no old holder is reused for the wrong type.
            submitList(null);
            submitList(buildDisplayList());
        } else if (changed) {
            notifyDataSetChanged();
        }
    }

    public boolean isGrid() {
        return VIEW_GRID.equals(viewMode);
    }

    public boolean isDetail() {
        return VIEW_DETAIL.equals(viewMode);
    }

    /**
     * Gives the adapter the flat list of loaded files; in detail layout they are grouped by post.
     */
    public void setItems(List<MediaItem> items) {
        source = new ArrayList<>(items);
        submitList(buildDisplayList());
    }

    /** The files that share a post with {@code m} (detail layout only), or null. */
    public List<MediaItem> groupOf(MediaItem m) {
        Long lead = leadOf.get(m.id);
        return lead == null ? null : groups.get(lead);
    }

    private List<MediaItem> buildDisplayList() {
        groups.clear();
        leadOf.clear();
        if (!isDetail()) {
            for (MediaItem m : source) m.groupSig = null;
            return new ArrayList<>(source);
        }
        Map<String, List<MediaItem>> byPost = new LinkedHashMap<>();
        for (MediaItem m : source) {
            String key = TextUtil.isEmpty(m.postId) ? "m" + m.id : m.accountId + ":" + m.postId;
            List<MediaItem> g = byPost.get(key);
            if (g == null) {
                g = new ArrayList<>();
                byPost.put(key, g);
            }
            g.add(m);
        }
        List<MediaItem> leads = new ArrayList<>();
        for (List<MediaItem> g : byPost.values()) {
            Collections.sort(g, (a, b) -> Long.compare(a.id, b.id)); // download order = media order
                                                                     // in the post
            MediaItem lead = g.get(0);
            StringBuilder sig = new StringBuilder();
            for (MediaItem x : g) {
                sig.append(x.id).append(':').append(x.availability).append(':').append(x.fileSize)
                        .append(':').append(x.height).append(':').append(TextUtil.nz(x.fileUri)).append(';');
                leadOf.put(x.id, lead.id);
            }
            lead.groupSig = sig.toString();
            groups.put(lead.id, g);
            leads.add(lead);
        }
        return leads;
    }

    public void notifySelectionChanged(long id) {
        if (isDetail()) {
            Long lead = leadOf.get(id);
            if (lead == null) return;
            id = lead;
        }
        for (int i = 0; i < getItemCount(); i++) {
            if (getItem(i).id == id) {
                notifyItemChanged(i, SELECTION);
                return;
            }
        }
    }

    public void notifyAllSelectionChanged() {
        notifyItemRangeChanged(0, getItemCount(), SELECTION);
    }

    @Override
    public int getItemViewType(int position) {
        if (isDetail()) return TYPE_DETAIL;
        return isGrid() ? TYPE_GRID : TYPE_LIST;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_GRID)
            return new GridVH(inf.inflate(R.layout.item_media_grid, parent, false));
        if (viewType == TYPE_DETAIL)
            return new DetailVH(inf.inflate(R.layout.item_media_detail, parent, false));
        return new ListVH(inf.inflate(R.layout.item_media_list, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        bind(holder, position, false);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull
                    java.util.List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(SELECTION)) {
            bind(holder, position, true);
        } else {
            bind(holder, position, false);
        }
    }

    private void bind(RecyclerView.ViewHolder holder, int position, boolean selectionOnly) {
        final MediaItem m = getItem(position);
        if (holder instanceof DetailVH) {
            DetailVH h = (DetailVH) holder;
            List<MediaItem> group = groups.get(m.id);
            if (group == null) {
                group = new ArrayList<>();
                group.add(m);
            }
            if (selectionOnly) updateThumbSelection(h, group);
            else bindDetail(h, m, group);
            return;
        }
        boolean sel = selected.contains(m.id);
        if (holder instanceof GridVH) {
            GridVH h = (GridVH) holder;
            h.selectedFrame.setVisibility(sel ? View.VISIBLE : View.GONE);
            h.check.setVisibility(sel ? View.VISIBLE : View.GONE);
            if (!selectionOnly) bindGrid(h, m);
        } else {
            ListVH h = (ListVH) holder;
            h.card.setChecked(sel);
            if (!selectionOnly) bindList(h, m);
        }
        View root = holder.itemView;
        root.setOnClickListener(v -> listener.onClick(m));
        root.setOnLongClickListener(v -> {
            listener.onLongClick(m);
            return true;
        });
    }

    private void bindGrid(GridVH h, MediaItem m) {
        Context c = h.itemView.getContext();
        ImageLoader.loadMedia(h.thumb, m, thumbPx);
        h.play.setVisibility(m.isVideoLike() ? View.VISIBLE : View.GONE);
        h.type.setText(m.isVideoLike() ? (MediaItem.TYPE_GIF.equals(m.mediaType) ? "GIF" : "VIDEO") : "IMAGE");
        if (m.isVideoLike() && m.duration > 0) {
            h.duration.setVisibility(View.VISIBLE);
            h.duration.setText(Fmt.duration(m.duration));
        } else {
            h.duration.setVisibility(View.GONE);
        }
        String label = m.accountLabel();
        if (showAccount && label != null) {
            h.user.setVisibility(View.VISIBLE);
            h.user.setText(label);
        } else {
            h.user.setVisibility(View.GONE);
        }
        h.date.setText(Fmt.date(c, m.downloadedAt));
        h.missing.setVisibility(m.availability == MediaItem.AVAIL_OK ? View.GONE : View.VISIBLE);
        h.missing.setText(m.availability == MediaItem.AVAIL_MISSING ? R.string.missing : R.string.inaccessible);
        h.thumb.setContentDescription(m.filename);
    }

    private void bindList(ListVH h, MediaItem m) {
        Context c = h.itemView.getContext();
        float d = c.getResources().getDisplayMetrics().density;
        int box = (int) ((compact ? Math.min(56, listThumbDp) : listThumbDp) * d);
        ViewGroup.LayoutParams lp = h.thumbBox.getLayoutParams();
        if (lp.width != box || lp.height != box) {
            lp.width = box;
            lp.height = box;
            h.thumbBox.setLayoutParams(lp);
        }
        int pad = (int) ((compact ? 4 : 8) * d);
        h.row.setPadding(pad, pad, pad, pad);
        ImageLoader.loadMedia(h.thumb, m, (int) (box * 1.5f));
        h.play.setVisibility(m.isVideoLike() ? View.VISIBLE : View.GONE);
        h.name.setText(m.filename);

        StringBuilder sub = new StringBuilder();
        String label = m.accountLabel();
        if (showAccount && label != null) sub.append(label);
        appendPart(sub, FileActions.typeLabel(c, m.mediaType));
        if (m.isVideoLike() && m.height > 0)
            appendPart(sub, Math.min(m.width > 0 ? m.width : m.height, m.height) + "p");
        h.sub.setText(sub);

        if (m.availability != MediaItem.AVAIL_OK) {
            h.meta.setText(m.availability == MediaItem.AVAIL_MISSING ? R.string.status_missing : R.string.status_inaccessible);
            h.meta.setTextColor(c.getColor(R.color.status_error));
        } else {
            StringBuilder meta = new StringBuilder();
            if (showSize) meta.append(Fmt.size(m.fileSize));
            appendPart(meta, Fmt.date(c, m.downloadedAt));
            h.meta.setText(meta);
            android.util.TypedValue tv = new android.util.TypedValue();
            c.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true);
            if (tv.resourceId != 0) h.meta.setTextColor(c.getColorStateList(tv.resourceId));
            else h.meta.setTextColor(tv.data);
        }
        h.more.setOnClickListener(v -> listener.onMore(m, v));
    }

    // ------------------------------------------------------------------ detail layout

    private void bindDetail(final DetailVH h, MediaItem lead, final List<MediaItem> group) {
        final Context c = h.itemView.getContext();
        float d = c.getResources().getDisplayMetrics().density;

        // avatar
        int avatarPx = (int) (40 * d);
        if (lead.accountAvatarPath != null && new java.io.File(lead.accountAvatarPath).exists()) {
            ImageLoader.loadFile(h.avatar, lead.accountAvatarPath, avatarPx);
        } else if (!TextUtil.isEmpty(lead.accountAvatarUrl)) {
            ImageLoader.loadUrl(h.avatar, lead.accountAvatarUrl, avatarPx);
        } else {
            h.avatar.setTag(R.id.image_tag, null);
            h.avatar.setImageResource(R.drawable.ic_person);
        }

        // header on one line, like X: bold name and "@handle" (these may be shortened with "..."), while the
        // date sits in its own view to the right so it is always fully visible
        String label = lead.accountLabel();
        String display = lead.accountDisplayName;
        String bold = !TextUtil.isEmpty(display) ? display : label;
        if (TextUtil.isEmpty(bold)) bold = c.getString(R.string.unknown_account);
        SpannableStringBuilder header = new SpannableStringBuilder(bold);
        header.setSpan(new StyleSpan(Typeface.BOLD), 0, header.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!TextUtil.isEmpty(label) && !label.equals(bold)) {
            int from = header.length();
            header.append("  ").append(label);
            header.setSpan(new ForegroundColorSpan(secondaryColor(c)), from, header.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        h.name.setText(header);
        String date = Fmt.dateShort(c, lead.postDate());
        h.date.setText(date == null ? "" : date);
        h.date.setVisibility(TextUtil.isEmpty(date) ? View.GONE : View.VISIBLE);
        h.share.setOnClickListener(v -> listener.onPostShare(group));
        h.more.setOnClickListener(v -> listener.onPostMore(group, v));

        // caption (tap to expand / collapse)
        final String text = lead.postText == null ? "" : lead.postText.trim();
        if (text.isEmpty()) {
            h.text.setVisibility(View.GONE);
            h.text.setOnClickListener(null);
        } else {
            h.text.setVisibility(View.VISIBLE);
            h.text.setText(text);
            final long key = lead.id;
            applyCaptionState(h.text, expandedCaptions.contains(key));
            h.text.setOnClickListener(v -> {
                boolean now = !expandedCaptions.contains(key);
                if (now) expandedCaptions.add(key);
                else expandedCaptions.remove(key);
                applyCaptionState(h.text, now);
            });
        }

        bindMedia(h, group, c, d);
        updateThumbSelection(h, group);
        h.itemView.setOnClickListener(null);
        h.itemView.setOnLongClickListener(null);
        h.itemView.setClickable(false);
    }

    /**
     * Media block of a post, built like X: one file keeps its own aspect ratio, 2-4 files share a
     * 16:9 block (2 side by side, 3 = one tall + two stacked, 4 = 2x2). Files beyond the fourth are
     * reachable through "+N".
     */
    private void bindMedia(DetailVH h, List<MediaItem> group, Context c, float d) {
        h.media.removeAllViews();
        h.tiles.clear();
        int n = group.size();
        if (n == 0) {
            h.mediaCard.setVisibility(View.GONE);
            return;
        }
        h.mediaCard.setVisibility(View.VISIBLE);
        int shown = Math.min(n, 4);
        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        int gap = Math.max(1, (int) (1 * d)); // each tile keeps 1dp on the shared edge -> 2dp
                                              // between tiles

        if (n == 1) {
            MediaItem m = group.get(0);
            float ratio = (m.width > 0 && m.height > 0) ? (float) m.width / m.height : (m.isVideoLike() ? 16f / 9f : 4f / 3f);
            h.media.setRatio(Math.max(0.75f, Math.min(16f / 9f, ratio)));
            View t = makeTile(h, group, 0, 1, Math.min(screenW, 1280), c);
            h.media.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }

        h.media.setRatio(16f / 9f);
        int px = (int) (screenW * 0.6f);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        h.media.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT));
        if (shown == 2) {
            row.addView(makeTile(h, group, 0, shown, px, c), cell(0, gap, 0, 0, true));
            row.addView(makeTile(h, group, 1, shown, px, c), cell(gap, 0, 0, 0, true));
        } else if (shown == 3) {
            row.addView(makeTile(h, group, 0, shown, px, c), cell(0, gap, 0, 0, true));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            row.addView(col, cell(gap, 0, 0, 0, true));
            col.addView(makeTile(h, group, 1, shown, px, c), cell(0, 0, 0, gap, false));
            col.addView(makeTile(h, group, 2, shown, px, c), cell(0, 0, gap, 0, false));
        } else {
            LinearLayout left = new LinearLayout(c);
            left.setOrientation(LinearLayout.VERTICAL);
            row.addView(left, cell(0, gap, 0, 0, true));
            LinearLayout right = new LinearLayout(c);
            right.setOrientation(LinearLayout.VERTICAL);
            row.addView(right, cell(gap, 0, 0, 0, true));
            left.addView(makeTile(h, group, 0, shown, px, c), cell(0, 0, 0, gap, false));
            left.addView(makeTile(h, group, 2, shown, px, c), cell(0, 0, gap, 0, false));
            right.addView(makeTile(h, group, 1, shown, px, c), cell(0, 0, 0, gap, false));
            right.addView(makeTile(h, group, 3, shown, px, c), cell(0, 0, gap, 0, false));
        }
    }

    /** Equal-weight cell inside a horizontal ({@code inRow}) or vertical row of the media block. */
    private static LinearLayout.LayoutParams cell(int start, int end, int top, int bottom, boolean inRow) {
        LinearLayout.LayoutParams lp = inRow
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.setMarginStart(start);
        lp.setMarginEnd(end);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        return lp;
    }

    /**
     * One file of the post. When it is the last visible tile of a longer post it stands for all the
     * remaining files.
     */
    private View makeTile(DetailVH h, List<
                    MediaItem> group, int index, int shown, int px, Context c) {
        final MediaItem m = group.get(index);
        boolean overflow = index == shown - 1 && group.size() > shown;
        View t = LayoutInflater.from(c).inflate(R.layout.item_media_detail_thumb, h.media, false);
        List<Long> ids = new ArrayList<>();
        for (int i = index; i < (overflow ? group.size() : index + 1); i++)
            ids.add(group.get(i).id);
        t.setTag(ids);
        h.tiles.add(t);
        ImageView thumb = t.findViewById(R.id.dt_thumb);
        ImageLoader.loadMedia(thumb, m, px);
        thumb.setContentDescription(m.filename);
        t.findViewById(R.id.dt_play).setVisibility(m.isVideoLike() ? View.VISIBLE : View.GONE);
        TextView size = t.findViewById(R.id.dt_size);
        if (showSize && m.availability == MediaItem.AVAIL_OK && !overflow) {
            size.setVisibility(View.VISIBLE);
            size.setText(Fmt.size(m.fileSize));
        } else {
            size.setVisibility(View.GONE);
        }

        TextView resolution = t.findViewById(R.id.dt_resolution);
        if (m.isVideoLike() && m.width > 0 && m.height > 0 && !overflow) {
            resolution.setText(m.width + "×" + m.height);
            resolution.setVisibility(View.VISIBLE);
        } else {
            resolution.setVisibility(View.GONE);
        }

        TextView duration = t.findViewById(R.id.dt_duration);
        if (m.isVideoLike() && m.duration > 0 && !overflow) {
            duration.setText(Fmt.duration(m.duration));
            duration.setVisibility(View.VISIBLE);
        } else {
            duration.setVisibility(View.GONE);
        }

        TextView missing = t.findViewById(R.id.dt_missing);
        missing.setVisibility(m.availability == MediaItem.AVAIL_OK ? View.GONE : View.VISIBLE);
        missing.setText(m.availability == MediaItem.AVAIL_MISSING ? R.string.missing : R.string.inaccessible);
        TextView more = t.findViewById(R.id.dt_more);
        if (overflow) {
            more.setVisibility(View.VISIBLE);
            more.setText("+" + (group.size() - shown + 1));
        } else {
            more.setVisibility(View.GONE);
        }
        t.setOnClickListener(v -> listener.onClick(m));
        t.setOnLongClickListener(v -> {
            listener.onLongClick(m);
            return true;
        });
        return t;
    }

    private static int secondaryColor(Context c) {
        android.util.TypedValue tv = new android.util.TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true);
        if (tv.resourceId != 0) return c.getColorStateList(tv.resourceId).getDefaultColor();
        return tv.data;
    }

    private static void applyCaptionState(TextView tv, boolean expanded) {
        if (expanded) {
            tv.setMaxLines(Integer.MAX_VALUE);
            tv.setEllipsize(null);
        } else {
            tv.setMaxLines(CAPTION_COLLAPSED_LINES);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
    }

    @SuppressWarnings("unchecked")
    private void updateThumbSelection(DetailVH h, List<MediaItem> group) {
        for (View t : h.tiles) {
            boolean sel = false;
            Object tag = t.getTag();
            if (tag instanceof List) {
                for (Long id : (List<Long>) tag) {
                    if (selected.contains(id)) {
                        sel = true;
                        break;
                    }
                }
            }
            t.findViewById(R.id.dt_selected).setVisibility(sel ? View.VISIBLE : View.GONE);
            t.findViewById(R.id.dt_check).setVisibility(sel ? View.VISIBLE : View.GONE);
        }
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (TextUtil.isEmpty(part)) return;
        if (sb.length() > 0) sb.append(" \u00B7 ");
        sb.append(part);
    }

    static class DetailVH extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView name;
        final TextView date;
        final ImageButton share;
        final ImageButton more;
        final TextView text;
        final MaterialCardView mediaCard;
        final RatioFrameLayout media;
        final List<View> tiles = new ArrayList<>();

        DetailVH(View v) {
            super(v);
            avatar = v.findViewById(R.id.d_avatar);
            name = v.findViewById(R.id.d_name);
            date = v.findViewById(R.id.d_date);
            share = v.findViewById(R.id.d_share);
            more = v.findViewById(R.id.d_more);
            text = v.findViewById(R.id.d_text);
            mediaCard = v.findViewById(R.id.d_media_card);
            media = v.findViewById(R.id.d_media);
        }
    }

    static class GridVH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final ImageView play;
        final ImageView check;
        final View selectedFrame;
        final TextView type;
        final TextView duration;
        final TextView user;
        final TextView date;
        final TextView missing;

        GridVH(View v) {
            super(v);
            thumb = v.findViewById(R.id.g_thumb);
            play = v.findViewById(R.id.g_play);
            check = v.findViewById(R.id.g_check);
            selectedFrame = v.findViewById(R.id.g_selected);
            type = v.findViewById(R.id.g_type);
            duration = v.findViewById(R.id.g_duration);
            user = v.findViewById(R.id.g_user);
            date = v.findViewById(R.id.g_date);
            missing = v.findViewById(R.id.g_missing);
        }
    }

    static class ListVH extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final View row;
        final View thumbBox;
        final ImageView thumb;
        final ImageView play;
        final TextView name;
        final TextView sub;
        final TextView meta;
        final ImageButton more;

        ListVH(View v) {
            super(v);
            card = v.findViewById(R.id.l_card);
            card.setCheckable(true);
            row = v.findViewById(R.id.l_row);
            thumbBox = v.findViewById(R.id.l_thumb_box);
            thumb = v.findViewById(R.id.l_thumb);
            play = v.findViewById(R.id.l_play);
            name = v.findViewById(R.id.l_name);
            sub = v.findViewById(R.id.l_sub);
            meta = v.findViewById(R.id.l_meta);
            more = v.findViewById(R.id.l_more);
        }
    }
}
