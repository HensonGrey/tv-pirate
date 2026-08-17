package com.tvpirate.backend.favourite.dto;

/** A heart click: the title's identity in both TMDB id spaces. */
public record AddFavouriteRequest(long tmdbId, String mediaType) {
}
