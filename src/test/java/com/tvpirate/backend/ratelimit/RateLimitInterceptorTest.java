package com.tvpirate.backend.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import com.jayway.jsonpath.JsonPath;
import com.tvpirate.backend.api.GlobalExceptionHandler;
import com.tvpirate.backend.security.AuthedUser;

/** The interceptor end to end through MockMvc and the real exception handler:
 * what a caller over the limit actually receives. */
class RateLimitInterceptorTest {

    private MockMvc mockMvc;
    private RateLimitInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new RateLimitInterceptor(new RateLimiter(new FakeClock()));
        mockMvc = MockMvcBuilders.standaloneSetup(new PlainController(), new TmdbLikeController())
                .addInterceptors(interceptor)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        signIn(1L, "GUEST");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void overTheLimitIsA429ProblemWithRetryAfter() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(status(get("/api/plain"))).isEqualTo(200);
        }

        MvcResult result = mockMvc.perform(get("/api/plain")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
        assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("2");
        assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.detail"))
                .isEqualTo("Too many requests — try again in 2 seconds.");
    }

    @Test
    void theMethodAnnotationBeatsTheClassOne() throws Exception {
        for (int i = 0; i < 30; i++) {
            assertThat(status(get("/api/tmdb-like/search"))).isEqualTo(200);
        }
        assertThat(status(get("/api/tmdb-like/search"))).isEqualTo(429);
        // The class-level TMDB policy is a separate, still-full bucket.
        assertThat(status(get("/api/tmdb-like/detail"))).isEqualTo(200);
    }

    @Test
    void policyResolutionFallsBackToDefault() throws Exception {
        assertThat(RateLimitInterceptor.policyFor(handler(PlainController.class, "plain")))
                .isEqualTo(RateLimitPolicy.DEFAULT);
        assertThat(RateLimitInterceptor.policyFor(handler(TmdbLikeController.class, "detail")))
                .isEqualTo(RateLimitPolicy.TMDB);
        assertThat(RateLimitInterceptor.policyFor(handler(TmdbLikeController.class, "search")))
                .isEqualTo(RateLimitPolicy.TMDB_SEARCH);
        assertThat(RateLimitInterceptor.policyFor(new Object())).isEqualTo(RateLimitPolicy.DEFAULT);
    }

    @Test
    void usersHaveTheirOwnBuckets() throws Exception {
        for (int i = 0; i < 30; i++) {
            status(get("/api/plain"));
        }
        assertThat(status(get("/api/plain"))).isEqualTo(429);

        signIn(2L, "GUEST");
        assertThat(status(get("/api/plain"))).isEqualTo(200);
    }

    @Test
    void anAccountIsNotHeldToTheGuestLimit() throws Exception {
        signIn(3L, "GOOGLE");
        for (int i = 0; i < 31; i++) {
            assertThat(status(get("/api/plain"))).isEqualTo(200);
        }
    }

    @Test
    void theAtOnceSlotIsReleasedOnCompletion() throws Exception {
        // STREAM_RESOLVE allows 4 in flight; a leaked slot would 429 the fifth.
        for (int i = 0; i < 6; i++) {
            assertThat(status(get("/api/resolve"))).isEqualTo(200);
        }
    }

    @Test
    void theAtOnceSlotIsReleasedWhenTheHandlerThrows() throws Exception {
        for (int i = 0; i < 6; i++) {
            assertThat(status(get("/api/resolve-fails"))).isEqualTo(500);
        }
    }

    @Test
    void aFifthConcurrentResolveIsRefused() throws Exception {
        Object handler = handler(PlainController.class, "resolve");
        for (int i = 0; i < 4; i++) {
            assertThat(interceptor.preHandle(request(), new MockHttpServletResponse(), handler)).isTrue();
        }

        assertThatThrownBy(() -> interceptor.preHandle(request(), new MockHttpServletResponse(), handler))
                .isInstanceOfSatisfying(RateLimitExceededException.class,
                        ex -> assertThat(ex.getRetryAfterSeconds()).isEqualTo(1));
    }

    @Test
    void theWaitReadsInSecondsOrMinutes() {
        assertThat(RateLimitExceededException.humanize(1)).isEqualTo("1 second");
        assertThat(RateLimitExceededException.humanize(59)).isEqualTo("59 seconds");
        assertThat(RateLimitExceededException.humanize(60)).isEqualTo("1 minute");
        assertThat(RateLimitExceededException.humanize(1200)).isEqualTo("20 minutes");
        assertThat(RateLimitExceededException.humanize(1201)).isEqualTo("21 minutes");
    }

    // --- helpers ---

    private int status(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resolve");
        request.setRemoteAddr("203.0.113.7");
        return request;
    }

    private static void signIn(long id, String provider) {
        AuthedUser user = new AuthedUser(id, "user-" + id, provider, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    private static HandlerMethod handler(Class<?> type, String method) throws Exception {
        return new HandlerMethod(type.getDeclaredConstructor().newInstance(), type.getDeclaredMethod(method));
    }

    @RestController
    static class PlainController {

        @GetMapping("/api/plain")
        String plain() {
            return "ok";
        }

        @GetMapping("/api/resolve")
        @RateLimited(RateLimitPolicy.STREAM_RESOLVE)
        String resolve() {
            return "ok";
        }

        @GetMapping("/api/resolve-fails")
        @RateLimited(RateLimitPolicy.STREAM_RESOLVE)
        List<String> resolveFails() {
            throw new IllegalStateException("provider down");
        }
    }

    @RestController
    @RateLimited(RateLimitPolicy.TMDB)
    static class TmdbLikeController {

        @GetMapping("/api/tmdb-like/detail")
        String detail() {
            return "ok";
        }

        @GetMapping("/api/tmdb-like/search")
        @RateLimited(RateLimitPolicy.TMDB_SEARCH)
        String search() {
            return "ok";
        }
    }
}
