package com.tvpirate.backend.stream;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * vidsrc.hair (vidsrc family). The embed page embeds a signed session, the
 * sources call lists servers, and the race call resolves them into playable
 * HLS streams. vault:streaming-providers-deep-dive#vidsrc-hair-wire
 */
@Component
public class VidsrcHairProvider implements StreamProvider {

    private static final Logger log = LoggerFactory.getLogger(VidsrcHairProvider.class);

    private static final String REFERER = "https://vidsrc.hair/";

    /** The embed page serializes its session as `var Q = {...};` — one line, so a non-greedy regex is enough. */
    private static final Pattern Q_PATTERN = Pattern.compile("var Q = (\\{.*?\\});", Pattern.DOTALL);

    /** Hand-built mapper parses the Q blob; unknown fields are ignored (they're player UI state, not data). */
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    private final RestClient client;

    public VidsrcHairProvider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder()
                .baseUrl("https://vidsrc.hair")
                .requestFactory(factory)
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                .defaultHeader("Referer", REFERER)
                .build();
    }

    @Override
    public String name() {
        return "vidsrc-hair";
    }

    @Override
    public List<StreamSource> resolve(ResolveRequest request) {
        try {
            // The embed page answers with the session token even for a TMDB id;
            // it rewrites the id to IMDb's for its own API.
            String page = request.isMovie()
                    ? client.get().uri("/embed/movie/{id}", request.tmdbId()).retrieve().body(String.class)
                    : client.get().uri("/embed/tv/{id}/{season}/{episode}", request.tmdbId(),
                                    request.season(), request.episode())
                            .retrieve().body(String.class);
            Matcher matcher = Q_PATTERN.matcher(page == null ? "" : page);
            if (!matcher.find()) return List.of();
            Q session = MAPPER.readValue(matcher.group(1), Q.class);

            SourcesResponse sources = client.get()
                    .uri(builder -> builder.path("/api.php")
                            .queryParam("a", "sources")
                            .queryParam("type", session.type())
                            .queryParam("id", session.id())
                            .queryParam("s", session.s())
                            .queryParam("e", session.e())
                            .queryParam("t", session.t())
                            .build())
                    .retrieve()
                    .body(SourcesResponse.class);
            if (sources == null || sources.servers() == null || sources.servers().isEmpty()) {
                return List.of();
            }

            // All server refs go into one race call — the upstream resolves
            // whichever of them are actually alive and drops the rest.
            String refs = sources.servers().stream()
                    .map(Server::ref)
                    .collect(Collectors.joining(","));
            RaceResponse race = client.get()
                    .uri(builder -> builder.path("/api.php")
                            .queryParam("a", "race")
                            .queryParam("refs", refs)
                            .build())
                    .retrieve()
                    .body(RaceResponse.class);
            if (race == null || race.cands() == null) return List.of();

            // _stream answers with the master playlist directly; relative
            // urls get the site prefix. The playlist itself carries the
            // renditions, so the quality label stays "auto".
            return race.cands().stream()
                    .filter(cand -> cand.url() != null)
                    .map(cand -> new StreamSource("auto",
                            cand.url().startsWith("/") ? "https://vidsrc.hair" + cand.url() : cand.url(),
                            Map.of("Referer", REFERER), "hls"))
                    .toList();
        } catch (Exception e) {
            // Fast-fail: the caller surfaces "no sources", nothing retries here.
            log.warn("vidsrc.hair resolve failed for tmdb {}", request.tmdbId(), e);
            return List.of();
        }
    }

    // --- vidsrc.hair wire shapes (only this class may touch them) ---

    private record Q(String type, String id, Integer s, Integer e, String t) {}

    private record SourcesResponse(String status, List<Server> servers) {}

    private record Server(String ref, String name) {}

    private record RaceResponse(List<Cand> cands) {}

    private record Cand(String url, String type) {}
}
