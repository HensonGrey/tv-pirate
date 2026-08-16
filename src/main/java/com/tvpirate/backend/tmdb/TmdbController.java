package com.tvpirate.backend.tmdb;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpStatus;
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

    private final TmdbService tmdbService;

    public TmdbController(TmdbService tmdbService) {
        this.tmdbService = tmdbService;
    }

    /** Mixed movies + shows trending right now. window = day|week. */
    @GetMapping("/trending")
    public PageResponse<MediaItem> trending(@RequestParam(defaultValue = "day") String window,
                                            @RequestParam(defaultValue = "1") int page) {
        if (!window.equals("day") && !window.equals("week")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "window must be day or week");
        }
        return tmdbService.trending(window, checkPage(page));
    }

    /** Popularity-sorted movies or tv, narrowed by genre names (OR semantics). */
    @GetMapping("/discover")
    public PageResponse<MediaItem> discover(@RequestParam String type,
                                            @RequestParam(required = false) String genres,
                                            @RequestParam(defaultValue = "1") int page) {
        checkType(type);
        List<String> genreNames = genres == null
                ? List.of()
                : Arrays.stream(genres.split(","))
                        .map(String::trim)
                        .filter(name -> !name.isEmpty())
                        .toList();
        return tmdbService.discover(type, genreNames, checkPage(page));
    }

    /** Title search across movies + shows (people never enter the results). */
    @GetMapping("/search")
    public PageResponse<MediaItem> search(@RequestParam String query,
                                          @RequestParam(defaultValue = "1") int page) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query is required");
        }
        return tmdbService.search(query, checkPage(page));
    }

    /** Full detail for one title: runtime for movies, seasons/episodes for tv. */
    @GetMapping("/{type}/{id}")
    public MediaItem detail(@PathVariable String type, @PathVariable long id) {
        checkType(type);
        return tmdbService.detail(type, id);
    }

    /** One season of a show — identity + poster + the episode list feeding
     * the picker and the episode description. */
    @GetMapping("/tv/{id}/season/{season}")
    public SeasonInfo seasonEpisodes(@PathVariable long id, @PathVariable int season) {
        if (season < 1 || season > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "season must be between 1 and 100");
        }
        return tmdbService.seasonEpisodes(id, season);
    }

    /** The selectable genre list, movie + tv tables merged (see GenreInfo). */
    @GetMapping("/genres")
    public List<GenreInfo> genres() {
        return tmdbService.genres();
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
