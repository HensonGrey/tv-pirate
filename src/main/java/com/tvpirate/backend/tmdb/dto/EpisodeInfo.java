package com.tvpirate.backend.tmdb.dto;

/** One episode of a season, in OUR API's shape. Feeds the picker (number +
 * name) and the modal's episode description (overview). */
public record EpisodeInfo(
        Integer episodeNumber,
        String name,
        String overview,
        Integer runtimeMinutes) {
}
