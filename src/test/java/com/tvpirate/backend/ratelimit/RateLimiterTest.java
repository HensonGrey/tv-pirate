package com.tvpirate.backend.ratelimit;

import static com.tvpirate.backend.ratelimit.RateLimitPolicy.DEFAULT;
import static com.tvpirate.backend.ratelimit.RateLimitPolicy.GUEST_CREATE;
import static com.tvpirate.backend.ratelimit.RateLimitPolicy.STREAM_RESOLVE;
import static com.tvpirate.backend.ratelimit.RateLimitPolicy.TMDB;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.tvpirate.backend.ratelimit.RateLimitPolicy.Tier;
import com.tvpirate.backend.ratelimit.RateLimiter.Decision;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/** The bucket maths behind every policy, on a hand-driven clock. */
class RateLimiterTest {

    private FakeClock clock;
    private RateLimiter limiter;
    private ListAppender<ILoggingEvent> logs;
    private Logger limiterLogger;

    @BeforeEach
    void setUp() {
        clock = new FakeClock();
        limiter = new RateLimiter(clock);
        logs = new ListAppender<>();
        logs.start();
        limiterLogger = (Logger) LoggerFactory.getLogger(RateLimiter.class);
        limiterLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        limiterLogger.detachAppender(logs);
    }

    @Test
    void theBurstPassesThenTheNextCallWaitsForARefill() {
        assertThat(consume(DEFAULT, Tier.GUEST, "u1", 30)).allMatch(Decision::allowed);

        Decision rejected = limiter.tryConsume(DEFAULT, Tier.GUEST, "u1");
        assertThat(rejected.allowed()).isFalse();
        // 30/min refills one token every 2 s.
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofSeconds(2));

        clock.advance(Duration.ofSeconds(2));
        assertThat(limiter.tryConsume(DEFAULT, Tier.GUEST, "u1").allowed()).isTrue();
    }

    @Test
    void guestCreationAllowsThreeThenOneEveryTwentyMinutes() {
        assertThat(consume(GUEST_CREATE, Tier.GUEST, "n203.0.113.7", 3)).allMatch(Decision::allowed);

        Decision rejected = limiter.tryConsume(GUEST_CREATE, Tier.GUEST, "n203.0.113.7");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofMinutes(20));
    }

    @Test
    void guestCreationStopsAtTenADayEvenWhenPaced() {
        String network = "n203.0.113.7";
        consume(GUEST_CREATE, Tier.GUEST, network, 3);
        for (int i = 0; i < 7; i++) {
            clock.advance(Duration.ofMinutes(20));
            assertThat(limiter.tryConsume(GUEST_CREATE, Tier.GUEST, network).allowed()).isTrue();
        }

        clock.advance(Duration.ofMinutes(20));
        Decision eleventh = limiter.tryConsume(GUEST_CREATE, Tier.GUEST, network);
        assertThat(eleventh.allowed()).isFalse();
        // The daily bandwidth is the one refusing: its wait runs to the end of the day.
        assertThat(eleventh.retryAfter()).isGreaterThan(Duration.ofHours(20));
    }

    @Test
    void theGlobalCapHoldsAcrossManyUsersAndRefundsTheirOwnToken() {
        // Six guests use their whole bursts: exactly the 60/min global allowance.
        for (int user = 0; user < 6; user++) {
            assertThat(consume(STREAM_RESOLVE, Tier.GUEST, "u" + user, 10)).allMatch(Decision::allowed);
        }
        // A seventh guest has a full bucket but the global one is empty.
        assertThat(consume(STREAM_RESOLVE, Tier.GUEST, "u6", 5)).noneMatch(Decision::allowed);

        // 10 s refills the global bucket by 10 but u6's own by under 2. Without
        // the refund u6 would be down to ~6 tokens; with it, the full 10.
        clock.advance(Duration.ofSeconds(10));
        assertThat(consume(STREAM_RESOLVE, Tier.GUEST, "u6", 10)).allMatch(Decision::allowed);
    }

    @Test
    void aGlobalTripLogsOneWarnPerMinuteNotOnePerRejection() {
        for (int user = 0; user < 6; user++) {
            consume(STREAM_RESOLVE, Tier.GUEST, "u" + user, 10);
        }
        consume(STREAM_RESOLVE, Tier.GUEST, "u6", 5);
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().getFirst().getFormattedMessage()).contains("STREAM_RESOLVE");

        // A minute later the global bucket is full again; six fresh guests drain it.
        clock.advance(Duration.ofMinutes(1));
        for (int user = 7; user < 13; user++) {
            consume(STREAM_RESOLVE, Tier.GUEST, "u" + user, 10);
        }
        assertThat(warnings()).hasSize(1);
        consume(STREAM_RESOLVE, Tier.GUEST, "u13", 1);
        assertThat(warnings()).hasSize(2);
    }

    @Test
    void anAccountGetsThreeTimesAGuestsBurstAndRefill() {
        assertThat(consume(DEFAULT, Tier.ACCOUNT, "u1", 90)).allMatch(Decision::allowed);

        Decision rejected = limiter.tryConsume(DEFAULT, Tier.ACCOUNT, "u1");
        assertThat(rejected.allowed()).isFalse();
        // 90/min is a token every 2/3 s, three times a guest's rate.
        assertThat(rejected.retryAfter()).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void networkPoliciesIgnoreTheAccountTier() {
        assertThat(consume(GUEST_CREATE, Tier.ACCOUNT, "n203.0.113.7", 3)).allMatch(Decision::allowed);
        assertThat(limiter.tryConsume(GUEST_CREATE, Tier.ACCOUNT, "n203.0.113.8").allowed()).isTrue();
        assertThat(limiter.tryConsume(GUEST_CREATE, Tier.ACCOUNT, "n203.0.113.7").allowed()).isFalse();
    }

    @Test
    void keysTiersAndPoliciesDontShareBuckets() {
        consume(DEFAULT, Tier.GUEST, "u1", 30);
        assertThat(limiter.tryConsume(DEFAULT, Tier.GUEST, "u1").allowed()).isFalse();

        assertThat(limiter.tryConsume(DEFAULT, Tier.GUEST, "u2").allowed()).isTrue();
        assertThat(limiter.tryConsume(DEFAULT, Tier.ACCOUNT, "u1").allowed()).isTrue();
        assertThat(limiter.tryConsume(TMDB, Tier.GUEST, "u1").allowed()).isTrue();
    }

    private List<Decision> consume(RateLimitPolicy policy, Tier tier, String key, int times) {
        return java.util.stream.IntStream.range(0, times)
                .mapToObj(i -> limiter.tryConsume(policy, tier, key))
                .toList();
    }

    private List<ILoggingEvent> warnings() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }
}
