package com.tvpirate.backend.favourite;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FavouriteRepository extends JpaRepository<FavouriteEntity, Long> {

    List<FavouriteEntity> findAllByUserIdOrderByCreatedAtAsc(Long userId);

    void deleteByUserIdAndTmdbIdAndMediaType(Long userId, long tmdbId, String mediaType);

    /** uq_favourites_user_title is the conflict target, so a concurrent
     * first add can never surface as a 500 — it just collapses to one row. */
    @Modifying
    @Query(value = "INSERT INTO favourites (user_id, tmdb_id, media_type, created_at) "
            + "VALUES (:userId, :tmdbId, :mediaType, :createdAt) "
            + "ON CONFLICT (user_id, tmdb_id, media_type) DO NOTHING", nativeQuery = true)
    void upsert(@Param("userId") Long userId, @Param("tmdbId") long tmdbId,
                @Param("mediaType") String mediaType, @Param("createdAt") Instant createdAt);
}
