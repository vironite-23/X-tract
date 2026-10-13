package com.vironite.retrievex.extract;

import com.vironite.retrievex.auth.AuthHttp;
import com.vironite.retrievex.auth.Session;
import com.vironite.retrievex.auth.SessionManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a post the way the x.com web app does, using the signed-in user's own session. It is only used
 * after the public lookup has failed, so credentials are never sent for posts that don't need them.
 * It sees exactly what that account is allowed to see: age-restricted and sensitive-media posts, but
 * never posts from protected accounts the user doesn't follow.
 */
public class AuthedXExtractor implements PostExtractor {
    private static final String BASE = "https://x.com/i/api/graphql/";
    private static final String OPERATION = "TweetResultByRestId";
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final Pattern MISSING_FEATURES = Pattern.compile("features cannot be null:\\s*([A-Za-z0-9_,\\s]+)");
    private static final Pattern AGE_WORD = Pattern.compile("\\bage\\b");
    private static final Pattern FEATURE_NAME = Pattern.compile("^[A-Za-z0-9_]{3,100}$");

    private static final String FEATURES = "{\"creator_subscriptions_tweet_preview_api_enabled\":true,"
            + "\"premium_content_api_read_enabled\":false,\"communities_web_enable_tweet_community_results_fetch\":true,"
            + "\"c9s_tweet_anatomy_moderator_badge_enabled\":true,\"responsive_web_grok_analyze_button_fetch_trends_enabled\":false,"
            + "\"responsive_web_grok_analyze_post_followups_enabled\":true,\"responsive_web_jetfuel_frame\":false,"
            + "\"responsive_web_grok_share_attachment_enabled\":true,\"articles_preview_enabled\":true,"
            + "\"responsive_web_edit_tweet_api_enabled\":true,\"graphql_is_translatable_rweb_tweet_is_translatable_enabled\":true,"
            + "\"view_counts_everywhere_api_enabled\":true,\"longform_notetweets_consumption_enabled\":true,"
            + "\"responsive_web_twitter_article_tweet_consumption_enabled\":true,\"tweet_awards_web_tipping_enabled\":false,"
            + "\"responsive_web_grok_show_grok_translated_post\":false,\"responsive_web_grok_analysis_button_from_backend\":true,"
            + "\"creator_subscriptions_quote_tweet_preview_enabled\":false,\"freedom_of_speech_not_reach_fetch_enabled\":true,"
            + "\"standardized_nudges_misinfo\":true,\"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled\":true,"
            + "\"longform_notetweets_rich_text_read_enabled\":true,\"longform_notetweets_inline_media_enabled\":true,"
            + "\"payments_enabled\":false,\"profile_label_improvements_pcf_label_in_post_enabled\":true,"
            + "\"rweb_tipjar_consumption_enabled\":true,\"verified_phone_label_enabled\":false,"
            + "\"responsive_web_grok_image_annotation_enabled\":true,"
            + "\"responsive_web_graphql_skip_user_profile_image_extensions_enabled\":false,"
            + "\"responsive_web_graphql_timeline_navigation_enabled\":true,\"responsive_web_enhance_cards_enabled\":false}";
    private static final String FIELD_TOGGLES =
            "{\"withArticleRichContentState\":false,\"withArticlePlainText\":false,\"withGrokAnalyze\":false,"
                    + "\"withDisallowedReplyControls\":false}";

    @Override
    public String name() {
        return "x-session";
    }

