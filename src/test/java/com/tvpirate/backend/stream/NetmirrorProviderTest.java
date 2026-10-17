package com.tvpirate.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.tvpirate.backend.stream.StreamProvider.ResolveRequest;
import com.tvpirate.backend.stream.StreamProvider.StreamSource;

/**
 * NetMirror's JSON-to-source mapping. No socket is opened: the wire is a
 * fixture, so a red bar means our parser changed, not that the provider died.
 * vault:streaming-providers-deep-dive#netmirror-wire
 */
class NetmirrorProviderTest {

    private final List<MockClientHttpRequest> requests = new ArrayList<>();

    @Test
    void streamsBecomeMp4SourcesWithThePlaybackReferer() {
        List<StreamSource> sources = providerServing(MOVIE_JSON).resolve(movie(550));

        assertThat(sources).extracting(StreamSource::quality).containsExactly("360p", "720p");
        assertThat(sources).allSatisfy(source -> {
            assertThat(source.format()).isEqualTo("mp4");
            assertThat(source.headers()).containsEntry("Referer", "https://videodownloader.site/");
        });
    }

    @Test
    void anEmptyStreamListFallsBackToTheSingleMp4() {
        List<StreamSource> sources = providerServing("""
                {"ok":true,"streams":[],"mp4":"https://cdn.example/fallback.mp4","resolution":"720"}
                """).resolve(movie(550));

        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.quality()).isEqualTo("720p");
            assertThat(source.url()).isEqualTo("https://cdn.example/fallback.mp4");
            assertThat(source.format()).isEqualTo("mp4");
        });
    }

    @Test
    void noStreamsAndNoMp4YieldNothing() {
        assertThat(providerServing("""
                {"ok":true,"streams":[],"mp4":null}
                """).resolve(movie(550))).isEmpty();
    }

    @Test
    void okFalseYieldsNothing() {
        assertThat(providerServing("""
                {"ok":false,"streams":[{"url":"https://cdn.example/720.mp4","resolution":720}]}
                """).resolve(movie(550))).isEmpty();
    }

    @Test
    void aStreamWithoutAResolutionIsLabelledAuto() {
        List<StreamSource> sources = providerServing("""
                {"ok":true,"streams":[{"url":"https://cdn.example/auto.mp4"}]}
                """).resolve(movie(550));

        assertThat(sources).singleElement()
                .satisfies(source -> assertThat(source.quality()).isEqualTo("auto"));
    }

    @Test
    void tvResolvesThroughTheSeasonAndEpisodeParams() {
        providerServing(MOVIE_JSON).resolve(new ResolveRequest("tv", 1396L, 1, 2));

        String query = requestTo("/api/embed-tmdb/").getURI().getQuery();
        assertThat(query).contains("type=tv", "se=1", "ep=2");
    }

    @Test
    void a429IsReportedAsNoSources() {
        assertThat(providerServing("{}", HttpStatus.TOO_MANY_REQUESTS).resolve(movie(550))).isEmpty();
    }

    // --- fixtures & helpers ---

    private static final String MOVIE_JSON = """
            {
              "ok": true,
              "title": "Fight Club",
              "streams": [
                {"url": "https://cdn.example/720.mp4", "resolution": 720, "size": 872092884},
                {"url": "https://cdn.example/360.mp4", "resolution": 360, "size": 363984526}
              ],
              "mp4": "https://cdn.example/fallback.mp4",
              "resolution": "720"
            }
            """;

    private static ResolveRequest movie(long tmdbId) {
        return new ResolveRequest("movie", tmdbId, null, null);
    }

    private NetmirrorProvider providerServing(String body) {
        return providerServing(body, HttpStatus.OK);
    }

    private NetmirrorProvider providerServing(String body, HttpStatus status) {
        NetmirrorProvider provider = new NetmirrorProvider();
        RestClient client = RestClient.builder()
                .baseUrl("https://net27.cc")
                .requestFactory((uri, method) -> {
                    MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
                    MockClientHttpResponse response =
                            new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
                    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    request.setResponse(response);
                    requests.add(request);
                    return request;
                })
                .build();
        ReflectionTestUtils.setField(provider, "client", client);
        return provider;
    }

    private MockClientHttpRequest requestTo(String fragment) {
        return requests.stream()
                .filter(request -> request.getURI().toString().contains(fragment))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no request to " + fragment));
    }
}
