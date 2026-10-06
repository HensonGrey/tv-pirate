package com.tvpirate.backend.ratelimit;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tvpirate.backend.ratelimit.RateLimitPolicy.Limit;
import com.tvpirate.backend.ratelimit.RateLimitPolicy.Tier;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.local.LocalBucketBuilder;

/**
 * "How often": one token bucket per policy + tier + key, then the policy's
 * global bucket. In memory — correct for a single JVM.
 * vault:rate-limiting-deep-dive#lanes
 */
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    /** Per-policy bound on remembered callers — an evicted bucket starts full again. */
    private static final long MAX_KEYS_PER_POLICY = 50_000;
    private static final long WARN_EVERY_NANOS = Duration.ofMinutes(1).toNanos();

    /** The outcome of one check; {@code retryAfter} is zero when allowed. */
    public record Decision(boolean allowed, Duration retryAfter) {
        static final Decision ALLOWED = new Decision(true, Duration.ZERO);
    }

    private final TimeMeter clock;
    private final Map<RateLimitPolicy, Cache<String, Bucket>> buckets = new EnumMap<>(RateLimitPolicy.class);
    private final Map<RateLimitPolicy, Bucket> globals = new EnumMap<>(RateLimitPolicy.class);
    private final Map<RateLimitPolicy, AtomicLong> lastGlobalWarn = new EnumMap<>(RateLimitPolicy.class);

    public RateLimiter(TimeMeter clock) {
        this.clock = clock;
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            buckets.put(policy, Caffeine.newBuilder()
                    // Account buckets take as long as guest ones to refill (×3 both ways).
                    .expireAfterAccess(policy.fullRefill(Tier.GUEST))
                    .maximumSize(MAX_KEYS_PER_POLICY)
                    .ticker(clock::currentTimeNanos)
                    .build());
            if (policy.global() != null) {
                globals.put(policy, newBucket(List.of(policy.global())));
                lastGlobalWarn.put(policy, new AtomicLong(Long.MIN_VALUE));
            }
        }
    }

    /** Takes one token from the caller's bucket, then from the global one. */
    public Decision tryConsume(RateLimitPolicy policy, Tier tier, String key) {
        Decision own = tryConsumeOwn(policy, tier, key);
        return own.allowed() ? tryConsumeGlobal(policy, tier, key) : own;
    }

    public Decision tryConsumeOwn(RateLimitPolicy policy, Tier tier, String key) {
        ConsumptionProbe probe = bucket(policy, tier, key).tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Decision.ALLOWED : rejected(probe);
    }

    /** For a caller whose own token is already taken; a refusal hands that token
     * back so it isn't charged twice. */
    public Decision tryConsumeGlobal(RateLimitPolicy policy, Tier tier, String key) {
        Bucket global = globals.get(policy);
        if (global == null) {
            return Decision.ALLOWED;
        }
        ConsumptionProbe globalProbe = global.tryConsumeAndReturnRemaining(1);
        if (globalProbe.isConsumed()) {
            return Decision.ALLOWED;
        }
        bucket(policy, tier, key).addTokens(1);
        warnGlobalTrip(policy);
        return rejected(globalProbe);
    }

    private Bucket bucket(RateLimitPolicy policy, Tier tier, String key) {
        return buckets.get(policy).get(tier + ":" + key, k -> newBucket(policy.limits(tier)));
    }

    /** The VPN-attack tripwire: a global cap only trips when many callers pile
     * on at once. Throttled so a sustained attack can't flood the log.
     * vault:rate-limiting-deep-dive#vpn */
    private void warnGlobalTrip(RateLimitPolicy policy) {
        AtomicLong last = lastGlobalWarn.get(policy);
        long now = clock.currentTimeNanos();
        long previous = last.get();
        if ((previous == Long.MIN_VALUE || now - previous >= WARN_EVERY_NANOS) && last.compareAndSet(previous, now)) {
            log.warn("rate limit: global {} cap tripped — many callers at once (possible IP-rotation abuse)", policy);
        }
    }

    private Bucket newBucket(List<Limit> limits) {
        LocalBucketBuilder builder = Bucket.builder().withCustomTimePrecision(clock);
        limits.forEach(limit -> builder.addLimit(limit.toBandwidth()));
        return builder.build();
    }

    private static Decision rejected(ConsumptionProbe probe) {
        return new Decision(false, Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }
}
