package com.tvpirate.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.tvpirate.backend.api.GlobalExceptionHandler;
import com.tvpirate.backend.ratelimit.RateLimitInterceptor;
import com.tvpirate.backend.ratelimit.RateLimitPolicy;
import com.tvpirate.backend.ratelimit.RateLimitPolicy.Tier;
import com.tvpirate.backend.ratelimit.RateLimiter;
import com.tvpirate.backend.security.AuthedUser;
import com.tvpirate.backend.stream.StreamProvider.ResolveRequest;
import com.tvpirate.backend.stream.StreamProvider.StreamSource;

import io.github.bucket4j.TimeMeter;

/** The resolve endpoint behind the real rate limiter: only a resolve that reaches
 * a provider spends the site-wide allowance, so cached repeats can't drain it. */
class StreamSourcesRateLimitTest {

    private static final int SITE_WIDE = (int) RateLimitPolicy.STREAM_RESOLVE.global().capacity();
    private static final int PER_GUEST = (int) RateLimitPolicy.STREAM_RESOLVE.limits(Tier.GUEST).get(0).capacity();

    /** Stopped, so no bucket refills mid-test. */
    private static final TimeMeter FROZEN = new TimeMeter() {
        @Override
        public long currentTimeNanos() {
            return 1_000_000_000L;
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    };

    private final AtomicInteger providerCalls = new AtomicInteger();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // No sources, so no proxy ticket is minted; an empty answer is cached like any other.
        StreamProvider provider = new StreamProvider() {
            @Override
            public String name() {
                return "vixsrc";
            }

            @Override
            public List<StreamSource> resolve(ResolveRequest request) {
                providerCalls.incrementAndGet();
                return List.of();
            }
        };
        StreamController controller = new StreamController(new StreamService(List.of(provider)),
                new StreamProxyService(new PublicTargetGuard()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new RateLimitInterceptor(new RateLimiter(FROZEN)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void cachedRepeatsFromManyGuestsDontSpendTheSiteWideAllowance() throws Exception {
        int guests = SITE_WIDE / PER_GUEST + 2;
        for (long guest = 0; guest < guests; guest++) {
            for (int i = 0; i < PER_GUEST; i++) {
                assertThat(resolve(guest, 550)).isEqualTo(200);
            }
        }

        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void resolvesThatReachTheProviderStillHitTheSiteWideCap() throws Exception {
        for (int title = 0; title < SITE_WIDE; title++) {
            assertThat(resolve(title / PER_GUEST, 1000 + title)).isEqualTo(200);
        }

        assertThat(resolve(999, 9999)).isEqualTo(429);
        assertThat(providerCalls).hasValue(SITE_WIDE);
    }

    private int resolve(long guestId, long tmdbId) throws Exception {
        AuthedUser user = new AuthedUser(guestId, "guest-" + guestId, "GUEST", null);
        var authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return mockMvc.perform(get("/api/stream/sources")
                        .param("provider", "vixsrc")
                        .param("type", "movie")
                        .param("tmdbId", String.valueOf(tmdbId))
                        .principal(authentication))
                .andReturn().getResponse().getStatus();
    }
}
