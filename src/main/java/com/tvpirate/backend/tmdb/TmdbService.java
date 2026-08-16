package com.tvpirate.backend.tmdb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.tmdb.dto.EpisodeInfo;
import com.tvpirate.backend.tmdb.dto.GenreInfo;
import com.tvpirate.backend.tmdb.dto.MediaItem;
import com.tvpirate.backend.tmdb.dto.PageResponse;
import com.tvpirate.backend.tmdb.dto.SeasonInfo;

/**
 * Business logic between TmdbClient (raw TMDB) and the controller (our
 * API): maps entries to MediaItems, fills TMDB's gaps (genre names, image
 * URLs, years), caches results, and turns TMDB failures into clean HTTP
 * errors instead of leaking their status codes.
 */
@Service
public class TmdbService {

    private static final Logger log = LoggerFactory.getLogger(TmdbService.class);

    /** Poster/backdrop sizes we ask the image CDN for (must exist in TMDB's config). */
    private static final String POSTER_SIZE = "w500";
    private static final String BACKDROP_SIZE = "w1280";

    private final TmdbClient client;

    public TmdbService(TmdbClient client) {
        this.client = client;
    }

    // --- Public API (what the controller exposes, each cached) ---

    /** Trending re-ranked by rating: best-rated first, no-votes-yet (null)
     * at the bottom. Sorted within the fetched page — a global ranking
     * would fetch all 500 pages first. vault:tmdb-deep-dive#trending-sort */
    @Cacheable(cacheNames = "trending", key = "#window + ':' + #page")
    public PageResponse<MediaItem> trending(String window, int page) {
        return guarded(() -> {
            PageResponse<MediaItem> mapped = mapPage(client.trendingAll(window, page), null);
            List<MediaItem> sorted = new ArrayList<>(mapped.results());
            sorted.sort(Comparator.comparing(MediaItem::rating,
                    Comparator.nullsLast(Comparator.reverseOrder())));
            return new PageResponse<>(mapped.page(), sorted, mapped.totalPages(), mapped.totalResults());
        });
    }

    /** Popularity-sorted movies or tv, narrowed by genre names. Unknown
     * names are dropped; if none resolve, the answer is empty. */
    @Cacheable(cacheNames = "discover", key = "T(com.tvpirate.backend.tmdb.TmdbService).genreKey(#type, #genreNames, #page)")
    public PageResponse<MediaItem> discover(String type, List<String> genreNames, int page) {
        return guarded(() -> {
            Map<String, Integer> table = indexByName(client.genreTable(type));
            String genreIdsCsv = null;
            if (genreNames != null && !genreNames.isEmpty()) {
                List<String> resolved = genreNames.stream()
                        .distinct()
                        .map(name -> table.get(name.trim().toLowerCase(Locale.ROOT)))
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .toList();
                if (resolved.isEmpty()) {
                    return new PageResponse<>(page, List.of(), 0, 0);
                }
                genreIdsCsv = String.join(",", resolved);
            }
            return mapPage(client.discover(type, genreIdsCsv, page), type);
        });
    }

    /** Title search: one page from each type index, interleaved so each
     * list keeps its relevance order. vault:tmdb-deep-dive#search */
    @Cacheable(cacheNames = "search", key = "#query + ':' + #page")
    public PageResponse<MediaItem> search(String query, int page) {
        return guarded(() -> {
            String trimmed = query.trim();
            var movies = client.searchMovies(trimmed, page);
            var shows = client.searchShows(trimmed, page);
            TmdbClient.ImageSettings images = client.imageConfig();
            GenreLookup lookup = genreLookup();
            // Per-type results carry no media_type — the request URL's type wins.
            List<MediaItem> movieItems = movies.results().stream()
                    .map(entry -> toItem(entry, "movie", lookup, images))
                    .toList();
            List<MediaItem> showItems = shows.results().stream()
                    .map(entry -> toItem(entry, "tv", lookup, images))
                    .toList();
            List<MediaItem> merged = new ArrayList<>(movieItems.size() + showItems.size());
            for (int i = 0; i < Math.max(movieItems.size(), showItems.size()); i++) {
                if (i < movieItems.size()) merged.add(movieItems.get(i));
                if (i < showItems.size()) merged.add(showItems.get(i));
            }
            return new PageResponse<>(page, merged,
                    Math.max(movies.totalPages(), shows.totalPages()),
                    movies.totalResults() + shows.totalResults());
        });
    }

