package com.tvpirate.backend.stream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Capability-token store + streaming passthrough for provider sources. The
 * browser gets a short-lived token instead of the real URL, and every
 * request upstream replays the source's referer/origin headers — the one
 * thing a plain &lt;video&gt; tag can never do.
 * vault:streaming-providers-deep-dive#architecture
 */
@Component
public class StreamProxyService {

    private static final Logger log = LoggerFactory.getLogger(StreamProxyService.class);

    /** Tokens outlive any one play session (pauses, seeks) but not the day. */
    private static final Duration TOKEN_TTL = Duration.ofHours(6);

    private static final String BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";

    /** Encrypted-HLS key URIs; our providers serve plain playlists, but the
     * rewrite is cheap insurance if that ever changes. */
    private static final Pattern KEY_URI_PATTERN = Pattern.compile("URI=\"([^\"]+)\"");

    private final Cache<String, ProxyTarget> targets;
    private final SimpleClientHttpRequestFactory factory;

    public StreamProxyService() {
        // A 2 h movie is ~2000 segments, each with its own token — size the
        // cache for a few concurrent movies.
        this.targets = Caffeine.newBuilder()
                .expireAfterWrite(TOKEN_TTL)
                .maximumSize(20000)
                .build();
        // Read timeout is per-read inactivity (not total) — a slow-but-alive
        // movie download must not be killed at 20 s.
        this.factory = new SimpleClientHttpRequestFactory();
        this.factory.setConnectTimeout(Duration.ofSeconds(5));
        this.factory.setReadTimeout(Duration.ofSeconds(20));
    }

    /** One playable source → one capability token. The URL and its headers
     * never reach the browser; the token is the only handle it gets. */
    public String register(String url, Map<String, String> headers) {
        String token = UUID.randomUUID().toString().replace("-", "");
        targets.put(token, new ProxyTarget(url, headers));
        return token;
    }

    /** Streams a registered target back: the browser's Range header goes
     * through, and the upstream's status + range headers come back as-is so
     * the player sees real 206s. The body is streamed — never buffered.
     *
     * Provider CDNs flap under load (segments intermittently 404/502), so a
     * failed first attempt gets ONE immediate re-fetch before giving up.
     * This is proxy resilience, not provider fallback — the target is
     * always the URL the provider resolved.
     *
     * Playlists are the exception to streaming: every URI inside one is
     * re-registered and rewritten to a new proxy token, so segments/
     * renditions never get fetched directly (their CDNs 403 requests
     * without the referer, which only we can replay). Playlists are small,
     * so buffering them is fine. */
    public ResponseEntity<InputStreamResource> stream(String token, String range) {
        ProxyTarget target = targets.getIfPresent(token);
        if (target == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                ClientHttpRequest request = factory.createRequest(URI.create(target.url()), HttpMethod.GET);
                request.getHeaders().set(HttpHeaders.USER_AGENT, BROWSER_UA);
                target.headers().forEach(request.getHeaders()::set);
                if (range != null) request.getHeaders().set(HttpHeaders.RANGE, range);
                ClientHttpResponse upstream = request.execute();

                if (upstream.getStatusCode().is4xxClientError()
                        || upstream.getStatusCode().is5xxServerError()) {
                    upstream.close();
                    if (attempt == 1) {
                        sleepBetweenRetries();
                        continue;
                    }
                    return ResponseEntity.status(upstream.getStatusCode()).build();
                }

                String contentType = upstream.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
                boolean isPlaylist = contentType != null
                        && (contentType.contains("mpegurl") || contentType.contains("m3u8"));
                if (isPlaylist) {
                    byte[] rewritten = rewritePlaylist(upstream.getBody().readAllBytes(), target);
                    return ResponseEntity.status(upstream.getStatusCode())
                            .contentType(MediaType.parseMediaType(contentType))
                            .body(new InputStreamResource(new ByteArrayInputStream(rewritten)));
                }

                HttpHeaders out = new HttpHeaders();
                copy(upstream, out, HttpHeaders.CONTENT_TYPE);
                copy(upstream, out, HttpHeaders.CONTENT_RANGE);
                copy(upstream, out, HttpHeaders.ACCEPT_RANGES);
                copy(upstream, out, HttpHeaders.CONTENT_LENGTH);
                return ResponseEntity.status(upstream.getStatusCode())
                        .headers(out)
                        .body(new InputStreamResource(upstream.getBody()));
            } catch (IOException e) {
                if (attempt == 1) {
                    sleepBetweenRetries();
                    continue;
                }
                // Unknown tokens answered above; this is an unreachable/broken CDN.
                log.warn("proxy fetch failed for token {}", token, e);
            }
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    /** Small pause between a failed fetch and its one retry — enough for a
     * flapping CDN edge to settle, short enough to not stall the player. */
    private static void sleepBetweenRetries() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Rewrites every URI line (and EXT-X-KEY URIs) to a fresh proxy token,
     * resolving relative ones against the playlist's own URL. The child
     * inherits the parent's headers — the referer requirement applies to
     * segments exactly like it applies to the playlist. */
    private byte[] rewritePlaylist(byte[] body, ProxyTarget target) throws IOException {
        URI parent = URI.create(target.url());
        List<String> rewritten = new ArrayList<>();
        for (String line : new String(body, StandardCharsets.UTF_8).split("\\r?\\n", -1)) {
            if (line.isEmpty()) {
                rewritten.add(line);
            } else if (line.startsWith("#EXT-X-KEY")) {
                Matcher matcher = KEY_URI_PATTERN.matcher(line);
                if (matcher.find()) {
                    String child = register(parent.resolve(matcher.group(1)).toString(), target.headers());
                    rewritten.add(matcher.replaceFirst("URI=\"" + Matcher.quoteReplacement("/api/stream/proxy/" + child) + "\""));
                } else {
                    rewritten.add(line);
                }
            } else if (line.startsWith("#")) {
                rewritten.add(line);
            } else {
                // Plain URI line — resolve relative to the playlist, then proxy it.
                String child = register(parent.resolve(line.trim()).toString(), target.headers());
                rewritten.add("/api/stream/proxy/" + child);
            }
        }
        return String.join("\n", rewritten).getBytes(StandardCharsets.UTF_8);
    }

    private static void copy(ClientHttpResponse from, HttpHeaders to, String name) {
        String value = from.getHeaders().getFirst(name);
        if (value != null) to.set(name, value);
    }

    private record ProxyTarget(String url, Map<String, String> headers) {}
}
