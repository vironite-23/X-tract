package com.vironite.retrievex.extract;

/** Error raised while resolving a post URL into media metadata. The message is safe to show to the user. */
public class ExtractException extends Exception {
    public enum Kind {
        INVALID_URL, UNSUPPORTED_URL, DELETED, PRIVATE, NO_MEDIA, EXTRACTOR_FAILURE, TIMEOUT, NETWORK, RATE_LIMITED, SESSION_EXPIRED
    }

    public final Kind kind;

    public ExtractException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ExtractException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    /** True when the problem is with the input itself, so trying another extractor would not help. */
    public boolean isInputProblem() {
        return kind == Kind.INVALID_URL || kind == Kind.UNSUPPORTED_URL;
    }

    /** True when the error is about the post's availability rather than a technical failure. */
    public boolean isDefinitive() {
        return kind == Kind.DELETED || kind == Kind.PRIVATE || kind == Kind.NO_MEDIA;
    }
}
