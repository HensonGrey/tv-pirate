package com.tvpirate.backend.progress;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WatchProgressRepository extends JpaRepository<WatchProgressEntity, Long> {

    List<WatchProgressEntity> findAllByUserIdOrderByUpdatedAtDesc(Long userId);

    /** Every episode row for a title (tv "start over" without coordinates). */
    void deleteByUserIdAndTmdbIdAndMediaType(Long userId, long tmdbId, String mediaType);

    void deleteByUserIdAndTmdbIdAndMediaTypeAndSeasonNumberAndEpisodeNumber(
            Long userId, long tmdbId, String mediaType, Integer seasonNumber, Integer episodeNumber);

    /** Targets uq_watch_progress_movie. The predicate has to be spelled out —
     * naming the columns alone matches neither partial index and silently
     * inserts a duplicate row instead of upserting. */
    @Modifying
    @Query(value = """
            INSERT INTO watch_progress (user_id, tmdb_id, media_type, season_number, episode_number, progress_seconds, duration_seconds, updated_at)
            VALUES (:userId, :tmdbId, :mediaType, NULL, NULL, :progressSeconds, :durationSeconds, :updatedAt)
            ON CONFLICT (user_id, tmdb_id) WHERE season_number IS NULL
            DO UPDATE SET progress_seconds = EXCLUDED.progress_seconds,
                           duration_seconds = EXCLUDED.duration_seconds,
                           updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsertMovie(@Param("userId") Long userId, @Param("tmdbId") long tmdbId, @Param("mediaType") String mediaType,
                      @Param("progressSeconds") int progressSeconds, @Param("durationSeconds") Integer durationSeconds,
                      @Param("updatedAt") Instant updatedAt);

    /** Targets uq_watch_progress_tv — same predicate rule as the movie upsert. */
    @Modifying
    @Query(value = """
            INSERT INTO watch_progress (user_id, tmdb_id, media_type, season_number, episode_number, progress_seconds, duration_seconds, updated_at)
            VALUES (:userId, :tmdbId, :mediaType, :season, :episode, :progressSeconds, :durationSeconds, :updatedAt)
            ON CONFLICT (user_id, tmdb_id, season_number, episode_number) WHERE season_number IS NOT NULL
            DO UPDATE SET progress_seconds = EXCLUDED.progress_seconds,
                           duration_seconds = EXCLUDED.duration_seconds,
                           updated_at = EXCLUDED.updated_at
            """, nativeQuery = true)
    void upsertEpisode(@Param("userId") Long userId, @Param("tmdbId") long tmdbId, @Param("mediaType") String mediaType,
                        @Param("season") Integer season, @Param("episode") Integer episode,
                        @Param("progressSeconds") int progressSeconds, @Param("durationSeconds") Integer durationSeconds,
                        @Param("updatedAt") Instant updatedAt);
}
