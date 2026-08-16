package com.tvpirate.backend.tmdb;

import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The only class that knows TMDB's wire format; {@link TmdbService}
 * translates these into the API shapes our frontend consumes. If TMDB
 * changes their JSON, this file is the only one that has to move.
 */
@Component
public class TmdbClient {

    private final RestClient tmdb;

    public TmdbClient(RestClient tmdbRestClient) {
        this.tmdb = tmdbRestClient;
    }

    /** GET /trending/all/{window} — mixed movies + shows, sorted by popularity. */
    public TmdbPage<TmdbEntry> trendingAll(String window, int page) {
        return getPage("/trending/all/{window}", window, page);
    }

    /** Per-type search: /search/multi's people results bury the titles for
     * short queries. vault:tmdb-deep-dive#search */
    public TmdbPage<TmdbEntry> searchMovies(String query, int page) {
        return searchPage("/search/movie", query, page);
    }

    public TmdbPage<TmdbEntry> searchShows(String query, int page) {
        return searchPage("/search/tv", query, page);
    }

    private TmdbPage<TmdbEntry> searchPage(String path, String query, int page) {
        return tmdb.get()
                .uri(builder -> builder.path(path)
                        .queryParam("query", query)
                        .queryParam("page", page)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<TmdbPage<TmdbEntry>>() {});
    }

    /**
     * GET /discover/{type} — popularity-sorted, optionally narrowed to the
     * given genre ids (comma-separated). Empty/blank ids means "no filter".
     */
    public TmdbPage<TmdbEntry> discover(String type, String genreIdsCsv, int page) {
        return tmdb.get()
                .uri(builder -> builder.path("/discover/{type}")
                        .queryParam("sort_by", "popularity.desc")
                        .queryParam("with_genres", blankToNull(genreIdsCsv))
                        .queryParam("page", page)
                        .build(type))
                .retrieve()
                .body(new ParameterizedTypeReference<TmdbPage<TmdbEntry>>() {});
    }

    /** GET /movie/{id} — full detail including runtime in minutes. */
    public TmdbMovieDetail movieDetail(long id) {
        return tmdb.get()
                .uri("/movie/{id}", id)
                .retrieve()
                .body(TmdbMovieDetail.class);
    }

    /** GET /tv/{id} — full detail including seasons/episodes. */
    public TmdbTvDetail tvDetail(long id) {
        return tmdb.get()
                .uri("/tv/{id}", id)
                .retrieve()
                .body(TmdbTvDetail.class);
    }

    /** GET /tv/{id}/season/{n} — the episode list for one season. */
    public TmdbSeasonDetail tvSeason(long id, int season) {
        return tmdb.get()
                .uri("/tv/{id}/season/{season}", id, season)
                .retrieve()
                .body(TmdbSeasonDetail.class);
    }

    /** GET /{type}/{id}/external_ids — the IMDb id providers like videasy want. */
    @Cacheable(cacheNames = "tmdb-imdb-id", key = "#type + ':' + #id")
    public String imdbId(String type, long id) {
        ExternalIdsResponse response = tmdb.get()
                .uri("/{type}/{id}/external_ids", type, id)
                .retrieve()
                .body(ExternalIdsResponse.class);
        return response == null ? null : response.imdbId();
    }

    /** Cached 24 h: the genre tables are static and every result list needs them. */
    @Cacheable(cacheNames = "tmdb-genres", key = "#type")
    public List<GenreEntry> genreTable(String type) {
        GenreListResponse response = tmdb.get()
                .uri("/genre/{type}/list", type)
                .retrieve()
                .body(GenreListResponse.class);
        return response == null ? List.of() : response.genres();
    }

    /** Cached 7 days: image CDN settings effectively never change. */
    @Cacheable(cacheNames = "tmdb-image-config", key = "'config'")
    public ImageSettings imageConfig() {
        ImageConfigurationResponse response = tmdb.get()
                .uri("/configuration")
                .retrieve()
                .body(ImageConfigurationResponse.class);
        return response == null ? null : response.images();
    }

    private TmdbPage<TmdbEntry> getPage(String pathTemplate, String window, int page) {
        return tmdb.get()
                .uri(builder -> builder.path(pathTemplate)
                        .queryParam("page", page)
                        .build(window))
                .retrieve()
                .body(new ParameterizedTypeReference<TmdbPage<TmdbEntry>>() {});
    }

    /** RestClient omits null query params — pass empty strings through as null. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // --- Raw TMDB wire shapes (private: only this class may touch them) ---

    /** Wrapper every list endpoint returns: results + pagination metadata.
     * Needs {@link ParameterizedTypeReference} at the call site — generics
     * are erased at runtime. */
    record TmdbPage<T>(int page, List<T> results, int totalPages, int totalResults) {}

    /** One list entry; fields come and go per endpoint, so everything is
     * nullable — the service decides what's real. */
    record TmdbEntry(
            long id,
            @JsonProperty("media_type") String mediaType,
            String title,
            String name,
            String overview,
            @JsonProperty("poster_path") String posterPath,
            @JsonProperty("backdrop_path") String backdropPath,
            @JsonProperty("vote_average") double voteAverage,
            @JsonProperty("genre_ids") List<Long> genreIds,
            @JsonProperty("release_date") String releaseDate,
            @JsonProperty("first_air_date") String firstAirDate) {}

    record TmdbMovieDetail(
            long id,
            String title,
            String overview,
            @JsonProperty("poster_path") String posterPath,
            @JsonProperty("backdrop_path") String backdropPath,
            @JsonProperty("vote_average") double voteAverage,
            List<GenreEntry> genres,
            @JsonProperty("release_date") String releaseDate,
            Integer runtime) {}

    record TmdbTvDetail(
            long id,
            String name,
            String overview,
            @JsonProperty("poster_path") String posterPath,
            @JsonProperty("backdrop_path") String backdropPath,
            @JsonProperty("vote_average") double voteAverage,
            List<GenreEntry> genres,
            @JsonProperty("first_air_date") String firstAirDate,
            @JsonProperty("number_of_seasons") Integer numberOfSeasons,
            @JsonProperty("number_of_episodes") Integer numberOfEpisodes,
            @JsonProperty("episode_run_time") List<Integer> episodeRunTime) {}

    record TmdbSeasonDetail(
            @JsonProperty("season_number") Integer seasonNumber,
            String name,
            @JsonProperty("poster_path") String posterPath,
            List<TmdbEpisode> episodes) {}

    record TmdbEpisode(
            @JsonProperty("episode_number") Integer episodeNumber,
            String name,
            String overview,
            @JsonProperty("still_path") String stillPath,
            Integer runtime) {}

    /** One row of a genre table (also the shape of a detail's genres list). */
    record GenreEntry(long id, String name) {}

    record GenreListResponse(List<GenreEntry> genres) {}

    record ImageConfigurationResponse(ImageSettings images) {}

    record ImageSettings(
            @JsonProperty("secure_base_url") String secureBaseUrl,
            @JsonProperty("poster_sizes") List<String> posterSizes,
            @JsonProperty("backdrop_sizes") List<String> backdropSizes) {}

    record ExternalIdsResponse(@JsonProperty("imdb_id") String imdbId) {}
}
