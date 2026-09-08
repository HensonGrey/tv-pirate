package com.tvpirate.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.tvpirate.backend.progress.ProgressService;
import com.tvpirate.backend.progress.dto.ProgressRowDto;
import com.tvpirate.backend.progress.dto.SaveProgressRequest;
import com.tvpirate.backend.user.AuthProvider;

/**
 * The heartbeat upsert against the two <em>partial</em> unique indexes: which
 * rows are the same row, and which only look alike. Nothing but real Postgres
 * reproduces it — default NULLS DISTINCT is why movie and tv rows need
 * separate indexes, and the arbitration is what the ON CONFLICT predicates
 * have to name. vault:watch-progress-deep-dive#schema
 */
@SpringBootTest
@ActiveProfiles("it")
class WatchProgressUpsertIT {

    private static final long MOVIE_TMDB_ID = 550L;
    private static final long SHOW_TMDB_ID = 1399L;

    @Autowired
    private ProgressService progressService;

    @Autowired
    private JdbcTemplate jdbc;

    private long userId;
    private long otherUserId;

    // No @Transactional: the upserts have to actually commit for the ON CONFLICT
    // arbitration to be the real thing rather than one statement's view.
    @BeforeEach
    void truncateAndSeedUsers() {
        truncate();
        userId = insertGuest("it-progress-a");
        otherUserId = insertGuest("it-progress-b");
    }

    @AfterEach
    void cleanUp() {
        truncate();
    }

