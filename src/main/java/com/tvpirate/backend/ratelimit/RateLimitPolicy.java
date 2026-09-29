package com.tvpirate.backend.ratelimit;

import static java.time.Duration.ofDays;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;

import java.time.Duration;
import java.util.List;

import io.github.bucket4j.Bandwidth;

/** Every rate-limit number in the app. vault:rate-limiting-deep-dive#policies */
public enum RateLimitPolicy {

    GUEST_CREATE(Scope.NETWORK,
            List.of(Limit.greedy(3, 1, ofMinutes(20)), Limit.intervally(10, 10, ofDays(1))),
            Limit.greedy(60, 60, ofHours(1)), 0, 0),
    AUTH_SESSION(Scope.NETWORK, List.of(Limit.greedy(10, 10, ofMinutes(1))), null, 0, 0),
    STREAM_RESOLVE(Scope.USER, List.of(Limit.greedy(10, 1, ofSeconds(6))),
            Limit.greedy(60, 60, ofMinutes(1)), 4, 8),
    SUBTITLES(Scope.USER, List.of(Limit.greedy(10, 10, ofMinutes(1))),
            Limit.greedy(60, 60, ofMinutes(1)), 4, 8),
    // A search is two requests (movies + tv), one TMDB call each.
    TMDB_SEARCH(Scope.USER, List.of(Limit.greedy(30, 30, ofMinutes(1))), null, 0, 0),
    // High burst: the Library tab fires one detail call per title at once.
    TMDB(Scope.USER, List.of(Limit.greedy(150, 40, ofMinutes(1))), null, 0, 0),
    DEFAULT(Scope.USER, List.of(Limit.greedy(30, 30, ofMinutes(1))), null, 0, 0);

    public static final int ACCOUNT_MULTIPLIER = 3;

    /** The playback proxy's in-flight caps (it's never rate limited); raise with server.tomcat.threads.max. */
    public static final int PROXY_IN_FLIGHT_PER_OWNER = 8;
    public static final int PROXY_IN_FLIGHT_TOTAL = 24;

    public enum Scope { USER, NETWORK }

    public enum Tier { GUEST, ACCOUNT }

    private final Scope scope;
    private final List<Limit> guestLimits;
    private final Limit global;
    private final int inFlightPerKey;
    private final int inFlightTotal;

    RateLimitPolicy(Scope scope, List<Limit> guestLimits, Limit global, int inFlightPerKey, int inFlightTotal) {
        this.scope = scope;
        this.guestLimits = guestLimits;
        this.global = global;
        this.inFlightPerKey = inFlightPerKey;
        this.inFlightTotal = inFlightTotal;
    }

    public Scope scope() {
        return scope;
    }

    public List<Limit> limits(Tier tier) {
        int factor = tier == Tier.ACCOUNT && scope == Scope.USER ? ACCOUNT_MULTIPLIER : 1;
        return guestLimits.stream().map(limit -> limit.times(factor)).toList();
    }

    /** Shared by every caller — the only layer rotating IPs or guests can't dodge; null = none. */
    public Limit global() {
        return global;
    }

    /** 0 = no at-once cap. */
    public int inFlightPerKey() {
        return inFlightPerKey;
    }

    /** 0 = no global at-once cap. */
    public int inFlightTotal() {
        return inFlightTotal;
    }

    /** How long an idle bucket needs to refill completely — a bucket evicted
     * sooner would forgive a debt its caller still owed. */
    public Duration fullRefill(Tier tier) {
        return limits(tier).stream().map(Limit::fullRefill).max(Duration::compareTo).orElseThrow();
    }

    /** One bandwidth: {@code capacity} burst, refilled {@code tokens} per {@code period}.
     * Greedy refill trickles tokens in; interval refill adds them all at the period's end. */
    public record Limit(long capacity, long tokens, Duration period, boolean greedy) {

        static Limit greedy(long capacity, long tokens, Duration period) {
            return new Limit(capacity, tokens, period, true);
        }

        static Limit intervally(long capacity, long tokens, Duration period) {
            return new Limit(capacity, tokens, period, false);
        }

        Limit times(int factor) {
            return new Limit(capacity * factor, tokens * factor, period, greedy);
        }

        Duration fullRefill() {
            return period.multipliedBy(capacity).dividedBy(tokens);
        }

        Bandwidth toBandwidth() {
            var builder = Bandwidth.builder().capacity(capacity);
            return (greedy ? builder.refillGreedy(tokens, period) : builder.refillIntervally(tokens, period)).build();
        }
    }
}
