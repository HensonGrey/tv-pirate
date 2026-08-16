package com.tvpirate.backend.stream;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.tvpirate.backend.tmdb.TmdbClient;
import com.tvpirate.backend.tmdb.TmdbService;
import com.tvpirate.backend.tmdb.dto.MediaItem;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Videasy (player.videasy.to) — the TMDB-Embed-API plugin is stale
 * (api.videasy.net is dead); this follows the live frontend's backend,
 * api.speedracelight.com. Sources come back encrypted with a per-seed
 * stream cipher ported 1:1 from their player bundle (magic "mvm1").
 * vault:streaming-providers-deep-dive#provider-5-hunt
 */
@Component
public class VideasyProvider implements StreamProvider {

    private static final Logger log = LoggerFactory.getLogger(VideasyProvider.class);

    private static final String API_BASE = "https://api.speedracelight.com";

    private static final String REFERER = "https://player.videasy.to/";

    private static final String ORIGIN = "https://player.videasy.to";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";

    /** Server preference: cdn = HLS renditions up to 2160p, downloader2 = mp4 fallback. */
    private static final List<String> SERVERS = List.of("cdn", "downloader2");

    /** Golden-ratio constant their cipher mixes in everywhere. */
    private static final int PHI = 0x9E3779B9;

    private static final byte[] MAGIC = {109, 118, 109, 49}; // "mvm1"

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final RestClient client;
    private final TmdbService tmdbService;
    private final TmdbClient tmdbClient;

    public VideasyProvider(TmdbService tmdbService, TmdbClient tmdbClient) {
        this.tmdbService = tmdbService;
        this.tmdbClient = tmdbClient;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.client = RestClient.builder()
                .baseUrl(API_BASE)
                .requestFactory(factory)
                .defaultHeader("User-Agent", USER_AGENT)
                .defaultHeader("Referer", REFERER)
                .defaultHeader("Origin", ORIGIN)
                .build();
    }

    @Override
    public String name() {
        return "videasy";
    }

