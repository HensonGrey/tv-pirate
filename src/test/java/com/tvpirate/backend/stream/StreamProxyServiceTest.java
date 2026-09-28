package com.tvpirate.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tvpirate.backend.stream.StreamProxyService.ProxyTarget;

/** Ticket ownership: the proxy's per-viewer cap only works if every ticket a
 * playlist spawns still knows who resolved it. IP-literal URLs keep the SSRF
 * guard from doing DNS lookups. */
class StreamProxyServiceTest {

    private static final long OWNER = 42L;
    private static final String PLAYLIST_URL = "https://93.184.216.34/hls/master.m3u8";

    private final StreamProxyService service = new StreamProxyService(new PublicTargetGuard());

    @Test
    void aRegisteredTicketKnowsItsOwner() {
        String token = service.register(PLAYLIST_URL, Map.of(), OWNER);

        assertThat(service.ownerOf(token)).isEqualTo(OWNER);
    }

    @Test
    void anUnknownTicketHasNoOwner() {
        assertThat(service.ownerOf("0123456789abcdef0123456789abcdef")).isNull();
    }

    @Test
    void everyTicketARewriteMintsInheritsTheOwner() throws Exception {
        String playlist = String.join("\n",
                "#EXTM3U",
                "#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\"",
                "#EXT-X-MAP:URI=\"init.mp4\"",
                "#EXTINF:6.0,",
                "seg-1.ts",
                "#EXTINF:6.0,",
                "https://93.184.216.35/seg-2.ts");

        String rewritten = new String(service.rewritePlaylist(playlist.getBytes(StandardCharsets.UTF_8),
                new ProxyTarget(PLAYLIST_URL, Map.of("Referer", "https://93.184.216.34/"), OWNER)),
                StandardCharsets.UTF_8);

        List<String> tokens = Arrays.stream(rewritten.split("/api/stream/proxy/"))
                .skip(1)
                .map(rest -> rest.substring(0, 32))
                .toList();
        assertThat(tokens).hasSize(4);
        assertThat(tokens).allSatisfy(token -> assertThat(service.ownerOf(token)).isEqualTo(OWNER));
    }
}
