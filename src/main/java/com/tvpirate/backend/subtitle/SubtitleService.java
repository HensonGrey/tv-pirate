package com.tvpirate.backend.subtitle;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenSubtitles proxy: search by TMDB id, download once, serve from a local
 * cache. Download links expire and every one counts against the small daily
 * quota, so only the cached bytes (keyed by file_id) are ever re-served.
 * SRT is converted to VTT — browser players only speak WebVTT.
 * vault:streaming-providers-deep-dive#subtitles
 */
@Service
public class SubtitleService {

    private static final Logger log = LoggerFactory.getLogger(SubtitleService.class);

    private static final String BASE_URL = "https://api.opensubtitles.com/api/v1";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";

    private static final Path CACHE_DIR = Path.of("data", "subtitles");
    private static final Duration CACHE_TTL = Duration.ofDays(30);

    /** SRT timestamps use commas, VTT wants dots — only timestamp lines change. */
    private static final Pattern SRT_TIME = Pattern.compile("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})");

    private final RestClient api;
    private final RestClient fileFetcher;
    private final boolean apiKeyConfigured;

    public SubtitleService(@Value("${opensubtitles.api-key:}") String apiKey) {
        this.apiKeyConfigured = apiKey != null && !apiKey.isBlank();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.api = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(factory)
                .defaultHeader("Api-Key", apiKey)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Content-Type", "application/json")
                .build();
        // The download link is an arbitrary one-shot CDN URL — it gets no
        // Api-Key header, so the key never leaves OpenSubtitles' API host.
        this.fileFetcher = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .build();
    }

