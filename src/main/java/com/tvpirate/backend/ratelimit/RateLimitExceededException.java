package com.tvpirate.backend.ratelimit;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** A 429 with Retry-After — routed through GlobalExceptionHandler's 4xx path,
 * which keeps both the detail and the header. vault:rate-limiting-deep-dive#lanes */
public class RateLimitExceededException extends ErrorResponseException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(Duration retryAfter) {
        this(Math.max(1, ceilSeconds(retryAfter)));
    }

    private RateLimitExceededException(long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many requests — try again in " + humanize(retryAfterSeconds) + "."),
                null);
        this.retryAfterSeconds = retryAfterSeconds;
        getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    private static long ceilSeconds(Duration duration) {
        long seconds = duration.getSeconds();
        return duration.getNano() > 0 ? seconds + 1 : seconds;
    }

    static String humanize(long seconds) {
        if (seconds < 60) {
            return seconds + (seconds == 1 ? " second" : " seconds");
        }
        long minutes = (seconds + 59) / 60;
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }
}
