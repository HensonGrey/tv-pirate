package com.tvpirate.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.tvpirate.backend.user.AuthProvider;
import com.tvpirate.backend.user.UserEntity;
import com.tvpirate.backend.user.UserRepository;

/**
 * The daily sweep's selection query: provider plus the trigger-maintained
 * activity clock. Getting the predicate wrong deletes live accounts, so both
 * sides of the window and both providers are pinned here against the real
 * column type. vault:guest-cleanup-deep-dive#cron
 */
@SpringBootTest
@ActiveProfiles("it")
class GuestSweepQueryIT {

    /** Stands in for the configured retention window (7 days by default). */
    private static final Instant CUTOFF =
            Instant.now().minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // No @Transactional: these tests delete users, so the cascade has to commit.
    @BeforeEach
    void startFromAnEmptyDatabase() {
        truncate();
    }

    @AfterEach
    void cleanUp() {
        truncate();
    }

    @Test
    void guestsPastTheRetentionWindowAreSelected() {
        insertUser("it-sweep-abandoned", AuthProvider.GUEST, CUTOFF.minus(1, ChronoUnit.DAYS));

        assertThat(sweepCandidates())
                .extracting(UserEntity::getUsername)
                .containsExactly("it-sweep-abandoned");
    }

    @Test
    void guestsThatWereActiveInsideTheWindowSurvive() {
        insertUser("it-sweep-active", AuthProvider.GUEST, Instant.now());

        assertThat(sweepCandidates()).isEmpty();
    }

    @Test
    void aProviderBackedAccountIsNeverSweptHoweverStale() {
        // Google users keep their data forever — only guests are disposable.
        insertUser("it-sweep-google", AuthProvider.GOOGLE, CUTOFF.minus(365, ChronoUnit.DAYS));

        assertThat(sweepCandidates()).isEmpty();
    }

    @Test
    void theCutoffItselfIsNotStale() {
        // Before, not on-or-before: a clock exactly at the cutoff survives.
        insertUser("it-sweep-borderline", AuthProvider.GUEST, CUTOFF);

        assertThat(sweepCandidates()).isEmpty();
    }

    @Test
    void sweepingAGuestTakesEverythingItOwnedAndSparesTheRest() {
        // The sweep's own call path: select, then deleteAll. Nothing enumerates
        // the child tables — ON DELETE CASCADE does that.
        long abandoned = insertUser("it-sweep-doomed", AuthProvider.GUEST, Instant.now());
        long active = insertUser("it-sweep-spared", AuthProvider.GUEST, Instant.now());
        insertOwnedRows(abandoned);
        insertOwnedRows(active);
        // Those inserts bumped both clocks via the trigger, so the abandoned
        // guest has to be backdated afterwards to look stale at all.
        setLastActivity(abandoned, CUTOFF.minus(1, ChronoUnit.DAYS));

        userRepository.deleteAll(sweepCandidates());

        assertThat(userRepository.findByUsername("it-sweep-doomed")).isEmpty();
        assertThat(userRepository.findByUsername("it-sweep-spared")).isPresent();
        assertThat(ownedRowCount(abandoned)).isZero();
        assertThat(ownedRowCount(active)).isEqualTo(3);
    }

    @Test
    void theIndexBehindTheSweepExists() {
        List<String> definitions = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_users_provider_activity'",
                String.class);

        assertThat(definitions).singleElement()
                .satisfies(def -> assertThat(def).contains("provider", "last_activity_at"));
    }

    private List<UserEntity> sweepCandidates() {
        return userRepository.findByProviderAndLastActivityAtBefore(AuthProvider.GUEST, CUTOFF);
    }

    private void insertOwnedRows(long owner) {
        jdbc.update("""
                INSERT INTO watch_progress (user_id, tmdb_id, media_type, progress_seconds, duration_seconds, updated_at)
                VALUES (?, 550, 'movie', 120, 7200, ?)
                """, owner, utc(Instant.now()));
        jdbc.update("INSERT INTO favourites (user_id, tmdb_id, media_type, created_at) VALUES (?, 550, 'movie', ?)",
                owner, utc(Instant.now()));
        jdbc.update("INSERT INTO refresh_tokens (token_hash, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
                "hash-owned-by-" + owner, owner, utc(Instant.now().plus(30, ChronoUnit.DAYS)), utc(Instant.now()));
    }

    private void setLastActivity(long owner, Instant lastActivityAt) {
        jdbc.update("UPDATE users SET last_activity_at = ? WHERE id = ?", utc(lastActivityAt), owner);
    }

    private int ownedRowCount(long owner) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM watch_progress WHERE user_id = ?)
                     + (SELECT count(*) FROM favourites WHERE user_id = ?)
                     + (SELECT count(*) FROM refresh_tokens WHERE user_id = ?)
                """, Integer.class, owner, owner, owner);
    }

    private long insertUser(String username, AuthProvider provider, Instant lastActivityAt) {
        return jdbc.queryForObject("""
                INSERT INTO users (username, provider, created_at, last_activity_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, username, provider.name(), utc(Instant.now()), utc(lastActivityAt));
    }

    private void truncate() {
        jdbc.execute("TRUNCATE users, refresh_tokens, watch_progress, favourites RESTART IDENTITY CASCADE");
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