    /** The subtitle track for one title/episode as VTT bytes — 404 when
     * nothing matches, 503 when the upstream says no (no key configured,
     * quota exhausted), 502 when OpenSubtitles is unreachable. */
    public byte[] resolve(String mediaType, long tmdbId, Integer season, Integer episode, String lang) {
        if (!apiKeyConfigured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenSubtitles API key not configured — add OPENSUBTITLES_API_KEY to backend/.env");
        }
        try {
            SearchResponse search = api.get()
                    .uri(builder -> {
                        // Canonical (alphabetical) param order only — the Kong
                        // gateway 301s any other ordering (X-OS-Rule: canonical).
                        builder.path("/subtitles");
                        if (episode != null) builder.queryParam("episode_number", episode);
                        builder.queryParam("languages", lang);
                        if (season != null) builder.queryParam("season_number", season);
                        builder.queryParam("tmdb_id", tmdbId);
                        return builder.build();
                    })
                    .retrieve()
                    .body(SearchResponse.class);
            Entry best = pick(search);
            if (best == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no subtitles found");
            }

            Path cached = cachePath(tmdbId, mediaType, season, episode, lang, best);
            if (Files.exists(cached)) {
                return Files.readAllBytes(cached);
            }

            byte[] content = download(best);
            content = toVtt(content);
            cleanupExpired();
            try {
                Files.createDirectories(CACHE_DIR);
                Files.write(cached, content);
            } catch (IOException e) {
                // Serving beats caching — the download still worked.
                log.warn("subtitle cache write failed for {}", cached, e);
            }
            return content;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RestClientResponseException e) {
            log.warn("OpenSubtitles answered with status {}", e.getStatusCode().value(), e);
            if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "OpenSubtitles daily quota exhausted — try again tomorrow");
            }
            if (e.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()
                    || e.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "OpenSubtitles rejects this API key — check the key and the consumer's anonymous-downloads setting");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "OpenSubtitles is not answering right now — try again shortly");
        } catch (RestClientException | IOException e) {
            log.warn("OpenSubtitles call failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "OpenSubtitles is unreachable — try again shortly");
        }
    }

    /** Best = most-downloaded clean subtitle. Hearing-impaired and machine
     * translations are penalised, not banned — a title with only HI subs
     * still gets captions over none. */
    private static Entry pick(SearchResponse search) {
        if (search == null || search.data() == null) return null;
        return search.data().stream()
                .filter(Entry::hasFiles)
                .sorted(Comparator
                        .comparingInt((Entry e) -> penalty(e.attributes()))
                        .thenComparing(Entry::downloadCount, Comparator.reverseOrder()))
                .findFirst()
                .orElse(null);
    }

    private static int penalty(Attributes attributes) {
        if (attributes == null) return 0;
        return (Boolean.TRUE.equals(attributes.hearingImpaired()) ? 1 : 0)
                + (Boolean.TRUE.equals(attributes.machineTranslated()) ? 1 : 0);
    }

    /** POST /download hands back a one-shot link; the body of that link is
     * the subtitle file. Only the bytes are kept — the link expires. */
    private byte[] download(Entry best) {
        DownloadResponse download = api.post()
                .uri("/download")
                // sub_format=vtt: search file_names carry no extension, so the
                // upstream does the conversion — and ASS never reaches us.
                .body(new DownloadRequest(best.fileId(), "vtt"))
                .retrieve()
                .body(DownloadResponse.class);
        if (download == null || download.link() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "subtitle download link missing");
        }
        log.info("OpenSubtitles quota after download: {} remaining (file {})", download.remaining(), best.fileId());
        return fileFetcher.get()
                .uri(URI.create(download.link()))
                .retrieve()
                .body(byte[].class);
    }

    /** Cache file per subtitle file_id — quota-safe: re-resolving a title
     * that maps to the same file never downloads again. */
    private static Path cachePath(long tmdbId, String mediaType, Integer season, Integer episode, String lang, Entry best) {
        String s = season == null ? "x" : String.valueOf(season);
        String e = episode == null ? "x" : String.valueOf(episode);
        return CACHE_DIR.resolve(String.format("%d-%s-s%se%s-%s-%d.vtt", tmdbId, mediaType, s, e, lang, best.fileId()));
    }

    /** WEBVTT passes through; SRT gets the header plus dot timestamps. */
    private static byte[] toVtt(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        if (text.startsWith("WEBVTT")) return content;
        if (text.startsWith("[Script Info]")) {
            // ASS slipped through despite sub_format=vtt — browsers can't show it.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "subtitle format unsupported");
        }
        return ("WEBVTT\n\n" + SRT_TIME.matcher(text).replaceAll("$1.$2"))
                .getBytes(StandardCharsets.UTF_8);
    }

    /** Lazy sweep after each successful download: drop cache files past the
     * TTL. The dir stays tiny — worst case a few stale files linger. */
    private void cleanupExpired() {
        try (var paths = Files.list(CACHE_DIR)) {
            Instant cutoff = Instant.now().minus(CACHE_TTL);
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException ignored) {
                            // A locked/stale file just survives one more sweep.
                        }
                    });
        } catch (IOException ignored) {
            // First run: the dir doesn't exist yet.
        }
    }

    // --- OpenSubtitles wire shapes (only this class may touch them) ---

    private record SearchResponse(List<Entry> data) {}

    private record Entry(String id, Attributes attributes) {
        boolean hasFiles() {
            return attributes != null && attributes.files() != null && !attributes.files().isEmpty();
        }

        int downloadCount() {
            return attributes == null || attributes.downloadCount() == null ? 0 : attributes.downloadCount();
        }

        long fileId() {
            return attributes.files().get(0).fileId();
        }
    }

    private record Attributes(
            @JsonProperty("hearing_impaired") Boolean hearingImpaired,
            @JsonProperty("machine_translated") Boolean machineTranslated,
            @JsonProperty("download_count") Integer downloadCount,
            List<FileInfo> files) {}

    private record FileInfo(@JsonProperty("file_id") long fileId, @JsonProperty("file_name") String fileName) {}

    private record DownloadRequest(@JsonProperty("file_id") long fileId,
                                   @JsonProperty("sub_format") String subFormat) {}

    private record DownloadResponse(String link, Integer remaining, String message) {}
}
