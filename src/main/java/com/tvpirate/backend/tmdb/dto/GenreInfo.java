package com.tvpirate.backend.tmdb.dto;

/** One selectable genre. TMDB keeps separate movie/tv genre tables; the
 * backend merges them into one row per name, and the ids stay here so a
 * name can translate back per table (null when that table lacks the genre). */
public record GenreInfo(String name, Integer movieId, Integer tvId) {}
