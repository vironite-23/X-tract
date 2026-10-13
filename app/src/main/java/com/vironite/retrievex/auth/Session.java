package com.vironite.retrievex.auth;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Pattern;

/**
 * The minimum an X web session needs: the {@code auth_token} and {@code ct0} cookies. Nothing else from
 * the WebView is kept. Values are validated against a strict character set so they can never inject
 * extra headers or cookies when they are sent. {@link #toString()} never reveals the secrets.
 */
public final class Session {
    private static final Pattern AUTH_TOKEN = Pattern.compile("^[A-Za-z0-9]{20,100}$");
    private static final Pattern CT0 = Pattern.compile("^[A-Za-z0-9]{16,300}$");
    private static final Pattern USER_ID = Pattern.compile("^\\d{1,20}$");
    private static final Pattern SCREEN_NAME = Pattern.compile("^[A-Za-z0-9_]{1,15}$");

    public final String authToken;
    public final String ct0;
    public final String userId;      // numeric id from the twid cookie, may be null
    public final String screenName;  // @handle without the @, may be null until verified

    public Session(String authToken, String ct0, String userId, String screenName) {
        if (!validAuthToken(authToken) || !validCt0(ct0)) throw new IllegalArgumentException("Invalid session");
        this.authToken = authToken;
        this.ct0 = ct0;
        this.userId = userId != null && USER_ID.matcher(userId).matches() ? userId : null;
        this.screenName = screenName != null && SCREEN_NAME.matcher(screenName).matches() ? screenName : null;
    }

    public static boolean validAuthToken(String v) {
        return v != null && AUTH_TOKEN.matcher(v).matches();
    }

    public static boolean validCt0(String v) {
        return v != null && CT0.matcher(v).matches();
    }

    public Session withCt0(String newCt0) {
        return new Session(authToken, newCt0, userId, screenName);
    }

    public Session withScreenName(String name) {
        return new Session(authToken, ct0, userId, name);
    }

    /** Value of the Cookie header. Safe because both values passed strict validation. */
    public String cookieHeader() {
        return "auth_token=" + authToken + "; ct0=" + ct0;
    }

    String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("a", authToken);
            o.put("c", ct0);
            if (userId != null) o.put("u", userId);
            if (screenName != null) o.put("n", screenName);
            return o.toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    static Session fromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            return new Session(o.getString("a"), o.getString("c"), o.optString("u", null), o.optString("n", null));
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "Session{" + (screenName != null ? "@" + screenName : "unverified") + ", secrets hidden}";
    }
}
