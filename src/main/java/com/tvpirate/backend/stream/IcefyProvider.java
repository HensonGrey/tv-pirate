package com.tvpirate.backend.stream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Icefy (streams.icefy.top) — the simplest provider: one API call answers
 * with the master playlist url, and the playlist carries the renditions.
 * Every request replays the site's own Referer/Origin — the proxy must,
 * because the browser can't. vault:streaming-providers-deep-dive#icefy-wire
 *
 * DISABLED 2026-08-15: segments live on TikTok's CDN (p16-sg.tiktokcdn.com)
 * and are session-bound — they 403 even for real browsers outside icefy's
 * own player and for our proxy with any header combo. Resolution works,
 * playback can't. Kept registered-out until the CDN puzzle is solved.
 */
// @Component — see the CDN note above; the registry picks up beans only.
public class IcefyProvider implements StreamProvider {

    private static final Logger log = LoggerFactory.getLogger(IcefyProvider.class);

    private static final String BASE_URL = "https://streams.icefy.top";

    private static final String REFERER = BASE_URL + "/";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";

    /** Master playlist renditions: each RESOLUTION line is followed by its variant URL. */
    private static final Pattern RENDITION_PATTERN =
            Pattern.compile("#EXT-X-STREAM-INF:[^\\n]*RESOLUTION=\\d+x(\\d+)[^\\n]*\\n([^\\n]+)");

    private final RestClient client;

    public IcefyProvider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Referer", REFERER)
                .build();
    }

    @Override
    public String name() {
        return "icefy";
    }

    @Override
    public List<StreamSource> resolve(ResolveRequest request) {
        try {
            IceResponse response = request.isMovie()
                    ? client.get().uri("/movie/{id}", request.tmdbId()).retrieve().body(IceResponse.class)
                    : client.get().uri("/tv/{id}/{season}/{episode}", request.tmdbId(),
                                    request.season(), request.episode())
                            .retrieve().body(IceResponse.class);
            if (response == null || response.stream() == null) return List.of();

            // One extra fetch parses the renditions so the picker can offer real quality rows;
            // it also fails fast when the playlist behind the answer is already gone.
            String master = client.get()
                    .uri(response.stream())
                    .header("Origin", BASE_URL)
                    .retrieve()
                    .body(String.class);
            if (master == null) return List.of();

            List<StreamSource> sources = new ArrayList<>();
            Matcher matcher = RENDITION_PATTERN.matcher(master);
            while (matcher.find()) {
                int height = Integer.parseInt(matcher.group(1));
                sources.add(new StreamSource(height + "p", matcher.group(2).trim(),
                        Map.of("Referer", REFERER, "Origin", BASE_URL), "hls"));
            }
            if (sources.isEmpty()) {
                // No parseable renditions — the master itself is still playable, so hand it over unlabelled.
                return List.of(new StreamSource("auto", response.stream(),
                        Map.of("Referer", REFERER, "Origin", BASE_URL), "hls"));
            }
            sources.sort(Comparator.comparingInt(s -> qualityNumber(s.quality())));
            return sources;
        } catch (RestClientException | IllegalArgumentException e) {
            // Fast-fail: the caller surfaces "no sources", nothing retries here.
            log.warn("icefy resolve failed for tmdb {}", request.tmdbId(), e);
            return List.of();
        }
    }

    private static int qualityNumber(String quality) {
        try {
            return Integer.parseInt(quality.replace("p", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE; // "auto" sorts last
        }
    }

    // --- icefy wire shapes (only this class may touch them) ---

    private record IceResponse(String stream) {}
}
