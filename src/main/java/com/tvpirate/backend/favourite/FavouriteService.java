package com.tvpirate.backend.favourite;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tvpirate.backend.favourite.dto.FavouriteRowDto;

/** The favourites store. Add and remove are idempotent on purpose — the
 * frontend fires them optimistically and may retry. */
@Service
public class FavouriteService {

    private final FavouriteRepository repository;

    public FavouriteService(FavouriteRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<FavouriteRowDto> list(Long userId) {
        return repository.findAllByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(row -> new FavouriteRowDto(row.getTmdbId(), row.getMediaType()))
                .toList();
    }

    @Transactional
    public void add(Long userId, long tmdbId, String mediaType) {
        if (!repository.existsByUserIdAndTmdbIdAndMediaType(userId, tmdbId, mediaType)) {
            repository.save(new FavouriteEntity(userId, tmdbId, mediaType));
        }
        // The unique constraint is the race backstop: a lost insert races to
        // a constraint violation, which reads the same as "already saved".
    }

    @Transactional
    public void remove(Long userId, long tmdbId, String mediaType) {
        repository.deleteByUserIdAndTmdbIdAndMediaType(userId, tmdbId, mediaType);
    }
}
