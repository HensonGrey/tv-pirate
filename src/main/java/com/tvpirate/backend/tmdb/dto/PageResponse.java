package com.tvpirate.backend.tmdb.dto;

import java.util.List;

/** Every list endpoint returns this: the current page plus the pagination
 * metadata the footer and page window need. Mirrors TMDB's own page shape. */
public record PageResponse<T>(int page, List<T> results, int totalPages, int totalResults) {}
