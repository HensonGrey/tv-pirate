package com.tvpirate.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

import com.tvpirate.backend.favourite.FavouriteService;
import com.tvpirate.backend.favourite.dto.FavouriteRowDto;
import com.tvpirate.backend.user.AuthProvider;

/**
 * uq_favourites_user_title as the real constraint: media_type is part of the
 * identity (movie 550 and tv 550 are different titles), and the add path is
 * an ON CONFLICT DO NOTHING, so the optimistic UI's replays collapse onto one
 * row instead of a 500. vault:favourites-deep-dive#schema
 */
@SpringBootTest
@ActiveProfiles("it")
class FavouriteRepositoryIT {

    private static final long TMDB_ID = 550L;

    @Autowired
    private FavouriteService favouriteService;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;
    private long otherUserId;

    @BeforeEach
    void truncateAndSeedUsers() {
        truncate();
        userId = insertGuest("it-favourite-a");
        otherUserId = insertGuest("it-favourite-b");
    }

    @AfterEach
    void cleanUp() {
        truncate();
    }

    @Test
    void aMovieAndAShowSharingATmdbIdAreSeparateFavourites() {
        favouriteService.add(userId, TMDB_ID, "movie");
        favouriteService.add(userId, TMDB_ID, "tv");

        assertThat(favouriteService.list(userId))
                .extracting(FavouriteRowDto::tmdbId, FavouriteRowDto::mediaType)
                .containsExactlyInAnyOrder(tuple(TMDB_ID, "movie"), tuple(TMDB_ID, "tv"));
    }

    @Test
    void addingTheSameFavouriteAgainStaysOneUntouchedRow() {
        favouriteService.add(userId, TMDB_ID, "movie");
        Instant firstCreatedAt = createdAtOfOnlyRow();

        favouriteService.add(userId, TMDB_ID, "movie");

        assertThat(favouriteService.list(userId)).hasSize(1);
        // DO NOTHING, not DO UPDATE: a replayed heart keeps its original date.
        assertThat(createdAtOfOnlyRow()).isEqualTo(firstCreatedAt);
    }

    @Test
    void removingOneMediaTypeLeavesTheOtherAlone() {
        favouriteService.add(userId, TMDB_ID, "movie");
        favouriteService.add(userId, TMDB_ID, "tv");

        favouriteService.remove(userId, TMDB_ID, "movie");

        assertThat(favouriteService.list(userId))
                .extracting(FavouriteRowDto::mediaType)
                .containsExactly("tv");
    }

    @Test
    void removingSomethingThatWasNeverFavouritedIsANoOp() {
        // The optimistic toggle fires DELETE without knowing server state.
        favouriteService.remove(userId, TMDB_ID, "movie");

        assertThat(favouriteService.list(userId)).isEmpty();
    }

    @Test
    void oneUsersFavouritesAreInvisibleToAnother() {
        favouriteService.add(userId, TMDB_ID, "movie");

        assertThat(favouriteService.list(otherUserId)).isEmpty();
    }

    @Test
    void theListIsOldestFirst() {
        favouriteService.add(userId, TMDB_ID, "movie");
        favouriteService.add(userId, 1399L, "tv");
        // Backdate the tv row: two adds inside one test can share a clock tick.
        jdbc.update("UPDATE favourites SET created_at = ? WHERE media_type = 'tv'",
                utc(Instant.now().minus(1, ChronoUnit.HOURS)));

        assertThat(favouriteService.list(userId))
                .extracting(FavouriteRowDto::mediaType)
                .containsExactly("tv", "movie");
    }

    private Instant createdAtOfOnlyRow() {
        return jdbc.queryForObject("SELECT created_at FROM favourites", OffsetDateTime.class).toInstant();
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
