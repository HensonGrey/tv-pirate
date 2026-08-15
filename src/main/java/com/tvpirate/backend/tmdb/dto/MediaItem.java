package com.tvpirate.backend.tmdb.dto;

import java.util.List;

/** One movie or show, in OUR API's shape. List responses leave the detail
 * fields null (cheap to fetch, cheap to ship); detail fills them in.
 * Everything nullable on purpose — the frontend hides missing data instead
 * of crashing. */
public record MediaItem(
        Long id,
        String mediaType, // "movie" | "tv"
        String title,
        String overview,
        String posterUrl, // full URL, built by the backend (image CDN + size)
        String backdropUrl,
        Double rating, // TMDB vote_average, 0–10, rounded to 1 decimal
        List<String> genres, // names, translated from genre_ids by the backend
        Integer year, // from release_date / first_air_date
        Integer runtimeMinutes, // movies only
        Integer seasons, // tv only
        Integer episodes) { // tv only
}
