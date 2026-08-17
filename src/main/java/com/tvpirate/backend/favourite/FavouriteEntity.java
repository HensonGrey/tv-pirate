package com.tvpirate.backend.favourite;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One heart click: a user marked a title. The (user, tmdb, media_type)
 * triple is unique — see the constraint in migration 0004.
 * vault:favourites-deep-dive#schema */
@Entity
@Table(name = "favourites")
public class FavouriteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "tmdb_id", nullable = false)
    private long tmdbId;

    /** "movie" | "tv" — both TMDB namespaces share the numeric id space. */
    @Column(name = "media_type", nullable = false)
    private String mediaType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected FavouriteEntity() {
        // JPA needs a no-arg constructor.
    }

    public FavouriteEntity(Long userId, long tmdbId, String mediaType) {
        this.userId = userId;
        this.tmdbId = tmdbId;
        this.mediaType = mediaType;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
