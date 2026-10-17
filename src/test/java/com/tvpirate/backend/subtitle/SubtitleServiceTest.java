package com.tvpirate.backend.subtitle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

/** Against a fake SubDL: a mock server fails the test on any request it wasn't
 * told to expect, so "served without searching" and "keyless download" are
 * checked exactly. */
class SubtitleServiceTest {

    private static final String SEARCH_MOVIE = "https://api.subdl.com/api/v2/subtitles/search?tmdb_id=550&type=movie&languages=EN";
    private static final String SEARCH_MOVIE_PT = "https://api.subdl.com/api/v2/subtitles/search?tmdb_id=550&type=movie&languages=PT";
    private static final String SEARCH_TV = "https://api.subdl.com/api/v2/subtitles/search?tmdb_id=1396&type=tv&languages=EN&season=1&episode=2";

    private static final String SRT = "1\r\n00:00:01,000 --> 00:00:02,000\r\nHis name was Robert Paulson.\r\n";
    private static final String VTT = "WEBVTT\n\n1\n00:00:01.000 --> 00:00:02.000\nHis name was Robert Paulson.\n";

    /** One usable SRT file; its url carries the key, which must be stripped before the fetch. */
    private static final String ONE_RESULT = """
            {"status":true,"subtitles":[{"release_name":"Fight Club 1999","language":"EN","season":0,"episode":0,\
            "hi":false,"url":"/subtitle/1-2.zip?api_key=secret","unpack_files":[{"file_n_id":"abc123",\
            "name":"fc.en.srt","season":0,"episode":0,"language":"EN","hi":false,"format":"srt","md5":"m1",\
            "url":"/subtitle/1/abc123?api_key=secret"}]}]}""";

    @TempDir
    Path cacheDir;

