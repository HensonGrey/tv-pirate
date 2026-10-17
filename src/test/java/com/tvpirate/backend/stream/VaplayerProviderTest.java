package com.tvpirate.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.tvpirate.backend.stream.StreamProvider.ResolveRequest;
import com.tvpirate.backend.stream.StreamProvider.StreamSource;
import com.tvpirate.backend.tmdb.TmdbClient;

/**
 * VaPlayer's IMDb request, master parsing and header passthrough, all from
 * fixtures. vault:streaming-providers-deep-dive#vaplayer-wire
 */
class VaplayerProviderTest {

    private final List<MockClientHttpRequest> requests = new ArrayList<>();

    @Test
    void everyRenditionBecomesAnAscendingHlsSource() {
        List<StreamSource> sources = providerServing(API_JSON, MASTER).resolve(movie(550));

        assertThat(sources).extracting(StreamSource::quality).containsExactly("266p", "534p", "800p");
        assertThat(sources).extracting(StreamSource::format).containsOnly("hls");
    }

    @Test
    void relativeRenditionUrlsResolveAgainstTheMasterUrl() {
        List<StreamSource> sources = providerServing(API_JSON, MASTER).resolve(movie(550));

        assertThat(sources).extracting(StreamSource::url).containsExactly(
                "https://cdn.example/480/index.m3u8",
                "https://cdn.example/720/index.m3u8",
                "https://cdn.example/1080/index.m3u8");
    }

    @Test
    void everySourceCarriesThePlaybackHeaders() {
        List<StreamSource> sources = providerServing(API_JSON, MASTER).resolve(movie(550));

        assertThat(sources).allSatisfy(source -> assertThat(source.headers())
                .containsEntry("Referer", "https://nextgencloudfabric.com/")
                .containsEntry("Origin", "https://nextgencloudfabric.com"));
    }

    @Test
    void aMasterWithNoRenditionsFallsBackToAuto() {
        List<StreamSource> sources = providerServing(API_JSON, "#EXTM3U\n#EXT-X-VERSION:3\nsegment.ts\n")
                .resolve(movie(550));

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.quality()).isEqualTo("auto");
            assertThat(source.url()).isEqualTo("https://cdn.example/master.m3u8");
        });
    }

    @Test
    void tvResolvesThroughSeasonAndEpisodeWithTheEmbedReferer() {
        providerServing("tt0903747", API_JSON, MASTER).resolve(new ResolveRequest("tv", 1396L, 1, 2));

        MockClientHttpRequest api = requestTo("/api.php");
        assertThat(api.getURI().getQuery()).contains("type=tv", "season=1", "episode=2");
        assertThat(api.getHeaders().get("Referer"))
                .containsExactly("https://nextgencloudfabric.com/embed/tv/tt0903747/1/2");
    }

    @Test
    void aMissingImdbIdYieldsNothing() {
        assertThat(providerServing(null, API_JSON, MASTER).resolve(movie(550))).isEmpty();
        assertThat(requests).isEmpty();
    }

    @Test
    void aNon200StatusYieldsNothing() {
        assertThat(providerServing("""
                {"status_code":"500","data":null}
                """, MASTER).resolve(movie(550))).isEmpty();
    }

    // --- fixtures & helpers ---

    private static final String API_JSON = """
            {
              "status_code": "200",
              "data": {
                "title": "Fight Club 1999",
                "file_name": "Fight Club (1999) [1080p]/Fight.Club.mkv",
                "stream_urls": ["https://cdn.example/master.m3u8"]
              }
            }
            """;

    private static final String MASTER = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=682018,RESOLUTION=640x266
            480/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2332661,RESOLUTION=1280x534
            720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4462464,RESOLUTION=1920x800
            1080/index.m3u8
            """;

    private static ResolveRequest movie(long tmdbId) {
        return new ResolveRequest("movie", tmdbId, null, null);
    }

    private VaplayerProvider providerServing(String apiJson, String master) {
        return providerServing("tt0137523", apiJson, master);
    }

    private VaplayerProvider providerServing(String imdb, String apiJson, String master) {
        Map<String, String> fixtures = new LinkedHashMap<>();
        fixtures.put("/api.php", apiJson);
        fixtures.put("/master.m3u8", master);

        VaplayerProvider provider = new VaplayerProvider(tmdbServing(imdb));
        RestClient client = RestClient.builder()
                .baseUrl("https://streamdata.vaplayer.ru")
                .requestFactory((uri, method) -> {
                    MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
                    String body = fixtures.entrySet().stream()
                            .filter(fixture -> uri.toString().contains(fixture.getKey()))
                            .map(Map.Entry::getValue)
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("no fixture for " + uri));
                    MockClientHttpResponse response =
                            new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    response.getHeaders().setContentType(body.startsWith("#EXTM3U")
                            ? MediaType.parseMediaType("application/vnd.apple.mpegurl")
                            : MediaType.APPLICATION_JSON);
                    request.setResponse(response);
                    requests.add(request);
                    return request;
                })
                .build();
        ReflectionTestUtils.setField(provider, "client", client);
        return provider;
    }

    /** A TmdbClient whose one external_ids call answers with this IMDb id. */
    private static TmdbClient tmdbServing(String imdb) {
        RestClient tmdb = RestClient.builder()
                .baseUrl("https://api.themoviedb.org/3")
                .requestFactory((uri, method) -> {
                    MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
                    String body = imdb == null ? "{}" : "{\"imdb_id\":\"" + imdb + "\"}";
                    MockClientHttpResponse response =
                            new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    request.setResponse(response);
                    return request;
                })
                .build();
        return new TmdbClient(tmdb);
    }

    private MockClientHttpRequest requestTo(String fragment) {
        return requests.stream()
                .filter(request -> request.getURI().toString().contains(fragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no request to " + fragment));
    }
}
