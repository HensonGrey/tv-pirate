package com.tvpirate.backend.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Against a fake OpenSubtitles: a mock server fails the test on any request
 * it wasn't told to expect, so "served without searching" is checked exactly. */
class SubtitleServiceTest {

    private static final String SEARCH_EN = "https://api.opensubtitles.com/api/v1/subtitles?languages=en&tmdb_id=550";
    private static final String ONE_RESULT = """
            {"data":[{"id":"1","attributes":{"download_count":5,"files":[{"file_id":77,"file_name":"fc"}]}}]}""";
    private static final String VTT = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHis name was Robert Paulson.";

    @TempDir
    Path cacheDir;

    private MockRestServiceServer api;
    private MockRestServiceServer files;
    private SubtitleService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder apiBuilder = RestClient.builder().baseUrl("https://api.opensubtitles.com/api/v1");
        api = MockRestServiceServer.bindTo(apiBuilder).build();
        RestClient.Builder fileBuilder = RestClient.builder();
        files = MockRestServiceServer.bindTo(fileBuilder).build();
        service = new SubtitleService(true, cacheDir, apiBuilder.build(), fileBuilder.build());
    }

    @Test
    void aSecondRequestIsServedFromTheCacheWithoutSearching() {
        expectSearch(SEARCH_EN, ONE_RESULT);
        expectDownload();

        byte[] first = service.resolve("movie", 550, null, null, "en");
        byte[] second = service.resolve("movie", 550, null, null, "en");

        assertThat(new String(second, StandardCharsets.UTF_8)).isEqualTo(VTT).isEqualTo(new String(first, StandardCharsets.UTF_8));
        api.verify();
        files.verify();
    }

    @Test
    void aFileAlreadyOnDiskIsServedWithoutAnyRequest() throws Exception {
        Files.writeString(cacheDir.resolve("550-movie-sxex-en-77.vtt"), VTT);

        assertThat(new String(service.resolve("movie", 550, null, null, "en"), StandardCharsets.UTF_8)).isEqualTo(VTT);
        api.verify();
    }

    @Test
    void aMissIsRememberedAndNotSearchedAgain() {
        expectSearch(SEARCH_EN, "{\"data\":[]}");

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.resolve("movie", 550, null, null, "en"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        }
        api.verify();
    }

    @Test
    void anUnshowableFormatSpendsTheQuotaOnlyOnce() {
        expectSearch(SEARCH_EN, ONE_RESULT);
        api.expect(requestTo("https://api.opensubtitles.com/api/v1/download")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"link\":\"https://dl.example/fc.ass\",\"remaining\":99}", MediaType.APPLICATION_JSON));
        files.expect(requestTo("https://dl.example/fc.ass"))
                .andRespond(withSuccess("[Script Info]\nTitle: fc", MediaType.TEXT_PLAIN));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.resolve("movie", 550, null, null, "en"))
                    .isInstanceOf(ResponseStatusException.class);
        }
        api.verify();
        files.verify();
    }

    @Test
    void aShortLanguageCodeDoesNotPickUpALongerOnesFile() throws Exception {
        Files.writeString(cacheDir.resolve("550-movie-sxex-pt-BR-77.vtt"), VTT);
        expectSearch("https://api.opensubtitles.com/api/v1/subtitles?languages=pt&tmdb_id=550", "{\"data\":[]}");

        assertThatThrownBy(() -> service.resolve("movie", 550, null, null, "pt"))
                .isInstanceOf(ResponseStatusException.class);
        api.verify();
    }

    private void expectSearch(String url, String body) {
        api.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectDownload() {
        api.expect(requestTo("https://api.opensubtitles.com/api/v1/download")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"link\":\"https://dl.example/fc.vtt\",\"remaining\":99}", MediaType.APPLICATION_JSON));
        files.expect(requestTo("https://dl.example/fc.vtt")).andRespond(withSuccess(VTT, MediaType.TEXT_PLAIN));
    }
}
