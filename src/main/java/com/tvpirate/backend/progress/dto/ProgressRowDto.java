package com.tvpirate.backend.progress.dto;

import java.time.Instant;

/** One saved playback position as the frontend sees it. */
public record ProgressRowDto(long tmdbId, String mediaType, Integer season, Integer episode,
                             int progressSeconds, Integer durationSeconds, Instant updatedAt) {
}