    @Override
    public List<StreamSource> resolve(ResolveRequest request) {
        try {
            MediaItem detail = tmdbService.detail(request.mediaType(), request.tmdbId());
            String imdb = tmdbClient.imdbId(request.mediaType(), request.tmdbId());
            if (detail == null || detail.title() == null || detail.year() == null || imdb == null) {
                return List.of();
            }
            String seed = seed(request.tmdbId());
            if (seed == null) return List.of();

            // Seeds are single-use-ish: the frontend re-fetches and retries on 401.
            SourcesResponse sources = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    sources = fetchSources(request, detail, imdb, seed);
                    break;
                } catch (HttpClientErrorException e) {
                    if (e.getStatusCode().value() != 401 || attempt == 2) throw e;
                    seed = seed(request.tmdbId());
                    if (seed == null) return List.of();
                }
            }
            if (sources == null || sources.sources() == null) return List.of();
            return sources.sources().stream()
                    .filter(s -> s.url() != null)
                    .map(s -> new StreamSource(
                            s.quality() == null ? "auto" : s.quality(),
                            s.url(),
                            Map.of("Referer", REFERER, "Origin", ORIGIN),
                            s.url().contains(".m3u8") ? "hls" : "mp4"))
                    .sorted(Comparator.comparingInt(s -> qualityNumber(s.quality())))
                    .toList();
        } catch (RestClientException | IllegalArgumentException e) {
            // Fast-fail: the caller surfaces "no sources", nothing retries here.
            log.warn("videasy resolve failed for tmdb {}", request.tmdbId(), e);
            return List.of();
        }
    }

    /** One server per attempt, first non-empty answer wins (cdn → downloader2). */
    private SourcesResponse fetchSources(ResolveRequest request, MediaItem detail, String imdb, String seed) {
        for (String server : SERVERS) {
            String query = "title=" + doubleEncode(detail.title())
                    + "&mediaType=" + request.mediaType()
                    + "&year=" + detail.year();
            if (!request.isMovie()) {
                if (detail.seasons() != null) query += "&totalSeasons=" + detail.seasons();
                query += "&seasonId=" + request.season() + "&episodeId=" + request.episode();
            }
            query += "&tmdbId=" + request.tmdbId() + "&imdbId=" + imdb + "&enc=2&seed=" + seed;
            String body = client.get()
                    .uri(URI.create(API_BASE + "/" + server + "/sources-with-title?" + query))
                    .retrieve()
                    .body(String.class);
            if (body == null) continue;
            // The payload is raw base64, occasionally JSON-quoted — unquote if needed.
            String payload = body.trim();
            if (payload.startsWith("\"") && payload.endsWith("\"")) {
                payload = payload.substring(1, payload.length() - 1);
            }
            try {
                SourcesResponse sources = MAPPER.readValue(decrypt(payload, seed, request.tmdbId()), SourcesResponse.class);
                if (sources != null && sources.sources() != null && !sources.sources().isEmpty()) {
                    return sources;
                }
            } catch (Exception e) {
                // Bad decrypt (seed burned, payload shape changed) — try the next server.
                log.warn("videasy decrypt failed on server {}", server, e);
            }
        }
        return new SourcesResponse(List.of());
    }

    private String seed(long mediaId) {
        SeedResponse response = client.get()
                .uri(builder -> builder.path("/seed").queryParam("mediaId", mediaId).build())
                .retrieve()
                .body(SeedResponse.class);
        return response == null ? null : response.seed();
    }

    private static int qualityNumber(String quality) {
        try {
            return Integer.parseInt(quality.replace("p", ""));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE; // "auto" sorts last
        }
    }

    // --- videasy cipher, ported 1:1 from their player bundle ---
    // All values are 32-bit; Java int wrapping equals JS Math.imul/>>>0 on bits.

    /** xmur3-ish mixer: every keystream round ends with one of these. */
    private static int mix(int e) {
        e ^= e >>> 16;
        e *= 0x85EBCA6B;
        e ^= e >>> 13;
        e *= 0xC2B2AE35;
        e ^= e >>> 16;
        return e;
    }

    /** FNV-1a over the seed string, then mixed. */
    private static int fnv(String s) {
        int t = 0x811C9DC5;
        for (int i = 0; i < s.length(); i++) {
            t = (t ^ s.charAt(i)) * 16777619;
        }
        return mix(t);
    }

    private static int rotl(int e, int t) {
        return Integer.rotateLeft(e, t & 31);
    }

    /** The keystream state: a 61-entry sbox with only 8 slots ever written.
     * JS tracks slot existence with `n in r`, so occupancy is explicit —
     * unwritten slots read as 0 regardless. */
    private record State(int[] s, boolean[] filled, int acc) {}

    private static State state(String seed, long mediaId) {
        int[] s = new int[61];
        boolean[] filled = new boolean[61];
        int a = mix(fnv(seed) ^ mix(((int) mediaId) ^ PHI));
        for (int e = 0; e < 8; e++) {
            // JS % runs on the UNSIGNED value — a signed-int mod would
            // differ by 2^32 mod 61 from theirs.
            int t = (int) (Integer.toUnsignedLong(a) % 61);
            a = rotl(a + PHI, 7 + (7 & e));
            s[t] = a ^ mix(a);
            filled[t] = true;
            a = mix(a + t);
        }
        return new State(s, filled, mix(0xA5A5A5A5 ^ a));
    }

    /** One generator round per 4 output bytes, exactly like their JS. */
    private static byte[] keystream(String seed, long mediaId, int length) {
        State st = state(seed, mediaId);
        int[] s = st.s();
        boolean[] filled = st.filled();
        int acc = st.acc();
        byte[] out = new byte[length];
        int e = 0, t = 0;
        while (e < length) {
            // Unsigned mod — same JS quirk as the sbox indices above.
            int n = (int) (Integer.toUnsignedLong(acc) % 61);
            int i = filled[n] ? -1 : 0;
            int x = s[n] ^ (int) ((long) PHI * (t + 1));
            int l = (acc ^ x) | (acc & x & i);
            l = rotl(l + acc, n & 31) ^ rotl(acc, (n * 7) & 31);
            acc = mix(l + PHI);
            s[n] = acc;
            filled[n] = true;
            t++;
            int word = acc;
            out[e++] = (byte) word;
            if (e < length) out[e++] = (byte) (word >>> 8);
            if (e < length) out[e++] = (byte) (word >>> 16);
            if (e < length) out[e++] = (byte) (word >>> 24);
        }
        return out;
    }

    /** Base64 → XOR keystream → verify "mvm1" magic → the JSON after it. */
    private static String decrypt(String payload, String seed, long mediaId) {
        String b64 = payload.replace("-", "+").replace("_", "/");
        while (b64.length() % 4 != 0) b64 += "=";
        byte[] cipher = Base64.getDecoder().decode(b64);
        byte[] key = keystream(seed, mediaId, cipher.length);
        byte[] clear = new byte[cipher.length];
        for (int i = 0; i < cipher.length; i++) clear[i] = (byte) (cipher[i] ^ key[i]);
        for (int i = 0; i < MAGIC.length; i++) {
            if (i >= clear.length || clear[i] != MAGIC[i]) {
                throw new IllegalArgumentException("bad seed or tampered payload");
            }
        }
        return new String(clear, MAGIC.length, clear.length - MAGIC.length, StandardCharsets.UTF_8);
    }

    /** encodeURIComponent-compatible encoding (Java's encoder is close — only
     * the !'()~ escapes differ). Applied twice: their frontend pre-encodes
     * and the HTTP layer encodes again. */
    private static String doubleEncode(String value) {
        return encodeComponent(encodeComponent(value));
    }

    private static String encodeComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%21", "!")
                .replace("%27", "'")
                .replace("%28", "(")
                .replace("%29", ")")
                .replace("%7E", "~");
    }

    // --- videasy wire shapes (only this class may touch them) ---

    private record SeedResponse(String seed) {}

    private record SourcesResponse(List<VSource> sources) {}

    private record VSource(String quality, String url) {}
}