    @Override
    public PostInfo extract(UrlParser.Parsed p) throws ExtractException {
        Session session = SessionManager.get();
        if (session == null) {
            throw new ExtractException(ExtractException.Kind.SESSION_EXPIRED,
                    "You're not signed in to X. Sign in from Settings to open restricted posts.");
        }
        try {
            String queryId = QueryIds.tweetResult(false);
            boolean idRefreshed = false;
            boolean ct0Retried = false;
            Set<String> extraFalse = new HashSet<>();
            for (int attempt = 0; attempt < 5; attempt++) {
                session = SessionManager.get();
                if (session == null) throw sessionExpired();
                AuthHttp.Response r = AuthHttp.get(buildUrl(queryId, p.statusId, extraFalse), session, MAX_BYTES);

                if (r.newCt0 != null) {
                    SessionManager.updateCt0(r.newCt0);
                }
                if (r.code == 200) return parse(r.body, p);

                if (r.code == 404 && !idRefreshed) {
                    idRefreshed = true;
                    queryId = QueryIds.tweetResult(true);
                    continue;
                }
                if (r.code == 400) {
                    Set<String> missing = missingFeatures(r.body);
                    if (!missing.isEmpty() && extraFalse.addAll(missing)) continue;
                    if (!idRefreshed) {
                        idRefreshed = true;
                        queryId = QueryIds.tweetResult(true);
                        continue;
                    }
                }
                if (r.code == 403 && r.newCt0 != null && !ct0Retried) {
                    ct0Retried = true;
                    continue;
                }
                throw mapError(r);
            }
            throw new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "X didn't accept the request. Try again later.");
        } catch (SocketTimeoutException e) {
            throw new ExtractException(ExtractException.Kind.TIMEOUT, "The request timed out. Check your connection and try again.", e);
        } catch (UnknownHostException e) {
            throw new ExtractException(ExtractException.Kind.NETWORK, "No internet connection.", e);
        } catch (IOException e) {
            throw new ExtractException(ExtractException.Kind.NETWORK, "Couldn't reach X. Check your connection and try again.", e);
        } catch (JSONException e) {
            throw new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "Couldn't read the post data. X may have changed its format.", e);
        }
    }

    private static ExtractException sessionExpired() {
        return new ExtractException(ExtractException.Kind.SESSION_EXPIRED,
                "Your X session has expired. Sign in again from Settings.");
    }

    private static ExtractException mapError(AuthHttp.Response r) {
        int apiCode = firstErrorCode(r.body);
        boolean authFailure = r.code == 401 || apiCode == 32 || apiCode == 89 || apiCode == 215 || apiCode == 99;
        if (authFailure) {
            SessionManager.expire();
            return sessionExpired();
        }
        if (apiCode == 326 || apiCode == 64 || apiCode == 63) {
            return new ExtractException(ExtractException.Kind.PRIVATE,
                    "X has limited or locked this account. Open X and resolve it, then try again.");
        }
        if (r.code == 429) {
            return new ExtractException(ExtractException.Kind.RATE_LIMITED, "X is limiting requests right now. Try again in a few minutes.");
        }
        if (r.code == 404) {
            return new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "Couldn't find X's lookup service. The app may need an update.");
        }
        return new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "X returned an unexpected response (" + r.code + ").");
    }

    private static int firstErrorCode(String body) {
        try {
            JSONArray errs = new JSONObject(body).optJSONArray("errors");
            if (errs != null && errs.length() > 0) return errs.getJSONObject(0).optInt("code", 0);
        } catch (Exception ignored) {
            // not JSON
        }
        return 0;
    }

    private static Set<String> missingFeatures(String body) {
        Set<String> out = new HashSet<>();
        if (body == null) return out;
        Matcher m = MISSING_FEATURES.matcher(body);
        if (m.find()) {
            for (String part : m.group(1).split(",")) {
                String n = part.trim();
                if (FEATURE_NAME.matcher(n).matches()) out.add(n);
            }
        }
        return out;
    }

    private static String buildUrl(String queryId, String statusId, Set<String> extraFalse) throws JSONException {
        JSONObject vars = new JSONObject();
        vars.put("tweetId", statusId);
        vars.put("withCommunity", false);
        vars.put("includePromotedContent", false);
        vars.put("withVoice", false);
        JSONObject features = new JSONObject(FEATURES);
        for (String f : extraFalse) features.put(f, false);
        return BASE + queryId + "/" + OPERATION
                + "?variables=" + enc(vars.toString())
                + "&features=" + enc(features.toString())
                + "&fieldToggles=" + enc(FIELD_TOGGLES);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ parsing

    private PostInfo parse(String body, UrlParser.Parsed p) throws ExtractException, JSONException {
        JSONObject root = new JSONObject(body);
        JSONObject data = root.optJSONObject("data");
        JSONObject tr = data == null ? null : data.optJSONObject("tweetResult");
        JSONObject result = tr == null ? null : tr.optJSONObject("result");
        if (result == null) {
            throw new ExtractException(ExtractException.Kind.DELETED, "This post doesn't exist or has been deleted.");
        }
        String type = result.optString("__typename", "");
        if ("TweetUnavailable".equals(type) || "TweetTombstone".equals(type)) {
            throw unavailable(result);
        }
        if ("TweetWithVisibilityResults".equals(type)) {
            JSONObject inner = result.optJSONObject("tweet");
            if (inner == null) throw new ExtractException(ExtractException.Kind.DELETED, "This post doesn't exist or has been deleted.");
            result = inner;
        }
        JSONObject legacy = result.optJSONObject("legacy");
        if (legacy == null) {
            throw new ExtractException(ExtractException.Kind.EXTRACTOR_FAILURE, "Couldn't read the post data. X may have changed its format.");
        }

        PostInfo info = new PostInfo();
        info.source = name();
        info.postId = result.optString("rest_id", p.statusId);
        info.createdAtMs = parseDate(legacy.optString("created_at", null));
        info.text = text(result, legacy);

        JSONObject user = null;
        JSONObject core = result.optJSONObject("core");
        JSONObject ur = core == null ? null : core.optJSONObject("user_results");
        if (ur != null) user = ur.optJSONObject("result");
        if (user != null) {
            info.accountRestId = user.optString("rest_id", null);
            JSONObject uc = user.optJSONObject("core");
            JSONObject ul = user.optJSONObject("legacy");
            info.username = firstNonEmpty(uc, ul, "screen_name");
            info.displayName = firstNonEmpty(uc, ul, "name");
            String avatar = null;
            JSONObject av = user.optJSONObject("avatar");
            if (av != null) avatar = av.optString("image_url", null);
            if (avatar == null && ul != null) avatar = ul.optString("profile_image_url_https", null);
            if (avatar != null) avatar = avatar.replace("_normal.", "_400x400.");
            info.avatarUrl = avatar;
        }
        if (info.username == null || info.username.isEmpty()) info.username = p.username;

        JSONObject ext = legacy.optJSONObject("extended_entities");
        JSONArray media = ext != null ? ext.optJSONArray("media") : null;
        if (media == null) {
            JSONObject ent = legacy.optJSONObject("entities");
            media = ent != null ? ent.optJSONArray("media") : null;
        }
        if (media != null) {
            for (int i = 0; i < media.length(); i++) {
                MediaEntry e = SyndicationExtractor.parseDetail(media.getJSONObject(i), info.media.size());
                if (e != null) info.media.add(e);
            }
        }
        if (info.media.isEmpty()) {
            throw new ExtractException(ExtractException.Kind.NO_MEDIA, "This post has no downloadable media.");
        }
        return info;
    }

    private static ExtractException unavailable(JSONObject result) {
        String reason = result.optString("reason", "");
        String msg = "";
        JSONObject tomb = result.optJSONObject("tombstone");
        if (tomb != null) {
            JSONObject t = tomb.optJSONObject("text");
            if (t != null) msg = t.optString("text", "");
        }
        String low = (reason + " " + msg).toLowerCase(Locale.ROOT);
        if (low.contains("nsfwviewerhasnostatedage") || low.contains("birth")) {
            return new ExtractException(ExtractException.Kind.PRIVATE,
                    "X needs your birth date to show this post. Add it to your X account settings, then try again.");
        }
        if (low.contains("nsfw") || low.contains("sensitive") || AGE_WORD.matcher(low).find()) {
            return new ExtractException(ExtractException.Kind.PRIVATE,
                    "X won't show this post to your account. In X settings, allow viewing sensitive content, then try again.");
        }
        if (low.contains("protected") || low.contains("follow") || low.contains("private")) {
            return new ExtractException(ExtractException.Kind.PRIVATE,
                    "This post is from a protected account. Only people its owner approves can see it, so it can't be downloaded here.");
        }
        if (low.contains("suspended") || low.contains("blocked") || low.contains("unavailable")) {
            return new ExtractException(ExtractException.Kind.PRIVATE,
                    "This post isn't available to your account (suspended, blocked or restricted).");
        }
        return new ExtractException(ExtractException.Kind.DELETED, "This post doesn't exist or has been deleted.");
    }

    private static String firstNonEmpty(JSONObject a, JSONObject b, String key) {
        if (a != null) {
            String v = a.optString(key, null);
            if (v != null && !v.isEmpty()) return v;
        }
        if (b != null) {
            String v = b.optString(key, null);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    private static String text(JSONObject result, JSONObject legacy) {
        String text = null;
        JSONObject note = result.optJSONObject("note_tweet");
        JSONObject nr = note == null ? null : note.optJSONObject("note_tweet_results");
        JSONObject nres = nr == null ? null : nr.optJSONObject("result");
        if (nres != null) text = nres.optString("text", null);
        if (text == null) {
            text = legacy.optString("full_text", null);
            JSONArray range = legacy.optJSONArray("display_text_range");
            if (text != null && range != null && range.length() == 2) {
                try {
                    int start = range.getInt(0);
                    int end = range.getInt(1);
                    int cp = text.codePointCount(0, text.length());
                    if (start >= 0 && end <= cp && start <= end) {
                        text = text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end));
                    }
                } catch (Exception ignored) {
                    // keep full text
                }
            }
        }
        text = FormatFactory.unescapeHtml(text);
        return text == null ? null : text.trim();
    }

    private static long parseDate(String s) {
        if (s == null || s.isEmpty()) return 0;
        try {
            return new SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH).parse(s).getTime();
        } catch (Exception e) {
            return 0;
        }
    }
}
