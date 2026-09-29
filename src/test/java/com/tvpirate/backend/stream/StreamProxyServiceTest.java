package com.tvpirate.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.tvpirate.backend.stream.StreamProxyService.ProxyTarget;

/** Root tickets are stored, playlist children are sealed: the store must not
 * grow with playlist fetches, and a child must still reach its target with
 * its root's headers and owner. IP-literal URLs keep the SSRF guard from
 * doing DNS lookups. */
class StreamProxyServiceTest {

    private static final long OWNER = 42L;
    private static final String PLAYLIST_URL = "https://93.184.216.34/hls/master.m3u8";
    private static final Map<String, String> REFERER = Map.of("Referer", "https://93.184.216.34/");
    private static final Pattern TICKET = Pattern.compile("/api/stream/proxy/([A-Za-z0-9._-]+)");

    private final StreamProxyService service = new StreamProxyService(new PublicTargetGuard());

    @Test
    void aRootTicketKnowsItsOwner() {
        String root = service.register(PLAYLIST_URL, REFERER, OWNER);

        assertThat(service.ownerOf(root)).isEqualTo(OWNER);
        assertThat(service.storedTicketCount()).isEqualTo(1);
    }

    @Test
    void anUnknownTicketHasNoOwner() {
        assertThat(service.ownerOf("0123456789abcdef0123456789abcdef")).isNull();
        assertThat(service.ownerOf("s.bm90LWEtdGlja2V0")).isNull();
    }

    @Test
    void everyChildARewriteMintsIsSealedAndInheritsTheOwner() throws Exception {
        String root = service.register(PLAYLIST_URL, REFERER, OWNER);
        String playlist = String.join("\n",
                "#EXTM3U",
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
                "#EXT-X-MAP:URI=\"init.mp4\"",
                "#EXTINF:6.0,",
                "seg-1.ts",
                "#EXTINF:6.0,",
                "https://93.184.216.35/seg-2.ts");

        List<String> children = tickets(rewrite(playlist, root));

        assertThat(children).hasSize(4).allMatch(TicketSealer::isSealed);
        assertThat(children).allSatisfy(child -> assertThat(service.ownerOf(child)).isEqualTo(OWNER));
        assertThat(service.storedTicketCount()).isEqualTo(1);
    }

    @Test
    void theRewrittenPlaylistNeverShowsACdnUrl() throws Exception {
        String root = service.register(PLAYLIST_URL, REFERER, OWNER);

        String rewritten = rewrite("#EXTM3U\n#EXTINF:6.0,\nhttps://93.184.216.35/secret/seg-2.ts", root);

        assertThat(rewritten).doesNotContain("93.184.216.35").doesNotContain("secret");
    }

    @Test
    void fetchingABigPlaylistFiftyTimesStoresNothingButTheRoot() throws Exception {
        String root = service.register(PLAYLIST_URL, REFERER, OWNER);
        String playlist = "#EXTM3U\n" + IntStream.range(0, 2000)
                .mapToObj(i -> "#EXTINF:6.0,\nseg-" + i + ".ts")
                .collect(Collectors.joining("\n"));

        for (int fetch = 0; fetch < 50; fetch++) {
            rewrite(playlist, root);
        }

        assertThat(service.storedTicketCount()).isEqualTo(1);
    }

    @Test
    void aNestedPlaylistsChildrenStillPointAtTheOriginalRoot() throws Exception {
        String root = service.register(PLAYLIST_URL, REFERER, OWNER);
        String media = tickets(rewrite("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nmedia-720.m3u8", root)).getFirst();

        // The media playlist is fetched through its sealed ticket, so its own rewrite runs on the child's target.
        ProxyTarget mediaTarget = new ProxyTarget("https://93.184.216.34/hls/media-720.m3u8", REFERER, OWNER, root);
        String segment = tickets(new String(service.rewritePlaylist(
                "#EXTM3U\n#EXTINF:6.0,\nseg-1.ts".getBytes(StandardCharsets.UTF_8), mediaTarget),
                StandardCharsets.UTF_8)).getFirst();

        assertThat(service.ownerOf(media)).isEqualTo(OWNER);
        assertThat(service.ownerOf(segment)).isEqualTo(OWNER);
        assertThat(new TicketSealer().unseal(segment)).isNull(); // sealed by the service's own key only
    }

    @Test
    void aChildDiesWithItsRoot() throws Exception {
        StreamProxyService other = new StreamProxyService(new PublicTargetGuard());
        String otherRoot = other.register(PLAYLIST_URL, REFERER, OWNER);
        String child = tickets(new String(other.rewritePlaylist("#EXTM3U\nseg-1.ts".getBytes(StandardCharsets.UTF_8),
                new ProxyTarget(PLAYLIST_URL, REFERER, OWNER, otherRoot)), StandardCharsets.UTF_8)).getFirst();

        // Opened by a service that never stored that root (and holds another key): unknown.
        assertThat(service.ownerOf(child)).isNull();
        assertThat(service.stream(child, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aTamperedChildIsA404() throws Exception {
        String root = service.register(PLAYLIST_URL, REFERER, OWNER);
        String child = tickets(rewrite("#EXTM3U\nseg-1.ts", root)).getFirst();
        char last = child.charAt(child.length() - 1);
        String tampered = child.substring(0, child.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThat(service.ownerOf(tampered)).isNull();
        assertThat(service.stream(tampered, null).getStatusCode().value()).isEqualTo(404);
    }

    // --- helpers ---

    private String rewrite(String playlist, String root) throws Exception {
        return new String(service.rewritePlaylist(playlist.getBytes(StandardCharsets.UTF_8),
                new ProxyTarget(PLAYLIST_URL, REFERER, OWNER, root)), StandardCharsets.UTF_8);
    }

    private static List<String> tickets(String rewritten) {
        Matcher matcher = TICKET.matcher(rewritten);
        List<String> found = new java.util.ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
