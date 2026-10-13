package com.vironite.retrievex.extract;

/**
 * Resolves a post URL into structured media metadata. The UI depends only on this interface so the
 * extraction engine can be replaced without touching any screen.
 */
public interface PostExtractor {
    String name();

    PostInfo extract(UrlParser.Parsed url) throws ExtractException;
}
