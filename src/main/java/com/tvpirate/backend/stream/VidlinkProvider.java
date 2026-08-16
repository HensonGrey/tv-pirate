package com.tvpirate.backend.stream;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Vidlink (vidlink.pro). The TMDB id goes through vidlink's encryption
 * helper, then /api/b returns a per-quality map of signed CDN urls — those
 * urls are referer-sensitive, so every source carries the headers the CDN
 * demands. vault:streaming-providers-deep-dive#vidlink-wire
 *
 * DISABLED 2026-08-15: /api/b has been serving STALE vault urls for days
 * (t= timestamps a week old) — the CDN answers them with 428/429, so every
 * successful resolve dies at playback. vidlink's own player avoids this via
 * its encrypted /api/mercury DASH flow. Re-enable when the vault refreshes.
 */
// @Component — see the note above; the registry picks up beans only.
public class VidlinkProvider implements StreamProvider {

    private static final Logger log = LoggerFactory.getLogger(VidlinkProvider.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";

    private final RestClient vidlink;
    private final RestClient encDec;

    public VidlinkProvider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.vidlink = RestClient.builder()
                .baseUrl("https://vidlink.pro")
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Referer", "https://vidlink.pro")
                .build();
        this.encDec = RestClient.builder()
                .baseUrl("https://enc-dec.app")
                .requestFactory(factory)
                .build();
    }

    @Override
    public String name() {
        return "vidlink";
    }

    @Override
    public List<StreamSource> resolve(ResolveRequest request) {
        try {
            String encoded = encode(request.tmdbId());
            if (encoded == null) return List.of();
            VaultResponse response = request.isMovie()
                    ? vidlink.get().uri("/api/b/movie/{id}", encoded)
                            .retrieve().body(VaultResponse.class)
                    : vidlink.get().uri("/api/b/tv/{id}/{season}/{episode}", encoded,
                                    request.season(), request.episode())
                            .retrieve().body(VaultResponse.class);
            if (response == null || response.stream() == null || response.stream().qualities() == null) {
                return List.of();
            }
            return response.stream().qualities().entrySet().stream()
                    .sorted(Comparator.comparingInt(entry -> qualityNumber(entry.getKey())))
                    .map(entry -> new StreamSource(
                            entry.getKey() + "p",
                            entry.getValue().url(),
                            entry.getValue().headers() == null ? Map.of() : entry.getValue().headers(),
                            "mp4"))
                    .toList();
        } catch (RestClientException | IllegalArgumentException e) {
            // Fast-fail: the caller surfaces "no sources", nothing retries here.
            log.warn("Vidlink resolve failed for tmdb {}", request.tmdbId(), e);
            return List.of();
        }
    }

    /** Vidlink's /api/b wants the TMDB id in its encrypted form (enc-dec.app helper). */
    private String encode(long tmdbId) {
        EncResponse response = encDec.get()
                .uri(builder -> builder.path("/api/enc-vidlink")
                        .queryParam("text", tmdbId)
                        .build())
                .retrieve()
                .body(EncResponse.class);
        return response == null ? null : response.result();
    }

    private static int qualityNumber(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE; // unknown keys sort last
        }
    }

    // --- Vidlink wire shapes (only this class may touch them) ---

    private record EncResponse(Integer status, String result) {}

    private record VaultResponse(String sourceId, StreamInfo stream) {}

    private record StreamInfo(Map<String, Quality> qualities) {}

    private record Quality(String type, String url, Map<String, String> headers) {}
}
