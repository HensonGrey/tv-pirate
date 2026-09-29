package com.tvpirate.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.auth.AuthService;

/**
 * Two tabs refreshing with the same one-time cookie at the same instant: one
 * must win and the other get a 401 (which the frontend recovers from), never a
 * 500. Not @Transactional: each refresh needs its own committed transaction to
 * race against the other.
 */
@SpringBootTest
@ActiveProfiles("it")
class RefreshRaceIT {

    private static final int ROUNDS = 20;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void truncate() {
        jdbc.execute("TRUNCATE users, refresh_tokens, watch_progress, favourites RESTART IDENTITY CASCADE");
    }

    @Test
    void simultaneousRefreshesWithOneTokenGiveOneWinnerAndOne401() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                String token = authService.loginAsGuest().refreshToken();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Integer>> outcomes = new ArrayList<>();
                for (int tab = 0; tab < 2; tab++) {
                    outcomes.add(pool.submit(() -> {
                        start.await();
                        try {
                            authService.refresh(token);
                            return 200;
                        } catch (ResponseStatusException e) {
                            return e.getStatusCode().value();
                        } catch (RuntimeException e) {
                            return 500; // what the exception handler would have answered
                        }
                    }));
                }
                start.countDown();

                List<Integer> statuses = List.of(outcomes.get(0).get(), outcomes.get(1).get());
                assertThat(statuses).as("round " + round).containsExactlyInAnyOrder(200, 401);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
