package com.tvpirate.backend.favourite;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FavouriteRepository extends JpaRepository<FavouriteEntity, Long> {

    List<FavouriteEntity> findAllByUserIdOrderByCreatedAtAsc(Long userId);

    boolean existsByUserIdAndTmdbIdAndMediaType(Long userId, long tmdbId, String mediaType);

    void deleteByUserIdAndTmdbIdAndMediaType(Long userId, long tmdbId, String mediaType);
}
