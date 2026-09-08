package com.tvpirate.backend.tmdb;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.tmdb.dto.GenreInfo;
import com.tvpirate.backend.tmdb.dto.MediaItem;
import com.tvpirate.backend.tmdb.dto.PageResponse;
import com.tvpirate.backend.tmdb.dto.SeasonInfo;

/** Our TMDB proxy — the frontend calls these, the backend forwards with the
 * key from .env. /genres and /{type}/{id} don't clash: Spring prefers the
 * literal over the path variable. */
@RestController
@RequestMapping("/api/tmdb")
public class TmdbController {

    private static final int MAX_PAGE = 500; // TMDB caps results at 500 pages

    /** Matches the backing Caffeine caches' own TTLs (TmdbConfig) — caching
     * the response in the browser longer than the server-side cache behind
     * it would just mean stale data that a reload can't fix. Spring
     * Security's default header writer otherwise adds no-store to every
     * authenticated response, so today the browser re-hits us on every browse. */
    private static final Duration LIST_TTL = Duration.ofMinutes(10);
    private static final Duration DETAIL_TTL = Duration.ofDays(1);

    private final TmdbService tmdbService;

    public TmdbController(TmdbService tmdbService) {
        this.tmdbService = tmdbService;
    }

    /** Mixed movies + shows trending right now. window = day|week. */
    @GetMapping("/trending")
    public ResponseEntity<PageResponse<MediaItem>> trending(@RequestParam(defaultValue = "day") String window,
                                            @RequestParam(defaultValue = "1") int page) {
        if (!window.equals("day") && !window.equals("week")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "window must be day or week");
        }
        return cached(tmdbService.trending(window, checkPage(page)), LIST_TTL);
    }

    /** Popularity-sorted movies or tv, narrowed by genre names (OR semantics). */
    @GetMapping("/discover")
    public ResponseEntity<PageResponse<MediaItem>> discover(@RequestParam String type,
                                            @RequestParam(required = false) String genres,
                                            @RequestParam(defaultValue = "1") int page) {
        checkType(type);
        List<String> genreNames = genres == null
                ? List.of()
                : Arrays.stream(genres.split(","))
                        .map(String::trim)
                        .filter(name -> !name.isEmpty())
                        .toList();
        return cached(tmdbService.discover(type, genreNames, checkPage(page)), LIST_TTL);
    }

    /** Title search across movies + shows (people never enter the results). */
    @GetMapping("/search")
    public ResponseEntity<PageResponse<MediaItem>> search(@RequestParam String query,
                                          @RequestParam(defaultValue = "1") int page) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }
        return cached(tmdbService.search(query, checkPage(page)), LIST_TTL);
    }

    /** Full detail for one title: runtime for movies, seasons/episodes for tv. */
    @GetMapping("/{type}/{id}")
    public ResponseEntity<MediaItem> detail(@PathVariable String type, @PathVariable long id) {
        checkType(type);
        return cached(tmdbService.detail(type, id), DETAIL_TTL);
    }

    /** One season of a show — identity + poster + the episode list feeding
     * the picker and the episode description. */
    @GetMapping("/tv/{id}/season/{season}")
    public ResponseEntity<SeasonInfo> seasonEpisodes(@PathVariable long id, @PathVariable int season) {
        if (season < 1 || season > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "season must be between 1 and 100");
        }
        return cached(tmdbService.seasonEpisodes(id, season), DETAIL_TTL);
    }

    /** The selectable genre list, movie + tv tables merged (see GenreInfo). */
    @GetMapping("/genres")
    public ResponseEntity<List<GenreInfo>> genres() {
        return cached(tmdbService.genres(), DETAIL_TTL);
    }

    private static <T> ResponseEntity<T> cached(T body, Duration maxAge) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(maxAge).cachePrivate()).body(body);
    }

    private static int checkPage(int page) {
        if (page < 1 || page > MAX_PAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be between 1 and " + MAX_PAGE);
        }
        return page;
    }

    private static void checkType(String type) {
        if (!type.equals("movie") && !type.equals("tv")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "type must be movie or tv");
        }
    }
}
