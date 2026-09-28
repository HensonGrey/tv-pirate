package com.tvpirate.backend.ratelimit;

import java.util.HashMap;
import java.util.Map;

/**
 * "How many at once": counts the requests currently running, per key and in
 * total, and turns new ones away at the cap instead of queueing them — a
 * queued request would still hold a Tomcat thread. Every successful
 * {@link #tryAcquire} must be paired with one {@link #release}.
 * One lock guards everything; at a few dozen requests in flight it costs nothing.
 * vault:rate-limiting-deep-dive#lanes
 */
public class InFlightLimiter {

    /** Most requests one key (user or network) may have running at once; 0 = no per-key cap. */
    private final int perKeyMax;
    /** Most requests all keys together may have running at once, so many callers can't
     * tie up every Tomcat thread between them; 0 = no overall cap. */
    private final int totalMax;
    /** Requests running right now, per key; a key is removed when it drops to 0. */
    private final Map<String, Integer> perKey = new HashMap<>();
    /** Requests running right now, across all keys. */
    private int total;

    /** A max of 0 disables that cap. */
    public InFlightLimiter(int perKeyMax, int totalMax) {
        this.perKeyMax = perKeyMax;
        this.totalMax = totalMax;
    }

    /** Lets the request in and counts it, or refuses without counting anything.
     * A null key counts toward the total cap only. */
    public synchronized boolean tryAcquire(String key) {
        if (totalMax > 0 && total >= totalMax) {
            return false;
        }
        boolean perKeyCapped = key != null && perKeyMax > 0;
        if (perKeyCapped && perKey.getOrDefault(key, 0) >= perKeyMax) {
            return false;
        }
        total++;
        if (perKeyCapped) {
            perKey.merge(key, 1, Integer::sum);
        }
        return true;
    }

    public synchronized void release(String key) {
        total--;
        if (key != null && perKey.containsKey(key)) {
            int left = perKey.get(key) - 1;
            if (left == 0) {
                perKey.remove(key); // idle keys don't pile up
            } else {
                perKey.put(key, left);
            }
        }
    }
}
