package com.tvpirate.backend.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tvpirate.backend.user.User;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    long deleteByTokenHash(String tokenHash); // logout: burn one token by hash

    void deleteAllByUser(User user); // future logout / cleanup

    void deleteAllByExpiresAtBefore(Instant cutoff); // future scheduled cleanup
}