    private MockRestServiceServer api;
    private MockRestServiceServer files;
    private SubtitleService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder apiBuilder = RestClient.builder().baseUrl("https://api.subdl.com/api/v2");
        api = MockRestServiceServer.bindTo(apiBuilder).build();
        RestClient.Builder fileBuilder = RestClient.builder().baseUrl("https://dl.subdl.com");
        files = MockRestServiceServer.bindTo(fileBuilder).build();
        service = new SubtitleService(true, cacheDir, apiBuilder.build(), fileBuilder.build());
    }

    @Test
    void aSecondRequestIsServedFromTheCacheWithoutSearching() {
        expectSearch(SEARCH_MOVIE, ONE_RESULT);
        expectFile("/subtitle/1/abc123", SRT.getBytes(StandardCharsets.UTF_8));

        byte[] first = service.resolve("movie", 550, null, null, "en");
        byte[] second = service.resolve("movie", 550, null, null, "en");

        assertThat(new String(second, StandardCharsets.UTF_8)).isEqualTo(VTT).isEqualTo(new String(first, StandardCharsets.UTF_8));
        api.verify();
        files.verify();
    }

    @Test
    void aFileAlreadyOnDiskIsServedWithoutAnyRequest() throws Exception {
        Files.writeString(cacheDir.resolve("550-movie-sxex-en.abc123.vtt"), VTT);

        assertThat(new String(service.resolve("movie", 550, null, null, "en"), StandardCharsets.UTF_8)).isEqualTo(VTT);
        api.verify();
    }

    @Test
    void aMissIsRememberedAndNotSearchedAgain() {
        expectSearch(SEARCH_MOVIE, "{\"status\":false,\"error\":\"can't find movie or tv\"}");

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> service.resolve("movie", 550, null, null, "en"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        }
        api.verify();
    }

    @Test
    void aResponseWithoutSubtitlesIsAMiss() {
        expectSearch(SEARCH_MOVIE, "{\"status\":true}");

        assertThatThrownBy(() -> service.resolve("movie", 550, null, null, "en"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        api.verify();
    }

    @Test
    void anUnshowableFormatIsTriedOnlyOnce() {
        expectSearch(SEARCH_MOVIE, ONE_RESULT);
        files.expect(requestTo("https://dl.subdl.com/subtitle/1/abc123"))
                .andRespond(withSuccess("[Script Info]\nTitle: fc", MediaType.TEXT_PLAIN));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.resolve("movie", 550, null, null, "en"))
                    .isInstanceOfSatisfying(ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
        }
        api.verify();
        files.verify();
    }

    @Test
    void aShortLanguageCodeDoesNotPickUpALongerOnesFile() throws Exception {
        Files.writeString(cacheDir.resolve("550-movie-sxex-pt-BR.abc123.vtt"), VTT);
        expectSearch(SEARCH_MOVIE_PT, "{\"status\":true,\"subtitles\":[]}");

        assertThatThrownBy(() -> service.resolve("movie", 550, null, null, "pt"))
                .isInstanceOf(ResponseStatusException.class);
        api.verify();
    }

    @Test
    void aTrackOnDiskOrARememberedMissCountsAsCached() throws Exception {
        assertThat(service.isCached("movie", 550, null, null, "en")).isFalse();
        Files.writeString(cacheDir.resolve("550-movie-sxex-en.abc123.vtt"), VTT);
        assertThat(service.isCached("movie", 550, null, null, "en")).isTrue();

        expectSearch(SEARCH_MOVIE_PT, "{\"status\":true,\"subtitles\":[]}");
        assertThat(service.isCached("movie", 550, null, null, "pt")).isFalse();
        assertThatThrownBy(() -> service.resolve("movie", 550, null, null, "pt"))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(service.isCached("movie", 550, null, null, "pt")).isTrue();
        api.verify();
    }

    @Test
    void hearingImpairedFilesAreTriedLast() {
        expectSearch(SEARCH_MOVIE, """
                {"status":true,"subtitles":[
                 {"release_name":"HI","language":"EN","season":0,"episode":0,"hi":true,"unpack_files":[
                   {"file_n_id":"hi1","name":"fc.hi.srt","season":0,"episode":0,"language":"EN","hi":true,\
                "format":"srt","md5":"m1","url":"/subtitle/1/hi1?api_key=secret"}]},
                 {"release_name":"Clean","language":"EN","season":0,"episode":0,"hi":false,"unpack_files":[
                   {"file_n_id":"ok2","name":"fc.srt","season":0,"episode":0,"language":"EN","hi":false,\
                "format":"srt","md5":"m2","url":"/subtitle/1/ok2?api_key=secret"}]}]}""");
        expectFile("/subtitle/1/ok2", SRT.getBytes(StandardCharsets.UTF_8));

        assertThat(new String(service.resolve("movie", 550, null, null, "en"), StandardCharsets.UTF_8)).isEqualTo(VTT);
        api.verify();
        files.verify();
    }

    @Test
    void aWindows1252FileIsDecoded() {
        expectSearch(SEARCH_MOVIE, ONE_RESULT);
        String line = "It\u2019s fine, just keep talking and nobody explodes. ";
        String srt = "1\r\n00:00:01,000 --> 00:00:05,000\r\n" + line.repeat(20) + "\r\n";
        expectFile("/subtitle/1/abc123", srt.getBytes(Charset.forName("windows-1252")));

        String vtt = new String(service.resolve("movie", 550, null, null, "en"), StandardCharsets.UTF_8);

        assertThat(vtt).contains("It\u2019s fine").doesNotContain("\uFFFD");
        api.verify();
        files.verify();
    }

    @Test
    void aMislabelledArabicFileFallsThroughToTheNextCandidate() {
        expectSearch(SEARCH_MOVIE, """
                {"status":true,"subtitles":[
                 {"release_name":"Arabic labelled EN","language":"EN","season":0,"episode":0,"hi":false,"unpack_files":[
                   {"file_n_id":"ar1","name":"wrong.srt","season":0,"episode":0,"language":"EN","hi":false,\
                "format":"srt","md5":"m1","url":"/subtitle/1/ar1?api_key=secret"}]},
                 {"release_name":"Real English","language":"EN","season":0,"episode":0,"hi":false,"unpack_files":[
                   {"file_n_id":"en2","name":"fc.en.srt","season":0,"episode":0,"language":"EN","hi":false,\
                "format":"srt","md5":"m2","url":"/subtitle/1/en2?api_key=secret"}]}]}""");
        expectFile("/subtitle/1/ar1", "\u0645\u0631\u062d\u0628\u0627".getBytes(Charset.forName("windows-1256")));
        expectFile("/subtitle/1/en2", SRT.getBytes(StandardCharsets.UTF_8));

        assertThat(new String(service.resolve("movie", 550, null, null, "en"), StandardCharsets.UTF_8)).isEqualTo(VTT);
        api.verify();
        files.verify();
    }

    @Test
    void aSeasonPackIsNarrowedToTheRequestedEpisode() {
        expectSearch(SEARCH_TV, """
                {"status":true,"subtitles":[{"release_name":"Breaking Bad S01","language":"EN","season":1,"episode":2,\
                "hi":false,"full_season":true,"url":"/subtitle/9-9.zip?api_key=secret","unpack_files":[
                 {"file_n_id":"ep1","name":"bb.s01e01.srt","season":1,"episode":1,"language":"EN","hi":false,\
                "format":"srt","md5":"a","url":"/subtitle/9/ep1?api_key=secret"},
                 {"file_n_id":"ep2","name":"bb.s01e02.srt","season":1,"episode":2,"language":"EN","hi":false,\
                "format":"srt","md5":"b","url":"/subtitle/9/ep2?api_key=secret"},
                 {"file_n_id":"s2e2","name":"bb.s02e02.srt","season":2,"episode":2,"language":"EN","hi":false,\
                "format":"srt","md5":"c","url":"/subtitle/9/s2e2?api_key=secret"}]}]}""");
        expectFile("/subtitle/9/ep2", SRT.getBytes(StandardCharsets.UTF_8));

        assertThat(new String(service.resolve("tv", 1396, 1, 2, "en"), StandardCharsets.UTF_8)).isEqualTo(VTT);
        api.verify();
        files.verify();
    }

    @Test
    void aQuotaErrorIsReportedAsUnavailable() {
        api.expect(requestTo(SEARCH_MOVIE))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"error\":{\"code\":\"quota_exceeded\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.resolve("movie", 550, null, null, "en"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
        api.verify();
    }

    private void expectSearch(String url, String body) {
        api.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    /** The download URL is the SubDL path only — no ?api_key= query. */
    private void expectFile(String path, byte[] body) {
        files.expect(requestTo("https://dl.subdl.com" + path))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.TEXT_PLAIN));
    }
}
