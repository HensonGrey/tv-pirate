package com.tvpirate.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import com.tvpirate.backend.ratelimit.ProxyConcurrencyInterceptor;
import com.tvpirate.backend.stream.StreamProxyService.ProxyTarget;

/** The proxy's 8-per-viewer cap must count sealed pieces against the viewer who
 * pressed Play — here in the stream package, the only place sealed tickets can be minted. */
class SealedTicketConcurrencyTest {

    private static final String PLAYLIST_URL = "https://93.184.216.34/hls/media.m3u8";
    private static final Pattern TICKET = Pattern.compile("/api/stream/proxy/([A-Za-z0-9._-]+)");

    private final StreamProxyService service = new StreamProxyService(new PublicTargetGuard());
    private final ProxyConcurrencyInterceptor interceptor = new ProxyConcurrencyInterceptor(service);

    @Test
    void sealedPiecesCountAgainstTheirViewer() throws Exception {
        List<String> pieces = sealedPieces(1L, 9);

        for (String piece : pieces.subList(0, 8)) {
            assertThat(admit(piece)).isTrue();
        }
        assertThat(admit(pieces.get(8))).isFalse();
        assertThat(admit(sealedPieces(2L, 1).getFirst())).isTrue(); // another viewer is unaffected
    }

    private List<String> sealedPieces(long owner, int count) throws Exception {
        String root = service.register(PLAYLIST_URL, Map.of(), owner);
        String playlist = "#EXTM3U\n" + IntStream.range(0, count)
                .mapToObj(i -> "#EXTINF:4.0,\nseg-" + i + ".ts")
                .collect(Collectors.joining("\n"));
        String rewritten = new String(service.rewritePlaylist(playlist.getBytes(StandardCharsets.UTF_8),
                new ProxyTarget(PLAYLIST_URL, Map.of(), owner, root)), StandardCharsets.UTF_8);
        List<String> tickets = new ArrayList<>();
        Matcher matcher = TICKET.matcher(rewritten);
        while (matcher.find()) {
            tickets.add(matcher.group(1));
        }
        return tickets;
    }

    private boolean admit(String ticket) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/stream/proxy/" + ticket);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("token", ticket));
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }
}
