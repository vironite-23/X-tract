package com.vironite.retrievex.ui;

import com.vironite.retrievex.R;
import com.vironite.retrievex.data.MediaQuery;
import com.vironite.retrievex.data.Repo;

/** Sort and filter option tables (key + label) for the media library and accounts library. */
public final class SortOptions {
    private SortOptions() {
    }

    public static final String[] MEDIA_KEYS = {
            MediaQuery.SORT_NEWEST, MediaQuery.SORT_OLDEST, MediaQuery.SORT_NAME_ASC, MediaQuery.SORT_NAME_DESC,
            MediaQuery.SORT_SIZE_DESC, MediaQuery.SORT_SIZE_ASC, MediaQuery.SORT_USER_ASC, MediaQuery.SORT_USER_DESC,
            MediaQuery.SORT_TYPE, MediaQuery.SORT_RESOLUTION};
    public static final int[] MEDIA_LABELS = {
            R.string.sort_newest, R.string.sort_oldest, R.string.sort_name_az, R.string.sort_name_za,
            R.string.sort_largest, R.string.sort_smallest, R.string.sort_user_az, R.string.sort_user_za,
            R.string.sort_type, R.string.sort_resolution};

    /** Account detail uses a subset (no per-account username sorting). */
    public static final String[] ACCOUNT_MEDIA_KEYS = {
            MediaQuery.SORT_NEWEST, MediaQuery.SORT_OLDEST, MediaQuery.SORT_SIZE_DESC, MediaQuery.SORT_SIZE_ASC,
            MediaQuery.SORT_NAME_ASC, MediaQuery.SORT_NAME_DESC, MediaQuery.SORT_RESOLUTION, MediaQuery.SORT_TYPE};
    public static final int[] ACCOUNT_MEDIA_LABELS = {
            R.string.sort_newest, R.string.sort_oldest, R.string.sort_largest, R.string.sort_smallest,
            R.string.sort_name_az, R.string.sort_name_za, R.string.sort_resolution, R.string.sort_type};

    public static final String[] ACCOUNT_KEYS = {
            Repo.ACC_ACTIVITY_DESC, Repo.ACC_ACTIVITY_ASC, Repo.ACC_NAME_ASC, Repo.ACC_NAME_DESC,
            Repo.ACC_COUNT_DESC, Repo.ACC_COUNT_ASC, Repo.ACC_SIZE_DESC, Repo.ACC_SIZE_ASC,
            Repo.ACC_VIDEOS_DESC, Repo.ACC_IMAGES_DESC, Repo.ACC_ADDED_DESC, Repo.ACC_ADDED_ASC};
    public static final int[] ACCOUNT_LABELS = {
            R.string.asort_activity_newest, R.string.asort_activity_oldest, R.string.asort_name_az, R.string.asort_name_za,
            R.string.asort_most_media, R.string.asort_fewest_media, R.string.asort_largest, R.string.asort_smallest,
            R.string.asort_most_videos, R.string.asort_most_images, R.string.asort_added_newest, R.string.asort_added_oldest};

    public static final String[] FILTER_KEYS = {
            MediaQuery.FILTER_ALL, MediaQuery.FILTER_VIDEOS, MediaQuery.FILTER_IMAGES, MediaQuery.FILTER_GIFS,
            MediaQuery.FILTER_AUDIO, MediaQuery.FILTER_NO_AUDIO};
    public static final int[] FILTER_LABELS = {
            R.string.filter_all, R.string.filter_videos, R.string.filter_images, R.string.filter_gifs,
            R.string.filter_with_audio, R.string.filter_no_audio};

    public static int indexOf(String[] keys, String key) {
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(key)) return i;
        return 0;
    }
}
