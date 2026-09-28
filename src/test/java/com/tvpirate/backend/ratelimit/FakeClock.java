package com.tvpirate.backend.ratelimit;

import java.time.Duration;

import io.github.bucket4j.TimeMeter;

/** A clock the test moves by hand, so refill timing is exact instead of sleep-based. */
class FakeClock implements TimeMeter {

    private long nanos = Duration.ofDays(365).toNanos();

    @Override
    public long currentTimeNanos() {
        return nanos;
    }

    @Override
    public boolean isWallClockBased() {
        return false;
    }

    void advance(Duration duration) {
        nanos += duration.toNanos();
    }
}
