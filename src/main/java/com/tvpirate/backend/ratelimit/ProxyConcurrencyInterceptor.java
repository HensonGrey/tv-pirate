package com.tvpirate.backend.ratelimit;

import static com.tvpirate.backend.ratelimit.RateLimitPolicy.PROXY_IN_FLIGHT_PER_OWNER;
import static com.tvpirate.backend.ratelimit.RateLimitPolicy.PROXY_IN_FLIGHT_TOTAL;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.tvpirate.backend.stream.StreamProxyService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Caps how many proxy requests run at once, per ticket owner and in total —
 * never how often, since one title is thousands of chunk requests. Over a cap
 * is a bare 503: hls.js retries 5xx with backoff but treats any 4xx as fatal.
 * vault:rate-limiting-deep-dive#lanes
 */
public class ProxyConcurrencyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ProxyConcurrencyInterceptor.class);
    private static final String SLOT_ATTRIBUTE = ProxyConcurrencyInterceptor.class.getName() + ".slot";
    private static final String RETRY_AFTER_SECONDS = "2";

    /** key: "u" + owner id, or null for an unknown ticket (counts toward the total only). */
    private record Slot(String key) {
    }

    private final StreamProxyService proxyService;
    private final InFlightLimiter limiter = new InFlightLimiter(PROXY_IN_FLIGHT_PER_OWNER, PROXY_IN_FLIGHT_TOTAL);

    public ProxyConcurrencyInterceptor(StreamProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Long owner = proxyService.ownerOf(token(request));
        String key = owner == null ? null : "u" + owner;
        if (!limiter.tryAcquire(key)) {
            log.debug("proxy in-flight cap reached for {}", key == null ? "unknown ticket" : key);
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            return false;
        }
        request.setAttribute(SLOT_ATTRIBUTE, new Slot(key));
        return true;
    }

    /** Runs once the streamed body is fully written or the client aborted. */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(SLOT_ATTRIBUTE) instanceof Slot slot) {
            request.removeAttribute(SLOT_ATTRIBUTE);
            limiter.release(slot.key());
        }
    }

    private static String token(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map && map.get("token") instanceof String token ? token : "";
    }
}
