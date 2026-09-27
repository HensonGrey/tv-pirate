package com.tvpirate.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.tvpirate.backend.stream.StreamProvider.ResolveRequest;
import com.tvpirate.backend.stream.StreamProvider.StreamSource;

/**
 * The vixsrc parse layer, driven entirely from fixture HTML/m3u8 — no socket
 * is ever opened, because these providers die weekly and a red bar should
 * mean "our parser broke", not "the internet churned". The load-bearing
 * detail is URL_PATTERN's lookbehind: the embed page carries a bare `url:`
 * line AND quoted `"url":"…?ub=1"` entries inside window.streams, and only
 * the bare one is the playlist. vault:streaming-providers-deep-dive#vixsrc-wire
 */
class VixsrcProviderTest {

    private static final String PLAYLIST_URL = "https://vixsrc.to/playlist/1376867";

    /** Every request the stubbed transport saw, in order. */
    private final List<MockClientHttpRequest> requests = new ArrayList<>();

    // --- embed-page patterns ---

    @Test
    void theTokenAndExpiresAssignmentsAreLifted() {
        String page = embedPage(PLAYLIST_URL, futureExpiry());

        assertThat(VixsrcProvider.match(VixsrcProvider.TOKEN_PATTERN, page))
                .isEqualTo("6f2b1e9d4c8a03f7b5e1");
        assertThat(VixsrcProvider.match(VixsrcProvider.EXPIRES_PATTERN, page)).isEqualTo(futureExpiry());
    }

    @Test
    void onlyTheBareUrlLineIsTakenAsThePlaylist() {
        String playlist = VixsrcProvider.match(VixsrcProvider.URL_PATTERN, embedPage(PLAYLIST_URL, futureExpiry()));

        assertThat(playlist).isEqualTo(PLAYLIST_URL);
        assertThat(playlist).doesNotContain("ub=1");
    }

    @Test
    void quotedWindowStreamsUrlsAreSkippedEvenWhenTheyComeFirst() {
        // Proves it is the lookbehind doing the work, not first-match luck.
        String page = """
                window.streams = {"Ita":{"url":"https://vixsrc.to/playlist/1376867?ub=1"}};
                window.masterPlaylist = { url: 'https://vixsrc.to/playlist/1376867' };
                """;

        assertThat(VixsrcProvider.match(VixsrcProvider.URL_PATTERN, page)).isEqualTo(PLAYLIST_URL);
    }

    @Test
    void aPageWithOnlyQuotedUrlKeysYieldsNoPlaylist() {
        String page = """
                window.streams = {"Ita":{"url":"https://vixsrc.to/playlist/1376867?ub=1"},
                                  "Eng":{"url":"https://vixsrc.to/playlist/1376867?lang=en&ub=1"}};
                """;

        assertThat(VixsrcProvider.match(VixsrcProvider.URL_PATTERN, page)).isNull();
    }

    @Test
    void anIdentifierEndingInUrlIsNotMistakenForTheUrlLine() {
        assertThat(VixsrcProvider.match(VixsrcProvider.URL_PATTERN, "var baseurl: 'https://decoy.example/x'"))
                .isNull();
    }

    // --- master-playlist renditions ---

    @Test
    void everyResolutionLineBecomesAQualityLabel() {
        List<String> heights = new ArrayList<>();
        var matcher = VixsrcProvider.RENDITION_PATTERN.matcher(MASTER_PLAYLIST);
        while (matcher.find()) {
            heights.add(matcher.group(1) + "p");
        }

        assertThat(heights).containsExactly("1080p", "480p", "720p"); // playlist order, unsorted
    }

    @Test
    void audioAndSubtitleGroupsAreNotRenditions() {
        // The EXT-X-MEDIA URI= lines and the resolution-less audio-only variant
        // must not turn into pickable qualities.
        var matcher = VixsrcProvider.RENDITION_PATTERN.matcher(MASTER_PLAYLIST);
        List<String> urls = new ArrayList<>();
        while (matcher.find()) {
            urls.add(matcher.group(2).trim());
        }

        assertThat(urls).noneMatch(url -> url.contains("/eng/")).hasSize(3);
    }

    // --- token expiry ---

    @Test
    void aTokenGoodForAnotherHourIsNotExpired() {
        assertThat(VixsrcProvider.expired(epochSecondsFromNow(3600))).isFalse();
    }

    @Test
    void anAlreadyPastExpiryIsExpired() {
        assertThat(VixsrcProvider.expired(epochSecondsFromNow(-1))).isTrue();
    }

    @Test
    void theLast60SecondsOfATokenAreTreatedAsAlreadyGone() {
        // A token dying mid-resolve is worse than no token: the grace window
        // makes us report "no sources" instead of handing over a dud URL.
        assertThat(VixsrcProvider.expired(epochSecondsFromNow(30))).isTrue();
        assertThat(VixsrcProvider.expired(epochSecondsFromNow(120))).isFalse();
    }

