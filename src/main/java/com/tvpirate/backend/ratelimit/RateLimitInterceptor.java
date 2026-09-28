package com.tvpirate.backend.ratelimit;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.tvpirate.backend.ratelimit.RateLimitPolicy.Scope;
import com.tvpirate.backend.ratelimit.RateLimitPolicy.Tier;
import com.tvpirate.backend.security.AuthedUser;
import com.tvpirate.backend.user.AuthProvider;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Applies each /api route's rate-limit policy; over the limit becomes a 429. vault:rate-limiting-deep-dive#lanes */
public class RateLimitInterceptor implements HandlerInterceptor {

    /** An at-once refusal clears as soon as any in-flight call finishes. */
    private static final Duration IN_FLIGHT_RETRY = Duration.ofSeconds(1);
    private static final String SLOT_ATTRIBUTE = RateLimitInterceptor.class.getName() + ".slot";

    private record Slot(InFlightLimiter limiter, String key) {
    }

    private final RateLimiter rateLimiter;
    private final Map<RateLimitPolicy, InFlightLimiter> inFlight = new EnumMap<>(RateLimitPolicy.class);

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            if (policy.inFlightPerKey() > 0 || policy.inFlightTotal() > 0) {
                inFlight.put(policy, new InFlightLimiter(policy.inFlightPerKey(), policy.inFlightTotal()));
            }
        }
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RateLimitPolicy policy = policyFor(handler);
        AuthedUser user = currentUser();
        String key = policy.scope() == Scope.USER && user != null
                ? "u" + user.id()
                : "n" + ClientIp.networkKey(request);
        // provider comes from the users row on every request, so a guest can't claim a higher tier.
        Tier tier = user == null || AuthProvider.GUEST.name().equals(user.provider()) ? Tier.GUEST : Tier.ACCOUNT;

        InFlightLimiter limiter = inFlight.get(policy);
        if (limiter != null) {
            if (!limiter.tryAcquire(key)) {
                throw new RateLimitExceededException(IN_FLIGHT_RETRY);
            }
            request.setAttribute(SLOT_ATTRIBUTE, new Slot(limiter, key));
        }

        RateLimiter.Decision decision = rateLimiter.tryConsume(policy, tier, key);
        if (!decision.allowed()) {
            releaseSlot(request);
            throw new RateLimitExceededException(decision.retryAfter());
        }
        return true;
    }

    /** Runs after the handler returns or throws, so a slot can't leak. */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        releaseSlot(request);
    }

    private static void releaseSlot(HttpServletRequest request) {
        if (request.getAttribute(SLOT_ATTRIBUTE) instanceof Slot slot) {
            request.removeAttribute(SLOT_ATTRIBUTE);
            slot.limiter().release(slot.key());
        }
    }

    static RateLimitPolicy policyFor(Object handler) {
        if (handler instanceof HandlerMethod method) {
            RateLimited annotation = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RateLimited.class);
            if (annotation == null) {
                annotation = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RateLimited.class);
            }
            if (annotation != null) {
                return annotation.value();
            }
        }
        return RateLimitPolicy.DEFAULT;
    }

    private static AuthedUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthedUser user ? user : null;
    }
}
