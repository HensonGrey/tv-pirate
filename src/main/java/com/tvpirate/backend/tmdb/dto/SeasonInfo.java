package com.tvpirate.backend.tmdb.dto;

import java.util.List;

/** One season of a show, in OUR API's shape: identity + poster for the
 * picker's visuals, and the episode list the picker selects from. */
public record SeasonInfo(
        Integer seasonNumber,
        String name,
        String posterUrl, // full URL, built by the backend (image CDN + size)
        List<EpisodeInfo> episodes) {
}
