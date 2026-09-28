package com.tvpirate.backend.ratelimit;

import static java.time.Duration.ofDays;
import static java.time.Duration.ofHours;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;

import java.time.Duration;
import java.util.List;

import io.github.bucket4j.Bandwidth;

/**
 * Every rate-limit number in the app, in one place. Guest numbers are the
 * base; an account gets {@link #ACCOUNT_MULTIPLIER}× burst and refill on the
 * user-keyed policies. Network-keyed policies and the at-once caps are the
 * same for everyone — the caps protect Tomcat threads, not a user's share.
 * vault:rate-limiting-deep-dive#policies
 */
public enum RateLimitPolicy {

    /** POST /api/auth/guest — every call is a users + refresh_tokens insert. */
    GUEST_CREATE(Scope.NETWORK,
            List.of(Limit.greedy(3, 1, ofMinutes(20)), Limit.intervally(10, 10, ofDays(1))),
            Limit.greedy(60, 60, ofHours(1)), 0, 0),
    /** POST /api/auth/refresh and /logout. */
    AUTH_SESSION(Scope.NETWORK, List.of(Limit.greedy(10, 10, ofMinutes(1))), null, 0, 0),
    /** GET /api/stream/sources — 3–9 gray-market provider calls each. */
    STREAM_RESOLVE(Scope.USER, List.of(Limit.greedy(10, 1, ofSeconds(6))),
            Limit.greedy(60, 60, ofMinutes(1)), 4, 8),
    /** GET /api/subtitles — OpenSubtitles' quota is tiny. */
    SUBTITLES(Scope.USER, List.of(Limit.greedy(10, 10, ofMinutes(1))),
            Limit.greedy(60, 60, ofMinutes(1)), 4, 8),
    /** GET /api/tmdb/search — two TMDB calls per cache miss. */
    TMDB_SEARCH(Scope.USER, List.of(Limit.greedy(15, 15, ofMinutes(1))), null, 0, 0),
    /** The rest of /api/tmdb/** — the burst stays high because the Library tab
     * fires one detail call per title at once. */
    TMDB(Scope.USER, List.of(Limit.greedy(150, 40, ofMinutes(1))), null, 0, 0),
    /** Any /api route without an annotation: me, progress, favourites, providers. */
    DEFAULT(Scope.USER, List.of(Limit.greedy(30, 30, ofMinutes(1))), null, 0, 0);

    /** Accounts (any provider but GUEST) get this many times a guest's budget. */
    public static final int ACCOUNT_MULTIPLIER = 3;

    /** Stream lane (the playback proxy): never rate limited, only capped in
     * flight. Together with the API caps above: 24 + 8 + 8 of Tomcat's 50
     * threads — raise them with server.tomcat.threads.max. */
    public static final int PROXY_IN_FLIGHT_PER_OWNER = 8;
    public static final int PROXY_IN_FLIGHT_TOTAL = 24;

    /** What a bucket is keyed by: the signed-in user, or the client's network. */
    public enum Scope { USER, NETWORK }

    /** Guest is the base tier; network-keyed policies only ever use it. */
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
