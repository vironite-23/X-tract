package com.vironite.retrievex.extract;

import com.vironite.retrievex.auth.SessionManager;
import com.vironite.retrievex.util.Prefs;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the primary extractor and, when allowed and useful, the next ones. Order:
 * public embed lookup first; then, only if the user signed in and allows it, their own X session;
 * then the optional external fallback. The session is therefore never used for posts that are public.
 */
public final class ExtractorChain {
    private ExtractorChain() {
    }

    public static PostInfo extract(String rawUrl) throws ExtractException {
        UrlParser.Parsed p = UrlParser.parse(rawUrl);
        boolean signedIn = SessionManager.isSignedIn();
        boolean useAccount = Prefs.getBool(Prefs.X_USE_ACCOUNT, true);

        List<PostExtractor> list = new ArrayList<>();
        list.add(new SyndicationExtractor());
        if (signedIn && useAccount) list.add(new AuthedXExtractor());
        if (Prefs.getBool(Prefs.FALLBACK_SERVICE, true)) list.add(new FxTwitterExtractor());

        ExtractException first = null;
        ExtractException definitive = null;
        ExtractException sessionProblem = null;
        ExtractException accountResult = null;
        for (int i = 0; i < list.size(); i++) {
            PostExtractor ex = list.get(i);
            try {
                PostInfo info = ex.extract(p);
                info.postUrl = p.normalizedUrl;
                if (info.postId == null || info.postId.isEmpty()) info.postId = p.statusId;
                return info;
            } catch (ExtractException e) {
                if (e.isInputProblem()) throw e;
                if (e.kind == ExtractException.Kind.SESSION_EXPIRED) {
                    sessionProblem = e;
                    continue;
                }
                if (ex instanceof AuthedXExtractor) accountResult = e;
                if (first == null) first = e;
                if (definitive == null && e.isDefinitive()) definitive = e;
                // A post with no media will not have media on another service either; don't contact it.
                if (e.kind == ExtractException.Kind.NO_MEDIA) break;
            }
        }
        // Most actionable answer first: an expired session, then what the user's own account was told.
        if (sessionProblem != null) throw sessionProblem;
        if (accountResult != null && accountResult.isDefinitive()) throw accountResult;
        ExtractException err = definitive != null ? definitive : first;
        if (err.kind == ExtractException.Kind.PRIVATE) {
            if (!signedIn) {
                throw new ExtractException(ExtractException.Kind.PRIVATE, err.getMessage()
                        + " If X restricts it (age or sensitive content), sign in from Settings to try with your own account.");
            }
            if (!useAccount) {
                throw new ExtractException(ExtractException.Kind.PRIVATE, err.getMessage()
                        + " Turn on \"Use my X account\" in Settings to try with your own account.");
            }
        }
        throw err;
    }
}
