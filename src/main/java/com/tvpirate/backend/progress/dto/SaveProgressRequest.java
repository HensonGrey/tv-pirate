package com.tvpirate.backend.progress.dto;

/** A heartbeat from the player: current position plus the coordinates of
 * what is playing. Movies leave season/episode null. */
public record SaveProgressRequest(long tmdbId, String mediaType, Integer season, Integer episode,
                                  int progressSeconds, Integer durationSeconds) {
}
