package com.vironite.retrievex.data;

/** Parameters for a library query: search text, sort, filter and paging. */
public class MediaQuery {
    public static final String SORT_NEWEST = "newest";
    public static final String SORT_OLDEST = "oldest";
    public static final String SORT_NAME_ASC = "name_asc";
    public static final String SORT_NAME_DESC = "name_desc";
    public static final String SORT_SIZE_DESC = "size_desc";
    public static final String SORT_SIZE_ASC = "size_asc";
    public static final String SORT_USER_ASC = "user_asc";
    public static final String SORT_USER_DESC = "user_desc";
    public static final String SORT_TYPE = "type";
    public static final String SORT_RESOLUTION = "resolution";

    public static final String FILTER_ALL = "all";
    public static final String FILTER_VIDEOS = "videos";
    public static final String FILTER_IMAGES = "images";
    public static final String FILTER_GIFS = "gifs";
    public static final String FILTER_AUDIO = "audio";
    public static final String FILTER_NO_AUDIO = "noaudio";

    public String search = "";
    public String sort = SORT_NEWEST;
    public String filter = FILTER_ALL;
    public long accountId = 0;   // 0 = all accounts
    public int limit = 100;
    public int offset = 0;
}
