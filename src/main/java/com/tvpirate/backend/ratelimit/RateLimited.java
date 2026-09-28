package com.tvpirate.backend.ratelimit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Picks the rate-limit policy for a controller method or class — the method
 * wins; an unannotated /api route falls back to {@link RateLimitPolicy#DEFAULT}.
 * vault:rate-limiting-deep-dive#policies */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {
    RateLimitPolicy value();
}
