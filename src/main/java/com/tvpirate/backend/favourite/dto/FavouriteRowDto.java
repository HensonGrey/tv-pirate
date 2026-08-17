package com.tvpirate.backend.favourite.dto;

/** One saved favourite as the frontend sees it. */
public record FavouriteRowDto(long tmdbId, String mediaType) {
}
