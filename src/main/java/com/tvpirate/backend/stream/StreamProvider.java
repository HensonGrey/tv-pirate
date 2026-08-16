package com.tvpirate.backend.stream;

import java.util.List;
import java.util.Map;

/**
 * Uniform contract every source provider implements. Adding a provider is one
 * class registered in StreamService; a burned one is deleted just as easily.
 * vault:streaming-providers-deep-dive#architecture
 */
public interface StreamProvider {

    /** Stable id shown in the API response and used in the user's preferred-provider order. */
    String name();

    /**
     * All playable sources this provider can offer, or an empty list when it
     * can't serve the title. Fast-fail and no retries — fallback is the
     * registry's job.
     */
    List<StreamSource> resolve(ResolveRequest request);

    /** What to resolve: TMDB ids plus TV coordinates (null for movies). */
    record ResolveRequest(String mediaType, long tmdbId, Integer season, Integer episode) {
        /** Media types follow the "movie" | "tv" convention already used by MediaItem. */
        public boolean isMovie() {
            return "movie".equals(mediaType);
        }
    }

    /** One playable result: the quality label the switcher shows, the URL,
     * any headers (Referer/Origin) the CDN demands — the proxy replays them;
     * the browser never can — and the media format ("mp4" | "hls") so the
     * player knows which engine to load. */
    record StreamSource(String quality, String url, Map<String, String> headers, String format) {
    }
}
