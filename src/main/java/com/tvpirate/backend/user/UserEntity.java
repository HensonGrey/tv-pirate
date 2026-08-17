package com.tvpirate.backend.user;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user account. No local username/password accounts exist: a user is a
 * guest (one-click) or comes from a provider — so no password column, and
 * email is nullable for guests. vault:auth-deep-dive#user-model */
@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(unique = true)
    private String email;

    /** Provider-supplied avatar URL (Google's picture claim); null for
     * guests — the frontend falls back to the default avatar. 512 chars:
     * provider URLs get long. */
    @Column(length = 512)
    private String profilePictureUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Activity clock owned by the DB trigger (migration 0005) — the app
     * never writes it, so updatable=false keeps a stale in-memory copy from
     * overwriting a fresher trigger value. vault:guest-cleanup-deep-dive#trigger */
    @Column(name = "last_activity_at", nullable = false, updatable = false)
    private Instant lastActivityAt = Instant.now();

    protected UserEntity() {
        // JPA needs a no-arg constructor to create instances when loading rows.
    }

    public UserEntity(String username, String email, AuthProvider provider) {
        this(username, email, provider, null);
    }

    public UserEntity(String username, String email, AuthProvider provider, String profilePictureUrl) {
        this.username = username;
        this.email = email;
        this.provider = provider;
        this.profilePictureUrl = profilePictureUrl;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public AuthProvider getProvider() {
        return provider;
    }

    public String getProfilePictureUrl() {
        return profilePictureUrl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }
}