    @Test
    void anUnparseableExpiryFailsClosed() {
        assertThat(VixsrcProvider.expired("not-a-number")).isTrue();
        assertThat(VixsrcProvider.expired("")).isTrue();
        assertThat(VixsrcProvider.expired(null)).isTrue();
        assertThat(VixsrcProvider.expired("99999999999999999999")).isTrue(); // overflows long
    }

    // --- quality ordering ---

    @Test
    void qualityLabelsSortAscendingWithAutoLast() {
        List<String> qualities = new ArrayList<>(List.of("1080p", "auto", "480p", "2160p", "720p"));

        qualities.sort(Comparator.comparingInt(VixsrcProvider::qualityNumber));

        assertThat(qualities).containsExactly("480p", "720p", "1080p", "2160p", "auto");
    }

    @Test
    void anUnnumberedQualityIsMaxValueSoItCannotOutrankARealRendition() {
        assertThat(VixsrcProvider.qualityNumber("auto")).isEqualTo(Integer.MAX_VALUE);
        assertThat(VixsrcProvider.qualityNumber("1080p")).isEqualTo(1080);
    }

    // --- resolve, over a stubbed transport ---

    @Test
    void resolveTurnsTheMasterPlaylistIntoAscendingRenditionSources() {
        List<StreamSource> sources = providerServing(fixtures(embedPage(PLAYLIST_URL, futureExpiry()), MASTER_PLAYLIST))
                .resolve(new ResolveRequest("movie", 550L, null, null));

        assertThat(sources).extracting(StreamSource::quality).containsExactly("480p", "720p", "1080p");
        assertThat(sources).extracting(StreamSource::format).containsOnly("hls");
    }

    @Test
    void relativeRenditionUrlsResolveAgainstTheMasterUrl() {
        List<StreamSource> sources = providerServing(fixtures(embedPage(PLAYLIST_URL, futureExpiry()), MASTER_PLAYLIST))
                .resolve(new ResolveRequest("movie", 550L, null, null));

        assertThat(sources).extracting(StreamSource::url).containsExactly(
                "https://vixsrc.to/playlist/480p/index.m3u8?token=aaa&expires=111",
                "https://edge.vixsrc.to/hls/720p/index.m3u8?token=bbb", // absolute lines pass through
                "https://vixsrc.to/playlist/1080p/index.m3u8?token=ccc");
    }

    @Test
    void theMasterIsFetchedFromTheBareUrlWithTheTokenAndTheApiReferer() {
        providerServing(fixtures(embedPage(PLAYLIST_URL, futureExpiry()), MASTER_PLAYLIST))
                .resolve(new ResolveRequest("movie", 550L, null, null));

        MockClientHttpRequest master = requestTo("/playlist/");
        assertThat(master.getURI().toString())
                .startsWith(PLAYLIST_URL + "?")
                .contains("token=6f2b1e9d4c8a03f7b5e1")
                .contains("expires=" + futureExpiry())
                .contains("h=1")
                .doesNotContain("ub=1");
        assertThat(master.getHeaders().get(HttpHeaders.REFERER)).containsExactly("https://vixsrc.to/api/movie/550");
    }

    @Test
    void aBackslashEscapedBareUrlIsUnescapedBeforeTheMasterFetch() {
        providerServing(fixtures(embedPage("https:\\/\\/vixsrc.to\\/playlist\\/1376867", futureExpiry()),
                MASTER_PLAYLIST)).resolve(new ResolveRequest("movie", 550L, null, null));

        assertThat(requestTo("/playlist/").getURI().toString()).doesNotContain("\\").startsWith(PLAYLIST_URL + "?");
    }

    @Test
    void everySourceCarriesThePlaybackHeadersOnlyTheProxyCanReplay() {
        List<StreamSource> sources = providerServing(fixtures(embedPage(PLAYLIST_URL, futureExpiry()), MASTER_PLAYLIST))
                .resolve(new ResolveRequest("movie", 550L, null, null));

        assertThat(sources).allSatisfy(source -> assertThat(source.headers())
                .containsEntry("Referer", "https://vixsrc.to/api/movie/550")
                .containsKey("User-Agent"));
    }