    /** Full detail for one title: runtime for movies, seasons/episodes for tv. */
    @Cacheable(cacheNames = "tmdb-detail", key = "#type + ':' + #id")
    public MediaItem detail(String type, long id) {
        return guarded(() -> {
            TmdbClient.ImageSettings images = client.imageConfig();
            if ("tv".equals(type)) {
                var detail = client.tvDetail(id);
                Integer runtime = detail.episodeRunTime() == null || detail.episodeRunTime().isEmpty()
                        ? null
                        : detail.episodeRunTime().getFirst();
                return new MediaItem(detail.id(), "tv", detail.name(), detail.overview(),
                        imageUrl(images, POSTER_SIZE, detail.posterPath()),
                        imageUrl(images, BACKDROP_SIZE, detail.backdropPath()),
                        rating(detail.voteAverage()), namesOf(detail.genres()),
                        year(detail.firstAirDate()), runtime,
                        detail.numberOfSeasons(), detail.numberOfEpisodes());
            }
            var detail = client.movieDetail(id);
            return new MediaItem(detail.id(), "movie", detail.title(), detail.overview(),
                    imageUrl(images, POSTER_SIZE, detail.posterPath()),
                    imageUrl(images, BACKDROP_SIZE, detail.backdropPath()),
                    rating(detail.voteAverage()), namesOf(detail.genres()),
                    year(detail.releaseDate()), detail.runtime(), null, null);
        });
    }

    /** One season: identity + poster (the picker's visual) and the episode
     * list with numbers, names and overviews. Cached with the other detail
     * data: episode tables effectively never change. */
    @Cacheable(cacheNames = "tmdb-detail", key = "'season:' + #tvId + ':' + #season")
    public SeasonInfo seasonEpisodes(long tvId, int season) {
        return guarded(() -> {
            TmdbClient.TmdbSeasonDetail seasonDetail = client.tvSeason(tvId, season);
            if (seasonDetail == null) {
                return new SeasonInfo(null, null, null, List.of());
            }
            TmdbClient.ImageSettings images = client.imageConfig();
            List<EpisodeInfo> episodes = seasonDetail.episodes() == null
                    ? List.of()
                    : seasonDetail.episodes().stream()
                            .map(ep -> new EpisodeInfo(ep.episodeNumber(), ep.name(), ep.overview(), ep.runtime()))
                            .toList();
            return new SeasonInfo(seasonDetail.seasonNumber(), seasonDetail.name(),
                    imageUrl(images, POSTER_SIZE, seasonDetail.posterPath()), episodes);
        });
    }

    /** The selectable genre list: movie + tv tables merged into one row per
     * name, alphabetical. The underlying fetches are cached upstream. */
    public List<GenreInfo> genres() {
        return guarded(() -> {
            // Merged keyed by display name; display names come straight from
            // TMDB's tables (indexByName lowercases keys, so it can't be
            // reused here without losing the proper casing).
            Map<String, GenreInfo> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (TmdbClient.GenreEntry entry : client.genreTable("movie")) {
                merged.put(entry.name(), new GenreInfo(entry.name(), (int) entry.id(), null));
            }
            for (TmdbClient.GenreEntry entry : client.genreTable("tv")) {
                merged.merge(entry.name(),
                        new GenreInfo(entry.name(), null, (int) entry.id()),
                        (existing, incoming) -> new GenreInfo(existing.name(), existing.movieId(), incoming.tvId()));
            }
            return new ArrayList<>(merged.values());
        });
    }

    /** Cache-key helper for discover: order-proof, deduped genre list. */
    public static String genreKey(String type, List<String> genreNames, int page) {
        String names = genreNames == null
                ? ""
                : genreNames.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(name -> !name.isEmpty())
                        .distinct()
                        .sorted()
                        .toList()
                        .toString();
        return type + ':' + names + ':' + page;
    }

    // --- Mapping helpers ---

