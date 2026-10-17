package com.tvpirate.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.tvpirate.backend.theme.ThemeService;
import com.tvpirate.backend.user.AuthProvider;

/**
 * The theme save is an ON CONFLICT DO UPDATE on the user's id: a second choice
 * replaces the first in place, so an account never holds two themes.
 */
@SpringBootTest
@ActiveProfiles("it")
class UserThemeRepositoryIT {

    @Autowired
    private ThemeService themeService;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void truncateAndSeedUser() {
        truncate();
        userId = insertGuest("it-theme-a");
    }

    @AfterEach
    void cleanUp() {
        truncate();
    }

    @Test
    void anAccountThatNeverPickedHasNoTheme() {
        assertThat(themeService.find(userId)).isEmpty();
    }

    @Test
    void aNewChoiceReplacesTheOldOneInTheSameRow() {
        themeService.save(userId, "dark");

        themeService.save(userId, "light");

        assertThat(themeService.find(userId)).contains("light");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_themes", Integer.class)).isOne();
    }

    private long insertGuest(String username) {
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        return jdbc.queryForObject("""
                INSERT INTO users (username, provider, created_at, last_activity_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, username, AuthProvider.GUEST.name(), now, now);
    }

    private void truncate() {
        jdbc.execute("TRUNCATE users, refresh_tokens, watch_progress, favourites, user_themes RESTART IDENTITY CASCADE");
    }
}
