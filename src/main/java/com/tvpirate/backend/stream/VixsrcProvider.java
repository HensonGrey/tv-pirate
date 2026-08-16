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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * vixsrc.to — vidsrc-style HLS provider. The API hands out a signed embed
 * link, the embed page exposes the playlist token, and the master playlist
 * carries per-quality renditions with embedded subs.
 * vault:streaming-providers-deep-dive#vixsrc-wire
 */
@Component
public class VixsrcProvider implements StreamProvider {

    private static final Logger log = LoggerFactory.getLogger(VixsrcProvider.class);

    private static final String BASE_URL = "https://vixsrc.to";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150 Safari/537.36";

    /** The embed page writes token/expires/url as JS assignments. The lookbehind on url
     *  matches only the bare `url:` line — not the quoted `"url"` key inside window.streams. */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("['\"]token['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern EXPIRES_PATTERN = Pattern.compile("['\"]expires['\"]\\s*:\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern URL_PATTERN = Pattern.compile("(?<![\\w'\"])url\\s*:\\s*['\"]([^'\"]+)['\"]");

    /** Master playlist renditions: each RESOLUTION line is followed by its variant URL. */
    private static final Pattern RENDITION_PATTERN =
            Pattern.compile("#EXT-X-STREAM-INF:[^\\n]*RESOLUTION=\\d+x(\\d+)[^\\n]*\\n([^\\n]+)");

    private final RestClient client;

    public VixsrcProvider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .defaultHeader("Referer", BASE_URL)
                .defaultHeader("Origin", BASE_URL)
                .build();
    }

    @Override
    public String name() {
        return "vixsrc";
    }

    @Override
    public List<StreamSource> resolve(ResolveRequest request) {
        try {
            String apiUrl = request.isMovie()
                    ? "/api/movie/" + request.tmdbId()
                    : "/api/tv/" + request.tmdbId() + "/" + request.season() + "/" + request.episode();
            ApiResponse api = client.get().uri(apiUrl).retrieve().body(ApiResponse.class);
            if (api == null || api.src() == null || api.src().isBlank()) return List.of();

            // The src is single-use (a reused one answers 410 Gone), so fetch it right away.
            String embedHtml = client.get()
                    .uri(URI.create(BASE_URL + api.src()))
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .retrieve()
                    .body(String.class);
            if (embedHtml == null) return List.of();

            String token = match(TOKEN_PATTERN, embedHtml);
            String expires = match(EXPIRES_PATTERN, embedHtml);
            String playlistUrl = match(URL_PATTERN, embedHtml);
            if (token == null || expires == null || playlistUrl == null || expired(expires)) {
                return List.of();
            }

            // h=1 marks the token-bearing request; the playlist call wants the API url as its Referer.
            String masterUrl = playlistUrl.replace("\\", "")
                    + (playlistUrl.contains("?") ? "&" : "?")
                    + "token=" + token + "&expires=" + expires + "&h=1";
            String master = client.get()
                    .uri(URI.create(masterUrl))
                    .header("Referer", BASE_URL + apiUrl)
                    .retrieve()
                    .body(String.class);
            if (master == null) return List.of();

            List<StreamSource> sources = new ArrayList<>();
            Matcher matcher = RENDITION_PATTERN.matcher(master);
            while (matcher.find()) {
                int height = Integer.parseInt(matcher.group(1));
                sources.add(new StreamSource(height + "p", matcher.group(2).trim(),
                        playbackHeaders(BASE_URL + apiUrl), "hls"));
            }
            if (sources.isEmpty()) {
                // No parseable renditions — the master itself is still playable, so hand it over unlabelled.
                return List.of(new StreamSource("auto", masterUrl, playbackHeaders(BASE_URL + apiUrl), "hls"));
            }
            sources.sort(Comparator.comparingInt(s -> qualityNumber(s.quality())));
            return sources;
        } catch (RestClientException | IllegalArgumentException e) {
            // Fast-fail: the caller surfaces "no sources", nothing retries here.
            log.warn("vixsrc resolve failed for tmdb {}", request.tmdbId(), e);
            return List.of();
        }
    }

    private static String match(Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Tokens are seconds since epoch; anything past its expiry (minus a 60s grace) is dead on arrival. */
    private static boolean expired(String expires) {
        try {
            return Long.parseLong(expires) * 1000L - 60_000 < System.currentTimeMillis();
        } catch (NumberFormatException e) {
            return true;
        }
    }

    /** Every CDN request under a source replays these — the browser can't send Referer, the proxy will. */
    private static Map<String, String> playbackHeaders(String apiUrl) {
        return Map.of("Referer", apiUrl, "User-Agent", USER_AGENT);
    }

    private static int qualityNumber(String quality) {
        try {
            return Integer.parseInt(quality.replace("p", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE; // "auto" sorts last
        }
    }

    // --- vixsrc wire shapes (only this class may touch them) ---

    private record ApiResponse(String src) {}
}