    @Test
    void aMasterWithNoParseableRenditionsFallsBackToASingleAutoSource() {
        String bareMaster = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:6\nsegment0.ts\n";

        List<StreamSource> sources = providerServing(fixtures(embedPage(PLAYLIST_URL, futureExpiry()), bareMaster))
                .resolve(new ResolveRequest("movie", 550L, null, null));

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.quality()).isEqualTo("auto");
            assertThat(source.url()).startsWith(PLAYLIST_URL + "?");
        });
    }

    @Test
    void anExpiredTokenYieldsNoSourcesAndNoMasterFetch() {
        List<StreamSource> sources = providerServing(
                fixtures(embedPage(PLAYLIST_URL, epochSecondsFromNow(-3600)), MASTER_PLAYLIST))
                .resolve(new ResolveRequest("movie", 550L, null, null));

        assertThat(sources).isEmpty();
        assertThat(requests).noneMatch(request -> request.getURI().toString().contains("/playlist/"));
    }

    @Test
    void anEmbedPageMissingTheTokenYieldsNoSources() {
        String page = "window.masterPlaylist = { url: '" + PLAYLIST_URL + "' };";

        assertThat(providerServing(fixtures(page, MASTER_PLAYLIST))
                .resolve(new ResolveRequest("movie", 550L, null, null))).isEmpty();
    }

    @Test
    void tvResolvesThroughTheSeasonEpisodeApiPath() {
        providerServing(fixtures(embedPage(PLAYLIST_URL, futureExpiry()), MASTER_PLAYLIST))
                .resolve(new ResolveRequest("tv", 1396L, 1, 1));

        assertThat(requests.getFirst().getURI().toString()).isEqualTo("https://vixsrc.to/api/tv/1396/1/1");
        assertThat(requestTo("/playlist/").getHeaders().get(HttpHeaders.REFERER))
                .containsExactly("https://vixsrc.to/api/tv/1396/1/1");
    }

    // --- fixtures & helpers ---

    /** Renditions 1080/480/720 — deliberately out of order, so the ascending
     *  sort has something to do — plus the two decoys: EXT-X-MEDIA URI= lines
     *  and a STREAM-INF with no RESOLUTION. The 720p line is absolute. */
    private static final String MASTER_PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:6
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="English",DEFAULT=YES,URI="audio/eng/index.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",URI="subs/eng/index.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2",AUDIO="audio"
            audio/eng/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5200000,RESOLUTION=1920x1080,CODECS="avc1.640028,mp4a.40.2"
            1080p/index.m3u8?token=ccc
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480,CODECS="avc1.4d401f,mp4a.40.2",AUDIO="audio"
            480p/index.m3u8?token=aaa&expires=111
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,CODECS="avc1.4d401f,mp4a.40.2"
            https://edge.vixsrc.to/hls/720p/index.m3u8?token=bbb
            """;

    /** The embed page shape from the vault: JS assignments plus a window.streams
     *  blob whose quoted "url" keys must never win. */
    private static String embedPage(String bareUrl, String expires) {
        return """
                <!DOCTYPE html><html><head><script>
                window.streams = {"Ita":{"url":"https:\\/\\/vixsrc.to\\/playlist\\/1376867?ub=1","name":"Italiano"},
                                  "Eng":{"url":"https:\\/\\/vixsrc.to\\/playlist\\/1376867?lang=en&ub=1"}};
                window.masterPlaylist = {
                    params: { 'token': '6f2b1e9d4c8a03f7b5e1', 'expires': '%s' },
                    url: '%s',
                };
                window.canPlayFHD = true;
                </script></head><body></body></html>
                """.formatted(expires, bareUrl);
    }

    private static String futureExpiry() {
        return epochSecondsFromNow(7200);
    }

    private static String epochSecondsFromNow(long offsetSeconds) {
        return String.valueOf(System.currentTimeMillis() / 1000 + offsetSeconds);
    }

    private static Map<String, String> fixtures(String embedPage, String master) {
        Map<String, String> byUriFragment = new LinkedHashMap<>();
        byUriFragment.put("/api/", "{\"src\":\"/embed/1376867?token=x&t=y&expires=z&lang=en\"}");
        byUriFragment.put("/embed/", embedPage);
        byUriFragment.put("/playlist/", master);
        return byUriFragment;
    }

    /** A provider whose RestClient answers from the fixture map instead of the
     *  network — same baseUrl, so the relative api paths still build correctly. */
    private VixsrcProvider providerServing(Map<String, String> fixtures) {
        VixsrcProvider provider = new VixsrcProvider();
        ClientHttpRequestFactory transport = (uri, method) -> {
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            String body = fixtures.entrySet().stream()
                    .filter(fixture -> uri.toString().contains(fixture.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no fixture for " + uri));
            MockClientHttpResponse response =
                    new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(body.startsWith("{\"src\"")
                    ? MediaType.APPLICATION_JSON
                    : MediaType.TEXT_HTML);
            request.setResponse(response);
            requests.add(request);
            return request;
        };
        ReflectionTestUtils.setField(provider, "client", RestClient.builder()
                .baseUrl("https://vixsrc.to")
                .requestFactory(transport)
                .defaultHeader(HttpHeaders.USER_AGENT, "test-agent")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json, text/javascript, */*; q=0.01")
                .defaultHeader(HttpHeaders.REFERER, "https://vixsrc.to")
                .defaultHeader(HttpHeaders.ORIGIN, "https://vixsrc.to")
                .build());
        return provider;
    }

    private MockClientHttpRequest requestTo(String uriFragment) {
        return requests.stream()
                .filter(request -> request.getURI().toString().contains(uriFragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no request to " + uriFragment
                        + " — saw " + requests.stream().map(r -> URI.create(r.getURI().toString()).getPath()).toList()));
    }
}
