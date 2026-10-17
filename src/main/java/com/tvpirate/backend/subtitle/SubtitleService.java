package com.tvpirate.backend.subtitle;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * SubDL proxy: search by TMDB id, download once, serve from a local cache.
 * The free plan allows 50 keyed downloads a day, so cached bytes are the only
 * thing re-served; downloads are fetched keyless, keeping the key in the
 * search header alone. SRT is converted to VTT — browsers only speak WebVTT.
 * vault:subtitles-deep-dive#provider
 */
@Service
public class SubtitleService {

    private static final Logger log = LoggerFactory.getLogger(SubtitleService.class);

    private static final String API_BASE = "https://api.subdl.com/api/v2";
    private static final String DOWNLOAD_BASE = "https://dl.subdl.com";

    /** SubDL's CDN sits behind Cloudflare, which challenges browser-looking
     * clients; a plain backend UA passes. Don't reuse the OpenSubtitles UA. */
    private static final String USER_AGENT = "tv-pirate-backend";

    private static final Duration CACHE_TTL = Duration.ofDays(30);
    private static final Duration MISS_TTL = Duration.ofHours(12);

    /** Files tried per lookup: the first hit can be mislabelled or badly encoded. */
    private static final int MAX_CANDIDATES = 3;

    /** A failed UTF-8 decode with more non-ASCII bytes than this is a file in
     * another script (Arabic, Cyrillic) mislabelled as Latin — not windows-1252. */
    private static final double MAX_HIGH_BYTE_SHARE = 0.05;

    /** SRT timestamps use commas, VTT wants dots — only timestamp lines change. */
    private static final Pattern SRT_TIME = Pattern.compile("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})");

    /** Cache file names: "<titleKey>.<file_n_id>.vtt". The dot keeps "pt" from
     * matching a "pt-BR" file — ids may contain letters and dashes. */
    private static final Pattern CACHE_NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final String CACHE_SUFFIX = "\\.vtt";

    /** Titles with nothing usable (no match, or only formats browsers can't
     * show), so re-opening one doesn't search again; forgotten after MISS_TTL
     * — new subs do appear. */
    private final Cache<String, Boolean> misses = Caffeine.newBuilder()
            .expireAfterWrite(MISS_TTL)
            .maximumSize(10_000)
            .build();
    private final RestClient api;
    private final RestClient files;
    private final boolean apiKeyConfigured;
    private final Path cacheDir;

    @Autowired
    public SubtitleService(@Value("${subdl.api-key:}") String apiKey,
                           @Value("${app.subtitle-cache-dir:data/subtitles}") String cacheDir) {
        this(apiKey != null && !apiKey.isBlank(), Path.of(cacheDir),
                RestClient.builder()
                        .baseUrl(API_BASE)
                        .requestFactory(requestFactory())
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                        .build(),
                // Downloads are anonymous: the key is never put in the CDN URL,
                // which also means it can't leak into logs or CDN access records.
                RestClient.builder()
                        .baseUrl(DOWNLOAD_BASE)
                        .requestFactory(requestFactory())
                        .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                        .build());
    }

    /** Tests hand in clients bound to a fake SubDL. */
    SubtitleService(boolean apiKeyConfigured, Path cacheDir, RestClient api, RestClient files) {
        this.apiKeyConfigured = apiKeyConfigured;
        this.cacheDir = cacheDir;
        this.api = api;
        this.files = files;
    }

    private static SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    /** Whether a resolve would be answered without calling SubDL: a track on disk, or a remembered miss. */
    public boolean isCached(String mediaType, long tmdbId, Integer season, Integer episode, String lang) {
        String title = titleKey(tmdbId, mediaType, season, episode, lang);
        return readCached(title) != null || misses.getIfPresent(title) != null;
    }

