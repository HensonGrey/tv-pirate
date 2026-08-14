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

/**
 * A user account — one row in the "users" table.
 *
 * <p>There are no local username/password accounts: a user is either a guest
 * (one-click account, no credentials) or comes from a login provider (Google,
 * etc.). That is why there is no password column at all — {@link AuthProvider}
 * records which method the account belongs to, and email stays nullable
 * because guest accounts don't have one.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(unique = true)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected User() {
        // JPA needs a no-arg constructor to create instances when loading rows.
    }

    public User(String username, String email, AuthProvider provider) {
        this.username = username;
        this.email = email;
        this.provider = provider;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}
