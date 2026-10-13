package com.vironite.retrievex.ui;

import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.vironite.retrievex.R;
import com.vironite.retrievex.auth.AuthHttp;
import com.vironite.retrievex.auth.Session;
import com.vironite.retrievex.auth.SessionManager;
import com.vironite.retrievex.extract.Http;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sign-in through x.com's own login page. The password is typed into x.com inside a locked-down WebView
 * and never reaches the app's code. When X issues the session cookies, only {@code auth_token}, {@code ct0}
 * and the numeric user id are read, verified with X, and stored encrypted by {@link SessionManager}.
 * All WebView cookies and data are wiped before the flow starts and again when the screen closes.
 */
public class LoginActivity extends AppCompatActivity {
    private static final String START_URL = "https://x.com/i/flow/login";
    private static final String VERIFY_URL = "https://x.com/i/api/1.1/account/settings.json";
    private static final Pattern TWID = Pattern.compile("u(?:%3D|=)(\\d{1,20})");
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView web;
    private View busy;
    private LinearProgressIndicator progress;
    private boolean captured;
    private boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Keep credentials out of screenshots, screen recordings and the recent-apps thumbnail.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        InsetsHelper.apply(findViewById(R.id.login_root));

        MaterialToolbar toolbar = findViewById(R.id.login_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        web = findViewById(R.id.login_web);
        busy = findViewById(R.id.login_busy);
        progress = findViewById(R.id.login_progress);

        configureWebView();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!captured && web != null && web.canGoBack()) web.goBack();
                else finish();
            }
        });

        // Start from a clean slate so an old WebView session can never be captured by mistake.
        wipeWebData(() -> {
            if (!destroyed && web != null) web.loadUrl(START_URL);
        });
    }

    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);          // x.com's login flow needs it; no JavaScript bridge is ever exposed
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setGeolocationEnabled(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setSaveFormData(false);
        s.setUserAgentString(Http.USER_AGENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) s.setSafeBrowsingEnabled(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                if (!req.isForMainFrame()) return false;
                if (isAllowed(req.getUrl())) return false;
                Toast.makeText(LoginActivity.this, R.string.login_blocked, Toast.LENGTH_LONG).show();
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                if (!captured) progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                progress.setVisibility(View.INVISIBLE);
                tryCapture();
            }

            @Override
            public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                tryCapture();
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.cancel();   // never continue past a certificate problem
                fail(R.string.login_ssl_error);
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                fail(R.string.login_failed);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                callback.invoke(origin, false, false);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
                return false;   // no pop-ups
            }
        });
    }

    /** Top-level pages must be HTTPS pages on x.com or twitter.com (including their sub-domains). */
    private static boolean isAllowed(Uri u) {
        if (u == null || !"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo() != null) return false;
        if (u.getPort() != -1 && u.getPort() != 443) return false;
        String h = u.getHost();
        if (h == null) return false;
        h = h.toLowerCase(Locale.ROOT);
        return h.equals("x.com") || h.endsWith(".x.com") || h.equals("twitter.com") || h.endsWith(".twitter.com");
    }

    // ------------------------------------------------------------------ capture

    private void tryCapture() {
        if (captured || destroyed) return;
        String cookies = CookieManager.getInstance().getCookie("https://x.com");
        if (cookies == null) return;
        String auth = null;
        String ct0 = null;
        String userId = null;
        for (String part : cookies.split(";")) {
            String t = part.trim();
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String k = t.substring(0, eq);
            String v = t.substring(eq + 1);
            if ("auth_token".equals(k)) auth = v;
            else if ("ct0".equals(k)) ct0 = v;
            else if ("twid".equals(k)) {
                Matcher m = TWID.matcher(v);
                if (m.find()) userId = m.group(1);
            }
        }
        if (!Session.validAuthToken(auth) || !Session.validCt0(ct0)) return;

        captured = true;
        web.stopLoading();
        web.setVisibility(View.INVISIBLE);
        progress.setVisibility(View.INVISIBLE);
        busy.setVisibility(View.VISIBLE);
        final Session candidate = new Session(auth, ct0, userId, null);
        IO.execute(() -> finishLogin(candidate));
    }

    /** Verifies the session with X, stores it encrypted, then closes. Runs off the main thread. */
    private void finishLogin(Session candidate) {
        Session result = candidate;
        int errorRes = 0;
        try {
            AuthHttp.Response r = AuthHttp.get(VERIFY_URL, candidate, 256 * 1024);
            if (r.newCt0 != null && Session.validCt0(r.newCt0)) result = result.withCt0(r.newCt0);
            if (r.code == 200) {
                String name = new JSONObject(r.body).optString("screen_name", null);
                if (name != null) result = result.withScreenName(name);
            } else if (r.code == 401 || r.code == 403) {
                errorRes = R.string.login_rejected;
            }
            // Any other status (rate limit, server error): X couldn't confirm right now; the session is kept
            // and X will reject it on first use if it is invalid.
        } catch (Exception e) {
            // Offline or an unexpected reply: keep the session unverified rather than lose the sign-in.
        }
        boolean saved = false;
        if (errorRes == 0) {
            saved = SessionManager.save(result);
            if (!saved) errorRes = R.string.login_save_failed;
        }
        final boolean ok = saved;
        final int err = errorRes;
        final String name = result.screenName;
        main.post(() -> {
            if (destroyed) return;
            if (ok) {
                Toast.makeText(this, name != null ? getString(R.string.xa_signed_in_as, name) : getString(R.string.login_done),
                        Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
            } else {
                Toast.makeText(this, err != 0 ? err : R.string.login_failed, Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED);
            }
            finish();
        });
    }

    private void fail(int messageRes) {
        if (destroyed) return;
        captured = true;   // stop any further capture attempts
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show();
        setResult(RESULT_CANCELED);
        finish();
    }

    // ------------------------------------------------------------------ cleanup

    /** Removes every cookie and all web data the login page left behind. */
    private void wipeWebData(final Runnable afterCookies) {
        final CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(ok -> {
            cm.flush();
            if (afterCookies != null) runOnUiThread(afterCookies);
        });
        WebStorage.getInstance().deleteAllData();
        if (web != null) {
            web.clearCache(true);
            web.clearHistory();
            web.clearFormData();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        if (web != null) {
            web.stopLoading();
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) parent.removeView(web);
            wipeWebData(null);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