    @Test
    void savingTheSameMovieTwiceKeepsOneRowAtTheSecondPosition() {
        progressService.upsert(userId, movieAt(120, 7200));
        progressService.upsert(userId, movieAt(300, 7260));

        List<ProgressRowDto> rows = progressService.list(userId);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.progressSeconds()).isEqualTo(300);
            assertThat(row.durationSeconds()).isEqualTo(7260);
            assertThat(row.season()).isNull();
            assertThat(row.episode()).isNull();
        });
    }

    @Test
    void savingTheSameEpisodeTwiceKeepsOneRowAtTheSecondPosition() {
        progressService.upsert(userId, episodeAt(1, 1, 120, 2700));
        progressService.upsert(userId, episodeAt(1, 1, 480, 2700));

        assertThat(progressService.list(userId)).singleElement()
                .satisfies(row -> assertThat(row.progressSeconds()).isEqualTo(480));
    }

    @Test
    void aMovieAndAShowSharingATmdbIdAreSeparateRows() {
        // The two TMDB id namespaces collide, so media_type is part of the identity.
        progressService.upsert(userId, movieAt(120, 7200));
        progressService.upsert(userId, episodeAt(1, 1, 240, 2700));

        assertThat(progressService.list(userId))
                .extracting(ProgressRowDto::mediaType, ProgressRowDto::progressSeconds)
                .containsExactlyInAnyOrder(tuple("movie", 120), tuple("tv", 240));
    }

    @Test
    void differentEpisodesOfOneShowCoexist() {
        progressService.upsert(userId, episodeAt(1, 1, 120, 2700));
        progressService.upsert(userId, episodeAt(1, 2, 240, 2700));
        progressService.upsert(userId, episodeAt(2, 1, 360, 2700));

        assertThat(progressService.list(userId))
                .extracting(ProgressRowDto::season, ProgressRowDto::episode)
                .containsExactlyInAnyOrder(tuple(1, 1), tuple(1, 2), tuple(2, 1));
    }

    @Test
    void theSameEpisodeForTwoUsersStaysTwoRows() {
        progressService.upsert(userId, episodeAt(1, 1, 120, 2700));
        progressService.upsert(otherUserId, episodeAt(1, 1, 999, 2700));

        assertThat(rowCount()).isEqualTo(2);
        assertThat(progressService.list(userId)).singleElement()
                .satisfies(row -> assertThat(row.progressSeconds()).isEqualTo(120));
        assertThat(progressService.list(otherUserId)).singleElement()
                .satisfies(row -> assertThat(row.progressSeconds()).isEqualTo(999));
    }

    @Test
    void aTvHeartbeatWithoutCoordinatesCannotUpsert() {
        // Documents why ProgressController's "season and episode are required
        // for tv" guard is load-bearing: a coordinate-less tv row lands outside
        // uq_watch_progress_tv (the arbiter) but inside uq_watch_progress_movie,
        // so the second one raises a unique violation rather than updating.
        SaveProgressRequest coordinateless =
                new SaveProgressRequest(SHOW_TMDB_ID, "tv", null, null, 120, 2700);
        progressService.upsert(userId, coordinateless);

        assertThatThrownBy(() -> progressService.upsert(userId, coordinateless))
                .hasMessageContaining("uq_watch_progress_movie");

        assertThat(rowCount()).isEqualTo(1); // no duplicate slipped through either
    }

    @Test
    void startOverClearsEveryEpisodeOfTheShowAndNothingElse() {
        progressService.upsert(userId, episodeAt(1, 1, 120, 2700));
        progressService.upsert(userId, episodeAt(2, 4, 240, 2700));
        progressService.upsert(userId, movieAt(300, 7200));
        progressService.upsert(otherUserId, episodeAt(1, 1, 400, 2700));

        progressService.delete(userId, "tv", SHOW_TMDB_ID, null, null);

        assertThat(progressService.list(userId))
                .extracting(ProgressRowDto::mediaType)
                .containsExactly("movie");
        assertThat(progressService.list(otherUserId)).hasSize(1);
    }

    @Test
    void theListIsOrderedNewestFirst() {
        progressService.upsert(userId, movieAt(120, 7200));
        progressService.upsert(userId, episodeAt(1, 1, 240, 2700));
        // Backdate the movie row: two upserts inside one test can share a clock tick.
        jdbc.update("UPDATE watch_progress SET updated_at = ? WHERE media_type = 'movie'",
                utc(Instant.now().minus(2, ChronoUnit.HOURS)));

        assertThat(progressService.list(userId))
                .extracting(ProgressRowDto::mediaType)
                .containsExactly("tv", "movie");
    }

    @Test
    void aConflictTargetWithoutThePartialPredicateIsRejectedOutright() {
        // The hazard is real but it is not silent: Postgres cannot infer a
        // partial index from a column-only conflict target, so a heartbeat
        // written that way fails to plan (SQLSTATE 42P10) on the very first
        // call instead of quietly duplicating rows.
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO watch_progress (user_id, tmdb_id, media_type, progress_seconds, updated_at)
                VALUES (?, ?, 'movie', 120, ?)
                ON CONFLICT (user_id, tmdb_id) DO UPDATE SET progress_seconds = EXCLUDED.progress_seconds
                """, userId, MOVIE_TMDB_ID, utc(Instant.now())))
                .isInstanceOf(BadSqlGrammarException.class)
                .rootCause()
                .hasMessageContaining("no unique or exclusion constraint matching the ON CONFLICT specification");

        assertThat(rowCount()).isZero();
    }

    @Test
    void theIndexBehindThePerPageLoadProgressQueryExists() {
        List<String> definitions = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_watch_progress_user_updated'",
                String.class);

        assertThat(definitions).singleElement()
                .satisfies(def -> assertThat(def).contains("user_id", "updated_at DESC"));
    }

    private SaveProgressRequest movieAt(int progressSeconds, Integer durationSeconds) {
        return new SaveProgressRequest(MOVIE_TMDB_ID, "movie", null, null, progressSeconds, durationSeconds);
    }

    private SaveProgressRequest episodeAt(int season, int episode, int progressSeconds, Integer durationSeconds) {
        return new SaveProgressRequest(SHOW_TMDB_ID, "tv", season, episode, progressSeconds, durationSeconds);
    }

    private long insertGuest(String username) {
        OffsetDateTime now = utc(Instant.now());
        return jdbc.queryForObject("""
                INSERT INTO users (username, provider, created_at, last_activity_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, username, AuthProvider.GUEST.name(), now, now);
    }

    private int rowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM watch_progress", Integer.class);
    }

    private void truncate() {
        jdbc.execute("TRUNCATE users, refresh_tokens, watch_progress, favourites RESTART IDENTITY CASCADE");
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
