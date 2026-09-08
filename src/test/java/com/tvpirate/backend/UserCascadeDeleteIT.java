package com.tvpirate.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.tvpirate.backend.user.AuthProvider;
import com.tvpirate.backend.user.UserRepository;

/**
 * ON DELETE CASCADE on the FKs to users(id). Without it, every new
 * user-scoped table has to be hand-added to the guest sweep or the nightly
 * job dies on an FK violation; with it, one DELETE FROM users is enough. The
 * blanket assertion is on purpose — a future table wired without the cascade
 * fails this test rather than the 03:17 cron.
 * vault:guest-cleanup-deep-dive#cron
 */
@SpringBootTest
@ActiveProfiles("it")
class UserCascadeDeleteIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    private long userId;
    private long bystanderId;

    @BeforeEach
    void truncateAndSeedUsers() {
        truncate();
        userId = insertGuest("it-cascade-a");
        bystanderId = insertGuest("it-cascade-b");
    }

    @AfterEach
    void cleanUp() {
        truncate();
    }

    @Test
    void everyForeignKeyToUsersDeclaresOnDeleteCascade() {
        List<Map<String, Object>> foreignKeys = jdbc.queryForList("""
                SELECT conrelid::regclass::text AS child, confdeltype::text AS delete_rule
                FROM pg_constraint
                WHERE contype = 'f' AND confrelid = 'users'::regclass
                """);

        assertThat(foreignKeys)
                .extracting(fk -> fk.get("child"))
                .containsExactlyInAnyOrder("refresh_tokens", "watch_progress", "favourites");
        // 'c' is Postgres' code for CASCADE; 'a' (no action) is the pre-0008 state.
        assertThat(foreignKeys).allSatisfy(fk -> assertThat(fk.get("delete_rule")).isEqualTo("c"));
    }

    @Test
    void deletingAUserTakesEverythingItOwnedWithIt() {
        insertOwnedRows(userId);

        userRepository.deleteById(userId);

        assertThat(countFor("watch_progress", userId)).isZero();
        assertThat(countFor("favourites", userId)).isZero();
        assertThat(countFor("refresh_tokens", userId)).isZero();
        assertThat(userRepository.findById(userId)).isEmpty();
    }

    @Test
    void deletingAUserLeavesEveryOtherOwnersRowsAlone() {
        insertOwnedRows(userId);
        insertOwnedRows(bystanderId);

        userRepository.deleteById(userId);

        assertThat(countFor("watch_progress", bystanderId)).isOne();
        assertThat(countFor("favourites", bystanderId)).isOne();
        assertThat(countFor("refresh_tokens", bystanderId)).isOne();
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

    private int countFor(String table, long owner) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, owner);
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