    /** Maps a raw TMDB page into our page shape, resolving genres + image URLs. */
    private PageResponse<MediaItem> mapPage(TmdbClient.TmdbPage<TmdbClient.TmdbEntry> raw, String fallbackType) {
        TmdbClient.ImageSettings images = client.imageConfig();
        GenreLookup lookup = genreLookup();
        List<MediaItem> items = raw.results().stream()
                .map(entry -> toItem(entry, fallbackType, lookup, images))
                .toList();
        return new PageResponse<>(raw.page(), items, raw.totalPages(), raw.totalResults());
    }

    /** List entry → MediaItem. Mixed endpoints carry media_type on the
     * entry; discover/search fall back to the request URL's type. */
    private MediaItem toItem(TmdbClient.TmdbEntry entry, String fallbackType, GenreLookup lookup, TmdbClient.ImageSettings images) {
        String type = entry.mediaType() != null ? entry.mediaType() : fallbackType;
        boolean isTv = "tv".equals(type);
        String title = isTv ? entry.name() : entry.title();
        if (title == null) title = entry.name() != null ? entry.name() : entry.title();
        return new MediaItem(entry.id(), type, title, entry.overview(),
                imageUrl(images, POSTER_SIZE, entry.posterPath()),
                imageUrl(images, BACKDROP_SIZE, entry.backdropPath()),
                rating(entry.voteAverage()),
                genreNames(entry.genreIds(), type, lookup),
                year(isTv ? entry.firstAirDate() : entry.releaseDate()),
                null, null, null);
    }

    /** id→name tables for both media types, fetched once per request (cached upstream). */
    private GenreLookup genreLookup() {
        Map<Long, String> movie = client.genreTable("movie").stream()
                .collect(Collectors.toMap(TmdbClient.GenreEntry::id, TmdbClient.GenreEntry::name));
        Map<Long, String> tv = client.genreTable("tv").stream()
                .collect(Collectors.toMap(TmdbClient.GenreEntry::id, TmdbClient.GenreEntry::name));
        return new GenreLookup(movie, tv);
    }

    private static List<String> genreNames(List<Long> ids, String type, GenreLookup lookup) {
        if (ids == null || ids.isEmpty()) return List.of();
        return ids.stream()
                .map(id -> lookup.nameFor(type, id))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static List<String> namesOf(List<TmdbClient.GenreEntry> genres) {
        return genres == null ? List.of() : genres.stream().map(TmdbClient.GenreEntry::name).toList();
    }

    private static Map<String, Integer> indexByName(List<TmdbClient.GenreEntry> entries) {
        Map<String, Integer> map = new HashMap<>();
        for (TmdbClient.GenreEntry entry : entries) {
            map.put(entry.name().toLowerCase(Locale.ROOT), (int) entry.id()); // genre ids are tiny (< 10k)
        }
        return map;
    }

    /** TMDB's vote_average is 0–10 with one decimal of noise; 0 means "no votes" → null. */
    private static Double rating(double voteAverage) {
        if (voteAverage <= 0) return null;
        return Math.round(voteAverage * 10) / 10.0;
    }

    /** "2024-03-01" → 2024, or null when the date is missing/broken. */
    private static Integer year(String date) {
        if (date == null || date.length() < 4) return null;
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Poster path + CDN settings → full URL, or null when either side is missing. */
    private static String imageUrl(TmdbClient.ImageSettings images, String size, String path) {
        if (images == null || images.secureBaseUrl() == null || path == null || path.isBlank()) return null;
        return images.secureBaseUrl() + size + path;
    }

    /** TMDB's errors must not leak to the frontend: 404 means "no such
     * title", anything else means TMDB is the problem. Failures aren't
     * cached (@Cacheable only stores successes), so the next request retries. */
    private <T> T guarded(Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            // The upstream answered with an error status: log the details —
            // the client only gets the generic message (no TMDB internals leak).
            log.warn("TMDB answered with status {}", e.getStatusCode().value(), e);
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Title not found on TMDB");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TMDB is not answering right now — try again shortly");
        } catch (RestClientException e) {
            log.warn("TMDB call failed", e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "TMDB is unreachable — try again shortly");
        }
    }

    /** id→name for both genre tables, so one entry's ids resolve against its own type. */
    private record GenreLookup(Map<Long, String> movieById, Map<Long, String> tvById) {
        String nameFor(String mediaType, Long genreId) {
            Map<Long, String> table = "tv".equals(mediaType) ? tvById : movieById;
            return table.get(genreId);
        }
    }
}
