package com.tvpirate.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.tvpirate.backend.auth.AuthService;
import com.tvpirate.backend.auth.RefreshTokenEntity;
import com.tvpirate.backend.auth.RefreshTokenRepository;
import com.tvpirate.backend.auth.dto.AuthResponse;
import com.tvpirate.backend.user.AuthProvider;
import com.tvpirate.backend.user.UserEntity;
import com.tvpirate.backend.user.UserRepository;

/**
 * The refresh-token table as the auth flow actually uses it: rows are found
 * by SHA-256 hash, burned on rotation, and swept by expiry. Nothing here
 * needs a committed write, so the test transaction rolls everything back.
 * vault:auth-deep-dive#tokens
 */
@SpringBootTest
@ActiveProfiles("it")
@Transactional
class RefreshTokenRepositoryIT {

    private static final String HASH = "a".repeat(64); // shaped like a hex SHA-256 digest

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    private UserEntity owner;
    private UserEntity bystander;

    @BeforeEach
    void truncateAndSeedUsers() {
        jdbc.execute("TRUNCATE users, refresh_tokens, watch_progress, favourites RESTART IDENTITY CASCADE");
        owner = userRepository.save(new UserEntity("it-token-a", null, AuthProvider.GUEST));
        bystander = userRepository.save(new UserEntity("it-token-b", null, AuthProvider.GUEST));
    }

    @Test
    void lookupByHashFindsTheStoredRow() {
        repository.save(new RefreshTokenEntity(HASH, owner, in(30)));

        assertThat(repository.findByTokenHash(HASH))
                .get()
                .satisfies(token -> assertThat(token.getUser().getId()).isEqualTo(owner.getId()));
        assertThat(repository.findByTokenHash("b".repeat(64))).isEmpty();
    }

    @Test
    void burningATokenByHashRemovesItAndIsIdempotent() {
        repository.save(new RefreshTokenEntity(HASH, owner, in(30)));

        assertThat(repository.deleteByTokenHash(HASH)).isOne();
        assertThat(repository.findByTokenHash(HASH)).isEmpty();
        // Logout must not fail on a token that is already gone.
        assertThat(repository.deleteByTokenHash(HASH)).isZero();
    }

    @Test
    void theExpirySweepTakesOnlyTokensStrictlyBeforeTheCutoff() {
        Instant cutoff = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        repository.save(new RefreshTokenEntity("expired", owner, cutoff.minusSeconds(1)));
        repository.save(new RefreshTokenEntity("exactly-at-cutoff", owner, cutoff));
        repository.save(new RefreshTokenEntity("still-valid", owner, cutoff.plusSeconds(1)));

        repository.deleteAllByExpiresAtBefore(cutoff);

        assertThat(repository.findAll())
                .extracting(RefreshTokenEntity::getTokenHash)
                .containsExactlyInAnyOrder("exactly-at-cutoff", "still-valid");
    }

    @Test
    void deletingOneUsersTokensLeavesTheOthersAlone() {
        repository.save(new RefreshTokenEntity(HASH, owner, in(30)));
        repository.save(new RefreshTokenEntity("b".repeat(64), bystander, in(30)));

        repository.deleteAllByUser(owner);

        assertThat(repository.findAll())
                .singleElement()
                .satisfies(token -> assertThat(token.getUser().getId()).isEqualTo(bystander.getId()));
    }

    @Test
    void aHashCanOnlyBeStoredOnce() {
        repository.save(new RefreshTokenEntity(HASH, owner, in(30)));

        // Nothing after this line: the failed insert dooms the test transaction.
        assertThatThrownBy(() -> repository.saveAndFlush(new RefreshTokenEntity(HASH, bystander, in(30))))
                .hasMessageContaining("refresh_tokens_token_hash_key");
    }

    @Test
    void refreshRotatesTheStoredRowRatherThanAddingOne() {
        AuthResponse issued = authService.loginAsGuest();

        AuthResponse rotated = authService.refresh(issued.refreshToken());

        assertThat(rotated.refreshToken()).isNotEqualTo(issued.refreshToken());
        assertThat(repository.findAll()).hasSize(1); // the burned row is gone, not orphaned
        assertThatThrownBy(() -> authService.refresh(issued.refreshToken()))
                .hasMessageContaining("Invalid refresh token");
    }

    private static Instant in(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
    }
}
