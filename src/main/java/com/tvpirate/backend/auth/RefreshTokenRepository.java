package com.tvpirate.backend.auth;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.tvpirate.backend.user.UserEntity;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    long deleteByTokenHash(String tokenHash); // logout: burn one token by hash

    void deleteAllByUser(UserEntity user); // future logout / cleanup

    void deleteAllByExpiresAtBefore(Instant cutoff); // future scheduled cleanup
}
