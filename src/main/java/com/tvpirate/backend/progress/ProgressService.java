package com.tvpirate.backend.progress;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tvpirate.backend.progress.dto.ProgressRowDto;
import com.tvpirate.backend.progress.dto.SaveProgressRequest;

/** Watch-history store: heartbeat upserts, list for the home bars, and the
 * deletes behind "Start over". vault:watch-progress-deep-dive#schema */
@Service
public class ProgressService {

    private final WatchProgressRepository repository;

    public ProgressService(WatchProgressRepository repository) {
        this.repository = repository;
    }

    /** Last-write-wins upsert: a rewatch starts near 0 and must be able to
     * overwrite a mid-episode value, so no "only grow" guard. */
    @Transactional
    public void upsert(Long userId, SaveProgressRequest request) {
        // Sub-5-second plays are noise (accidental opens) — never create a row.
        if (request.progressSeconds() < 5) {
            return;
        }
        // Movie coordinates are normalized away regardless of what the client sent.
        Integer season = "tv".equals(request.mediaType()) ? request.season() : null;
        Integer episode = "tv".equals(request.mediaType()) ? request.episode() : null;
        WatchProgressEntity row = "tv".equals(request.mediaType())
                ? repository.findByUserIdAndTmdbIdAndMediaTypeAndSeasonNumberAndEpisodeNumber(
                        userId, request.tmdbId(), request.mediaType(), season, episode)
                        .orElseGet(() -> new WatchProgressEntity(
                                userId, request.tmdbId(), request.mediaType(), season, episode))
                : repository.findByUserIdAndTmdbIdAndMediaTypeAndSeasonNumberIsNull(
                        userId, request.tmdbId(), request.mediaType())
                        .orElseGet(() -> new WatchProgressEntity(
                                userId, request.tmdbId(), request.mediaType()));
        row.setProgressSeconds(request.progressSeconds());
        row.setDurationSeconds(request.durationSeconds());
        row.setUpdatedAt(Instant.now());
        repository.save(row);
    }

    @Transactional(readOnly = true)
    public List<ProgressRowDto> list(Long userId) {
        return repository.findAllByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(row -> new ProgressRowDto(row.getTmdbId(), row.getMediaType(),
                        row.getSeasonNumber(), row.getEpisodeNumber(),
                        row.getProgressSeconds(), row.getDurationSeconds(), row.getUpdatedAt()))
                .toList();
    }

    /** With season+episode: one episode's row. Without: every row for the
     * title ("Start over" on a show clears all episodes). */
    @Transactional
    public void delete(Long userId, String mediaType, long tmdbId, Integer season, Integer episode) {
        if ("tv".equals(mediaType) && season != null && episode != null) {
            repository.deleteByUserIdAndTmdbIdAndMediaTypeAndSeasonNumberAndEpisodeNumber(
                    userId, tmdbId, mediaType, season, episode);
        } else {
            repository.deleteByUserIdAndTmdbIdAndMediaType(userId, tmdbId, mediaType);
        }
    }
}
