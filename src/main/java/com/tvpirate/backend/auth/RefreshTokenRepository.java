package com.tvpirate.backend.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tvpirate.backend.user.UserEntity;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    long deleteByTokenHash(String tokenHash); // logout: burn one token by hash

    // Redundant with ON DELETE CASCADE for a user delete; kept for an explicit
    // "log out everywhere" that revokes tokens without touching the account.
    void deleteAllByUser(UserEntity user);

    /** Returns how many were pruned, so the nightly sweep can log it. */
    long deleteAllByExpiresAtBefore(Instant cutoff);
}
