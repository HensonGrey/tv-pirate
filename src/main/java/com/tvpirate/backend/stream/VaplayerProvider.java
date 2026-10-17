package com.tvpirate.backend.stream;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.tvpirate.backend.tmdb.TmdbClient;

/**
 * VaPlayer — IMDb-id API handing out HLS masters that proxy to MPEG-TS segments.
 * vault:streaming-providers-deep-dive#vaplayer-wire
 */
@ConditionalOnProperty(name = "app.providers.vaplayer.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class VaplayerProvider implements StreamProvider {

    private static final Logger log = LoggerFactory.getLogger(VaplayerProvider.class);

    private static final String API_BASE = "https://streamdata.vaplayer.ru";

    private static final String PLAYER_ORIGIN = "https://nextgencloudfabric.com";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150 Safari/537.36";

    /** Master lines: RESOLUTION=640x266 then the variant URI on the next line. */
    static final Pattern RENDITION_PATTERN =
            Pattern.compile("#EXT-X-STREAM-INF:[^\\n]*RESOLUTION=\\d+x(\\d+)[^\\n]*\\n([^\\n]+)");

    private final RestClient client;
    private final TmdbClient tmdbClient;

    public VaplayerProvider(TmdbClient tmdbClient) {
        this.tmdbClient = tmdbClient;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .baseUrl(API_BASE)
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Referer", PLAYER_ORIGIN + "/")
                .defaultHeader("Origin", PLAYER_ORIGIN)
                .build();
    }

    @Override
    public String name() {
        return "vaplayer";
    }

    @Override
    public List<StreamSource> resolve(ResolveRequest request) {
        try {
            String imdb = tmdbClient.imdbId(request.mediaType(), request.tmdbId());
            if (imdb == null || !imdb.startsWith("tt")) return List.of();

            String referer = PLAYER_ORIGIN + (request.isMovie()
                    ? "/embed/movie/" + imdb
                    : "/embed/tv/" + imdb + "/" + request.season() + "/" + request.episode());
            VaplayerResponse response = client.get()
                    .uri(builder -> {
                        builder.path("/api.php")
                                .queryParam("imdb", imdb)
                                .queryParam("type", request.isMovie() ? "movie" : "tv");
                        if (!request.isMovie()) {
                            builder.queryParam("season", request.season())
                                    .queryParam("episode", request.episode());
                        }
                        return builder.build();
                    })
                    .header("Referer", referer)
                    .retrieve()
                    .body(VaplayerResponse.class);
            if (response == null
                    || (response.statusCode() != null && !"200".equals(String.valueOf(response.statusCode())))
                    || response.data() == null
                    || response.data().streamUrls() == null) {
                return List.of();
            }

            // The API hands out several mirrors with the same renditions; use the first one that parses.
            for (String masterUrl : response.data().streamUrls()) {
                if (masterUrl == null || masterUrl.isBlank()) continue;
                try {
                    List<StreamSource> sources = parseMaster(masterUrl);
                    if (!sources.isEmpty()) return sources;
                } catch (RestClientResponseException e) {
                    log.warn("vaplayer master answered with status {} for tmdb {}",
                            e.getStatusCode().value(), request.tmdbId(), e);
                } catch (RestClientException | IllegalArgumentException e) {
                    log.warn("vaplayer master fetch failed for tmdb {}", request.tmdbId(), e);
                }
            }
            return List.of();
        } catch (RestClientResponseException e) {
            log.warn("vaplayer answered with status {} for tmdb {}", e.getStatusCode().value(), request.tmdbId(), e);
            return List.of();
        } catch (RestClientException | IllegalArgumentException e) {
            // Fast-fail: an empty list is cached as "this provider can't serve the title".
            log.warn("vaplayer resolve failed for tmdb {}", request.tmdbId(), e);
            return List.of();
        }
    }

    private List<StreamSource> parseMaster(String masterUrl) {
        String master = client.get()
                .uri(URI.create(masterUrl))
                .header("Referer", PLAYER_ORIGIN + "/")
                .header("Origin", PLAYER_ORIGIN)
                .retrieve()
                .body(String.class);
        if (master == null || !master.startsWith("#EXTM3U")) return List.of();

        URI masterUri = URI.create(masterUrl);
        Matcher matcher = RENDITION_PATTERN.matcher(master);
        List<StreamSource> sources = new ArrayList<>();
        while (matcher.find()) {
            int height = Integer.parseInt(matcher.group(1));
            String url = masterUri.resolve(matcher.group(2).trim()).toString();
            sources.add(new StreamSource(height + "p", url, playbackHeaders(), "hls"));
        }
        if (sources.isEmpty()) {
            // No parseable renditions — the master itself still plays.
            return List.of(new StreamSource("auto", masterUrl, playbackHeaders(), "hls"));
        }
        sources.sort(Comparator.comparingInt(s -> qualityNumber(s.quality())));
        return sources;
    }

    private static Map<String, String> playbackHeaders() {
        return Map.of("Referer", PLAYER_ORIGIN + "/", "Origin", PLAYER_ORIGIN, "User-Agent", USER_AGENT);
    }

    private static int qualityNumber(String quality) {
        try {
            return Integer.parseInt(quality.replace("p", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE; // "auto" sorts last
        }
    }

    // --- VaPlayer wire shapes (only this class may touch them) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VaplayerResponse(@JsonProperty("status_code") Object statusCode, VaplayerData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VaplayerData(@JsonProperty("stream_urls") List<String> streamUrls) {}
}
