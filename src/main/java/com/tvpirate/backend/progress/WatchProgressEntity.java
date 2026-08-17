package com.tvpirate.backend.progress;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One row of watch history: where a user left off in a movie (season/episode
 * NULL) or a specific episode. Upserted on every progress heartbeat.
 * vault:watch-progress-deep-dive#schema */
@Entity
@Table(name = "watch_progress")
public class WatchProgressEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Plain id, not a ManyToOne — nothing here needs the UserEntity object. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tmdb_id", nullable = false)
    private long tmdbId;

    /** "movie" | "tv" — both TMDB namespaces share the numeric id space. */
    @Column(name = "media_type", nullable = false)
    private String mediaType;

    @Column(name = "season_number")
    private Integer seasonNumber;

    @Column(name = "episode_number")
    private Integer episodeNumber;

    @Column(name = "progress_seconds", nullable = false)
    private int progressSeconds;

    /** Length of the title/episode — lets the home bars show a % without a
     * TMDB round-trip. */
    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected WatchProgressEntity() {
        // JPA needs a no-arg constructor.
    }

    public WatchProgressEntity(Long userId, long tmdbId, String mediaType) {
        this(userId, tmdbId, mediaType, null, null);
    }

    public WatchProgressEntity(Long userId, long tmdbId, String mediaType,
                               Integer seasonNumber, Integer episodeNumber) {
        this.userId = userId;
        this.tmdbId = tmdbId;
        this.mediaType = mediaType;
        this.seasonNumber = seasonNumber;
        this.episodeNumber = episodeNumber;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public long getTmdbId() {
        return tmdbId;
    }

    public String getMediaType() {
        return mediaType;
    }

    public Integer getSeasonNumber() {
        return seasonNumber;
    }

    public Integer getEpisodeNumber() {
        return episodeNumber;
    }

    public int getProgressSeconds() {
        return progressSeconds;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setProgressSeconds(int progressSeconds) {
        this.progressSeconds = progressSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