    /** The subtitle track for one title/episode as VTT bytes — 404 when
     * nothing matches, 503 when the upstream says no (no key configured,
     * quota exhausted), 502 when SubDL is unreachable. */
    public byte[] resolve(String mediaType, long tmdbId, Integer season, Integer episode, String lang) {
        if (!apiKeyConfigured) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "SubDL API key not configured — add SUBDL_API_KEY to backend/.env");
        }
        String title = titleKey(tmdbId, mediaType, season, episode, lang);
        byte[] onDisk = readCached(title);
        if (onDisk != null) {
            return onDisk;
        }
        if (misses.getIfPresent(title) != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no subtitles found");
        }

        SearchResponse search;
        try {
            search = api.get()
                    .uri(builder -> {
                        builder.path("/subtitles/search");
                        builder.queryParam("tmdb_id", tmdbId);
                        builder.queryParam("type", mediaType);
                        builder.queryParam("languages", subdlLanguage(lang));
                        if (season != null) builder.queryParam("season", season);
                        if (episode != null) builder.queryParam("episode", episode);
                        return builder.build();
                    })
                    .retrieve()
                    .body(SearchResponse.class);
        } catch (RestClientResponseException e) {
            throw upstreamFailure("search", e);
        } catch (RestClientException e) {
            log.warn("SubDL search failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "SubDL is unreachable — try again shortly");
        }

        List<Candidate> candidates = candidates(search, mediaType, season, episode, lang);
        ResponseStatusException downloadFailure = null;
        for (int i = 0; i < Math.min(MAX_CANDIDATES, candidates.size()); i++) {
            Candidate candidate = candidates.get(i);
            byte[] vtt;
            try {
                vtt = toVtt(fetch(candidate));
            } catch (UnusableSubtitle e) {
                continue; // wrong script or broken encode: the next candidate may be fine
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                    continue; // a dead record, not an outage
                }
                downloadFailure = upstreamFailure("download", e);
                break;
            } catch (RestClientException e) {
                log.warn("SubDL file fetch failed", e);
                downloadFailure = new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "SubDL is unreachable — try again shortly");
                break;
            }
            cache(title, candidate, vtt);
            return vtt;
        }
        if (downloadFailure != null) {
            throw downloadFailure;
        }
        misses.put(title, Boolean.TRUE); // nothing usable: don't search again for a while
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no subtitles found");
    }

    /** Every file the lookup could use, hearing-impaired last and API order otherwise.
     * Language and `hi` flags are self-reported and unreliable, so this only narrows
     * the list — the decode checks in {@link #toVtt} do the real filtering. */
    private static List<Candidate> candidates(SearchResponse search, String mediaType,
                                              Integer season, Integer episode, String lang) {
        if (search == null || search.subtitles() == null) return List.of();
        String wanted = subdlLanguage(lang);
        boolean tv = "tv".equals(mediaType);
        int wantedSeason = season == null ? 0 : season;
        int wantedEpisode = episode == null ? 0 : episode;

        List<Candidate> usable = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Subtitle subtitle : search.subtitles()) {
            if (subtitle == null || subtitle.unpackFiles() == null) continue;
            for (SubFile file : subtitle.unpackFiles()) {
                if (file == null || file.url() == null) continue;
                if (!isTextFormat(file)) continue;
                if (!languageMatches(wanted, file.language())) continue;
                if (tv && !matchesEpisode(file, wantedSeason, wantedEpisode)) continue;
                String id = fileId(file);
                if (id == null || !seen.add(id)) continue; // the same upload is often listed twice
                usable.add(new Candidate(file, id, hearingImpaired(subtitle, file)));
            }
        }
        // Stable sort: equal flags keep the API's order, which is the only ranking there is.
        usable.sort(Comparator.comparingInt(c -> c.hearingImpaired() ? 1 : 0));
        return usable;
    }

    /** Season 0 means "unknown" and is allowed; every other season must match. */
    private static boolean matchesEpisode(SubFile file, int season, int episode) {
        int fileEpisode = file.episode() == null ? 0 : file.episode();
        int fileSeason = file.season() == null ? 0 : file.season();
        return fileEpisode == episode && (fileSeason == season || fileSeason == 0);
    }

    /** An unknown code is ignored upstream and an empty field is common, so a
     * blank language is let through and judged by the file's bytes instead. */
    private static boolean languageMatches(String wanted, String actual) {
        return actual == null || actual.isBlank() || wanted.equals(subdlLanguage(actual));
    }

    private static boolean isTextFormat(SubFile file) {
        String format = file.format() == null ? "" : file.format().toLowerCase(Locale.ROOT);
        if (format.equals("srt") || format.equals("vtt")) return true;
        if (format.equals("ass") || format.equals("ssa") || format.equals("sub")) return false;
        String name = file.name() == null ? "" : file.name().toLowerCase(Locale.ROOT);
        return name.endsWith(".srt") || name.endsWith(".vtt");
    }

    private static boolean hearingImpaired(Subtitle subtitle, SubFile file) {
        return Boolean.TRUE.equals(file.hi()) || Boolean.TRUE.equals(subtitle.hi());
    }

    private static String fileId(SubFile file) {
        String id = file.fileNId() != null && !file.fileNId().isBlank() ? file.fileNId() : file.md5();
        if (id == null || id.isBlank()) return null; // nothing to key the cache on
        return CACHE_NAME.matcher(id).matches() ? id : id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** "pt-BR" → "BR_PT", "en" → "EN": SubDL's language codes are its own. */
    static String subdlLanguage(String lang) {
        String code = lang == null ? "" : lang.replace('-', '_').toUpperCase(Locale.ROOT);
        return code.equals("PT_BR") ? "BR_PT" : code;
    }

    /** The search URL carries the key in a header; the download URL is stripped
     * of its ?api_key= query and fetched anonymously. */
    private byte[] fetch(Candidate candidate) {
        String path = pathOf(candidate.file().url());
        byte[] body = files.get()
                .uri(URI.create(DOWNLOAD_BASE + path))
                .retrieve()
                .body(byte[].class);
        if (body == null || body.length == 0) throw new UnusableSubtitle();
        return body;
    }

    private static String pathOf(String url) {
        String path;
        try {
            path = URI.create(url).getRawPath();
        } catch (IllegalArgumentException e) {
            throw new UnusableSubtitle();
        }
        if (path == null || path.isBlank()) throw new UnusableSubtitle();
        return path.startsWith("/") ? path : "/" + path;
    }

    /** UTF-8 first; a mostly-ASCII file that isn't valid UTF-8 is windows-1252.
     * Anything with a lot of high bytes is another script under a false label. */
    private static String decode(byte[] bytes) {
        int offset = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
            return stripBom(text);
        } catch (CharacterCodingException e) {
            if (highByteShare(bytes, offset) > MAX_HIGH_BYTE_SHARE) {
                throw new UnusableSubtitle();
            }
            return stripBom(new String(bytes, offset, bytes.length - offset, Charset.forName("windows-1252")));
        }
    }

    private static double highByteShare(byte[] bytes, int offset) {
        int total = bytes.length - offset;
        if (total <= 0) return 0;
        int high = 0;
        for (int i = offset; i < bytes.length; i++) {
            if (bytes[i] < 0) high++;
        }
        return (double) high / total;
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** WEBVTT passes through; SRT gets the header plus dot timestamps. */
    private static byte[] toVtt(byte[] content) {
        String text = decode(content).replace("\r\n", "\n");
        if (text.startsWith("[Script Info]") || text.startsWith(";FFMETADATA")) {
            throw new UnusableSubtitle(); // ASS/SSA slipped through: browsers can't show it
        }
        if (text.startsWith("WEBVTT")) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        if (!text.contains("-->")) {
            throw new UnusableSubtitle(); // not a timed subtitle at all
        }
        return ("WEBVTT\n\n" + SRT_TIME.matcher(text).replaceAll("$1.$2"))
                .getBytes(StandardCharsets.UTF_8);
    }

    private void cache(String title, Candidate candidate, byte[] vtt) {
        cleanupExpired();
        try {
            Files.createDirectories(cacheDir);
            Files.write(cacheDir.resolve(title + "." + candidate.id() + ".vtt"), vtt);
        } catch (IOException e) {
            // Serving beats caching — the download still worked.
            log.warn("subtitle cache write failed for {}", title, e);
        }
    }

    /** One title/episode/language, e.g. "1396-tv-s1e2-en"; its cache file is "<key>.<file_n_id>.vtt". */
    private static String titleKey(long tmdbId, String mediaType, Integer season, Integer episode, String lang) {
        String s = season == null ? "x" : String.valueOf(season);
        String e = episode == null ? "x" : String.valueOf(episode);
        return String.format("%d-%s-s%se%s-%s", tmdbId, mediaType, s, e, lang);
    }

    /** The cached track for a title, found by name before any search — so a
     * hit costs no SubDL call. */
    private byte[] readCached(String title) {
        Pattern name = Pattern.compile(Pattern.quote(title) + "\\.[A-Za-z0-9_-]+" + CACHE_SUFFIX);
        try (var paths = Files.list(cacheDir)) {
            Path hit = paths.filter(path -> name.matcher(path.getFileName().toString()).matches())
                    .findFirst().orElse(null);
            return hit == null ? null : Files.readAllBytes(hit);
        } catch (IOException e) {
            return null; // no cache dir yet, or an unreadable file: fall through to a search
        }
    }

    /** Lazy sweep after each successful download: drop cache files past the
     * TTL. The dir stays tiny — worst case a few stale files linger. */
    private void cleanupExpired() {
        try (var paths = Files.list(cacheDir)) {
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

    private static ResponseStatusException upstreamFailure(String stage, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        log.warn("SubDL {} answered with status {}", stage, status); // never log the URI: it may carry the key
        if (status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "SubDL daily quota exhausted — try again tomorrow");
        }
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.FORBIDDEN.value()) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "SubDL rejects this API key — check SUBDL_API_KEY");
        }
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "SubDL is not answering right now — try again shortly");
    }

    /** A file this lookup can't use; the next candidate gets a turn. */
    private static final class UnusableSubtitle extends RuntimeException {
        UnusableSubtitle() {
            super(null, null, false, false);
        }
    }

    // --- SubDL wire shapes (only this class may touch them) ---

    private record SearchResponse(Boolean status, List<Subtitle> subtitles) {}

    private record Subtitle(
            @JsonProperty("release_name") String releaseName,
            String lang,
            String language,
            Integer season,
            Integer episode,
            Boolean hi,
            @JsonProperty("full_season") Boolean fullSeason,
            String url,
            @JsonProperty("unpack_files") List<SubFile> unpackFiles) {}

    private record SubFile(
            @JsonProperty("file_n_id") String fileNId,
            String name,
            Integer season,
            Integer episode,
            String language,
            Boolean hi,
            String format,
            String size,
            String md5,
            String url) {}

    private record Candidate(SubFile file, String id, boolean hearingImpaired) {}
}
