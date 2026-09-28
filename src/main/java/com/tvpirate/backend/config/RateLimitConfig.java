package com.tvpirate.backend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.tvpirate.backend.ratelimit.ProxyConcurrencyInterceptor;
import com.tvpirate.backend.ratelimit.RateLimitInterceptor;
import com.tvpirate.backend.ratelimit.RateLimiter;
import com.tvpirate.backend.stream.StreamProxyService;

import io.github.bucket4j.TimeMeter;

/** Puts the API lane's rate limits on /api/**. The playback proxy is excluded:
 * its chunk traffic is capped in flight, never rate limited. Off entirely
 * with RATE_LIMIT_ENABLED=false. vault:rate-limiting-deep-dive#lanes */
@Configuration
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitConfig implements WebMvcConfigurer {

    private final StreamProxyService proxyService;

    public RateLimitConfig(StreamProxyService proxyService) {
        this.proxyService = proxyService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(new RateLimiter(TimeMeter.SYSTEM_NANOTIME)))
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/stream/proxy/**");
        registry.addInterceptor(new ProxyConcurrencyInterceptor(proxyService))
                .addPathPatterns("/api/stream/proxy/**");
    }
}
