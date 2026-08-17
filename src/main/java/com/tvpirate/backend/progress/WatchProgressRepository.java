package com.tvpirate.backend.progress;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WatchProgressRepository extends JpaRepository<WatchProgressEntity, Long> {

    List<WatchProgressEntity> findAllByUserIdOrderByUpdatedAtDesc(Long userId);

    /** Movie rows only — derived IsNull keeps us off @Query null semantics. */
    Optional<WatchProgressEntity> findByUserIdAndTmdbIdAndMediaTypeAndSeasonNumberIsNull(
            Long userId, long tmdbId, String mediaType);

    Optional<WatchProgressEntity> findByUserIdAndTmdbIdAndMediaTypeAndSeasonNumberAndEpisodeNumber(
            Long userId, long tmdbId, String mediaType, Integer seasonNumber, Integer episodeNumber);

    /** Every episode row for a title (tv "start over" without coordinates). */
    void deleteByUserIdAndTmdbIdAndMediaType(Long userId, long tmdbId, String mediaType);

    void deleteByUserIdAndTmdbIdAndMediaTypeAndSeasonNumberAndEpisodeNumber(
            Long userId, long tmdbId, String mediaType, Integer seasonNumber, Integer episodeNumber);
}
