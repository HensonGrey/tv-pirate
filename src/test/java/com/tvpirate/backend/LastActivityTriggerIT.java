package com.tvpirate.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

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
 * touch_user_last_activity() — the DB-owned activity clock the guest sweep
 * reads. Driven by plain SQL rather than the services on purpose: the reason
 * it is a trigger at all is that ANY write path keeps the clock fresh. The
 * writes have to commit, because a rolling-back transaction never shows the
 * trigger's effect. vault:guest-cleanup-deep-dive#trigger
 */
@SpringBootTest
@ActiveProfiles("it")
class LastActivityTriggerIT {

    /** Any clearly-past value: the assertion is "the trigger moved it to now". */
    private static final Instant STALE = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    private long userId;
    private long bystanderId;

    @BeforeEach
    void truncateAndSeedUsers() {
        truncate();
        userId = insertGuest("it-trigger-a");
        bystanderId = insertGuest("it-trigger-b");
    }

    @AfterEach
    void cleanUp() {
        truncate();
    }

    @Test
    void savingWatchProgressBumpsTheOwnersClock() {
        backdate(userId);

        insertWatchProgress(userId);

        assertThat(lastActivityOf(userId))
                .isAfter(STALE)
                .isCloseTo(Instant.now(), within(2, ChronoUnit.MINUTES));
    }

    @Test
    void favouritingBumpsTheOwnersClock() {
        backdate(userId);

        insertFavourite(userId);

        assertThat(lastActivityOf(userId)).isAfter(STALE);
    }

    @Test
    void issuingARefreshTokenBumpsTheOwnersClock() {
        // This is what keeps a guest with the app open alive: the frontend's
        // silent refresh every 15 minutes counts as activity.
        backdate(userId);

        insertRefreshToken(userId, "hash-refresh-keepalive");

        assertThat(lastActivityOf(userId)).isAfter(STALE);
    }

    @Test
    void updatingARowBumpsTheClockToo() {
        insertWatchProgress(userId);
        backdate(userId);

        jdbc.update("UPDATE watch_progress SET progress_seconds = 900 WHERE user_id = ?", userId);

        assertThat(lastActivityOf(userId)).isAfter(STALE);
    }

    @Test
    void deletingARowBumpsTheClockToo() {
        // The trigger is AFTER INSERT OR UPDATE OR DELETE, so un-favouriting is
        // activity as well — it reads OLD.user_id when NEW is gone.
        insertFavourite(userId);
        backdate(userId);

        jdbc.update("DELETE FROM favourites WHERE user_id = ?", userId);

        assertThat(lastActivityOf(userId)).isAfter(STALE);
    }

    @Test
    void oneUsersActivityNeverTouchesAnother() {
        backdate(userId);
        backdate(bystanderId);

        insertWatchProgress(userId);

        assertThat(lastActivityOf(userId)).isAfter(STALE);
        assertThat(lastActivityOf(bystanderId)).isEqualTo(STALE);
    }

    @Test
    void reSavingAStaleUserCannotRewindTheClock() {
        // Why UserEntity maps the column updatable=false: this copy is loaded
        // before the trigger fires, so a dirty-checked save would write the
        // old clock straight back over the fresher one.
        UserEntity loadedBeforeTheWrite = userRepository.findById(userId).orElseThrow();
        insertFavourite(userId);
        Instant bumped = lastActivityOf(userId);

        userRepository.saveAndFlush(loadedBeforeTheWrite);

        assertThat(lastActivityOf(userId)).isEqualTo(bumped);
    }

    private void insertWatchProgress(long owner) {
        jdbc.update("""
                INSERT INTO watch_progress (user_id, tmdb_id, media_type, progress_seconds, duration_seconds, updated_at)
                VALUES (?, 550, 'movie', 120, 7200, ?)
                """, owner, utc(Instant.now()));
    }

    private void insertFavourite(long owner) {
        jdbc.update("INSERT INTO favourites (user_id, tmdb_id, media_type, created_at) VALUES (?, 550, 'movie', ?)",
                owner, utc(Instant.now()));
    }

    private void insertRefreshToken(long owner, String tokenHash) {
        jdbc.update("INSERT INTO refresh_tokens (token_hash, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
                tokenHash, owner, utc(Instant.now().plus(30, ChronoUnit.DAYS)), utc(Instant.now()));
    }

    private void backdate(long id) {
        jdbc.update("UPDATE users SET last_activity_at = ? WHERE id = ?", utc(STALE), id);
    }

    private Instant lastActivityOf(long id) {
        OffsetDateTime stored = jdbc.queryForObject(
                "SELECT last_activity_at FROM users WHERE id = ?", OffsetDateTime.class, id);
        return stored.toInstant();
    }

    private long insertGuest(String username) {
        OffsetDateTime now = utc(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO users (username, provider, created_at, last_activity_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, username, AuthProvider.GUEST.name(), now, now);
    }

    private void truncate() {
        jdbc.execute("TRUNCATE users, refresh_tokens, watch_progress, favourites RESTART IDENTITY CASCADE");
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
