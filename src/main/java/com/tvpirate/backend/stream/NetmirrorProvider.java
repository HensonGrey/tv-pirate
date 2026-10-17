package com.tvpirate.backend.stream;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * NetMirror — TMDB-id API returning progressive MP4s from one CDN.
 * vault:streaming-providers-deep-dive#netmirror-wire
 */
@ConditionalOnProperty(name = "app.providers.netmirror.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class NetmirrorProvider implements StreamProvider {

    private static final Logger log = LoggerFactory.getLogger(NetmirrorProvider.class);

    private static final String API_BASE = "https://net27.cc";

    /** The CDN answers 429 unless this exact referer is replayed by the proxy. */
    private static final String PLAYBACK_REFERER = "https://videodownloader.site/";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150 Safari/537.36";

    private final RestClient client;

    public NetmirrorProvider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .baseUrl(API_BASE)
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Referer", API_BASE + "/")
                .build();
    }

    @Override
    public String name() {
        return "netmirror";
    }

    @Override
    public List<StreamSource> resolve(ResolveRequest request) {
        try {
            NetmirrorResponse response = client.get()
                    .uri(builder -> {
                        builder.path("/api/embed-tmdb/{tmdbId}");
                        if (!request.isMovie()) {
                            builder.queryParam("type", "tv")
                                    .queryParam("se", request.season())
                                    .queryParam("ep", request.episode());
                        }
                        return builder.build(request.tmdbId());
                    })
                    .retrieve()
                    .body(NetmirrorResponse.class);
            if (response == null || !response.ok()) return List.of();

            List<StreamSource> sources = new ArrayList<>();
            if (response.streams() != null) {
                for (NetmirrorStream stream : response.streams()) {
                    if (stream.url() == null || stream.url().isBlank()) continue;
                    sources.add(new StreamSource(qualityLabel(stream.resolution()), stream.url(),
                            playbackHeaders(), "mp4"));
                }
            }
            // Some titles only expose the single mp4 fallback.
            if (sources.isEmpty() && response.mp4() != null && !response.mp4().isBlank()) {
                sources.add(new StreamSource(qualityLabel(response.resolution()), response.mp4(),
                        playbackHeaders(), "mp4"));
            }
            sources.sort(Comparator.comparingInt(s -> qualityNumber(s.quality())));
            return sources;
        } catch (RestClientResponseException e) {
            log.warn("netmirror answered with status {} for tmdb {}", e.getStatusCode().value(), request.tmdbId(), e);
            return List.of();
        } catch (RestClientException | IllegalArgumentException e) {
            // Fast-fail: an empty list is cached as "this provider can't serve the title".
            log.warn("netmirror resolve failed for tmdb {}", request.tmdbId(), e);
            return List.of();
        }
    }

    private static Map<String, String> playbackHeaders() {
        return Map.of("Referer", PLAYBACK_REFERER, "User-Agent", USER_AGENT);
    }

    private static String qualityLabel(Object resolution) {
        if (resolution == null) return "auto";
        String value = String.valueOf(resolution).replace("p", "");
        return value.matches("\\d+") ? value + "p" : "auto";
    }

    private static int qualityNumber(String quality) {
        try {
            return Integer.parseInt(quality.replace("p", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE; // "auto" sorts last
        }
    }

    // --- NetMirror wire shapes (only this class may touch them) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NetmirrorResponse(boolean ok, List<NetmirrorStream> streams, String mp4, Object resolution) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NetmirrorStream(Object resolution, String url) {}
}
