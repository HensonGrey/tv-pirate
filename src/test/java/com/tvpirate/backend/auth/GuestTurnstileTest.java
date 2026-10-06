package com.tvpirate.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import com.tvpirate.backend.api.GlobalExceptionHandler;
import com.tvpirate.backend.auth.dto.AuthResponse;
import com.tvpirate.backend.auth.dto.UserDto;
import com.tvpirate.backend.ratelimit.RateLimitInterceptor;
import com.tvpirate.backend.ratelimit.RateLimitPolicy;
import com.tvpirate.backend.ratelimit.RateLimiter;

import io.github.bucket4j.TimeMeter;

/** The guest endpoint through MockMvc, with the real Turnstile client talking to a
 * fake Cloudflare that fails the test on any request it wasn't told to expect. */
class GuestTurnstileTest {

    private static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private MockRestServiceServer cloudflare;
    private RestClient restClient;
    private FakeAuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        cloudflare = MockRestServiceServer.bindTo(builder).build();
        restClient = builder.build();
        authService = new FakeAuthService();
        mockMvc = mvcFor(new TurnstileClient(restClient, "the-secret"));
    }

    @Test
    void aTokenCloudflareVouchesForCreatesTheGuest() throws Exception {
        cloudflare.expect(requestTo(SITEVERIFY_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("secret", "the-secret", "response", "good-token",
                        "remoteip", "203.0.113.7")))
                .andRespond(withSuccess("{\"success\":true,\"hostname\":\"localhost\"}", MediaType.APPLICATION_JSON));

        MockHttpServletResponse response = mockMvc.perform(guest("good-token")).andReturn().getResponse();

        cloudflare.verify();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(authService.guestsCreated).isEqualTo(1);
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).anyMatch(c -> c.startsWith("access_token=access;"));
    }

    @Test
    void aTokenCloudflareRejectsIsRefusedAsABadToken() throws Exception {
        cloudflareRefusesWith("invalid-input-response");

        assertRefused(mockMvc.perform(guest("bad-token")).andReturn().getResponse(), TurnstileFailureEnum.BAD_TOKEN);
    }

    @Test
    void aWrongSecretIsReportedAsMisconfigured() throws Exception {
        cloudflareRefusesWith("invalid-input-secret");

        assertRefused(mockMvc.perform(guest("good-token")).andReturn().getResponse(), TurnstileFailureEnum.BAD_SECRET);
    }

    @Test
    void aReusedTokenIsReportedAsExpired() throws Exception {
        cloudflareRefusesWith("timeout-or-duplicate");

        assertRefused(mockMvc.perform(guest("used-token")).andReturn().getResponse(), TurnstileFailureEnum.EXPIRED);
    }

    @Test
    void anUnknownErrorCodeIsACloudflareError() throws Exception {
        cloudflareRefusesWith("internal-error");

        assertRefused(mockMvc.perform(guest("good-token")).andReturn().getResponse(),
                TurnstileFailureEnum.CLOUDFLARE_ERROR);
    }

    @Test
    void noTokenIsRefusedWithoutAskingCloudflare() throws Exception {
        assertRefused(mockMvc.perform(post("/api/auth/guest").with(fromIp())).andReturn().getResponse(),
                TurnstileFailureEnum.NO_TOKEN);
    }

    @Test
    void cloudflareBeingDownFailsClosed() throws Exception {
        cloudflare.expect(requestTo(SITEVERIFY_URL)).andRespond(withServerError());

        assertRefused(mockMvc.perform(guest("good-token")).andReturn().getResponse(), TurnstileFailureEnum.UNREACHABLE);
    }

    @Test
    void withoutASecretTheCheckIsSkipped() throws Exception {
        MockMvc unconfigured = mvcFor(new TurnstileClient(RestClient.create(), ""));

        MockHttpServletResponse response = unconfigured.perform(post("/api/auth/guest")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(authService.guestsCreated).isEqualTo(1);
    }

    @Test
    void refusedRequestsFromManyNetworksDontSpendTheSiteWideAllowance() throws Exception {
        MockMvc limited = mvcWithRateLimits();
        for (int network = 0; network < 70; network++) {
            assertThat(limited.perform(post("/api/auth/guest").with(fromIp("10.0.0." + network)))
                    .andReturn().getResponse().getStatus()).isEqualTo(403);
        }
        cloudflare.expect(requestTo(SITEVERIFY_URL)).andRespond(cloudflarePasses());

        assertThat(limited.perform(guest("good-token")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(authService.guestsCreated).isEqualTo(1);
    }

    @Test
    void verifiedGuestsStillSpendTheSiteWideAllowance() throws Exception {
        MockMvc limited = mvcWithRateLimits();
        int siteWide = (int) RateLimitPolicy.GUEST_CREATE.global().capacity();
        cloudflare.expect(ExpectedCount.times(siteWide + 1), requestTo(SITEVERIFY_URL)).andRespond(cloudflarePasses());
        for (int network = 0; network < siteWide; network++) {
            assertThat(limited.perform(guest("good-token", "10.0.0." + network))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
        }

        assertThat(limited.perform(guest("good-token")).andReturn().getResponse().getStatus()).isEqualTo(429);
        cloudflare.verify();
        assertThat(authService.guestsCreated).isEqualTo(siteWide);
    }

    private MockMvc mvcWithRateLimits() {
        AuthController controller = new AuthController(authService,
                new GoogleOAuthClient(RestClient.create(), "", "", "http://unused"),
                new TurnstileClient(restClient, "the-secret"), false, "http://frontend.test");
        return MockMvcBuilders.standaloneSetup(controller)
                .addInterceptors(new RateLimitInterceptor(new RateLimiter(TimeMeter.SYSTEM_NANOTIME)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static ResponseCreator cloudflarePasses() {
        return withSuccess("{\"success\":true,\"hostname\":\"localhost\"}", MediaType.APPLICATION_JSON);
    }

    private void cloudflareRefusesWith(String errorCode) {
        cloudflare.expect(requestTo(SITEVERIFY_URL))
                .andRespond(withSuccess("{\"success\":false,\"error-codes\":[\"" + errorCode + "\"]}",
                        MediaType.APPLICATION_JSON));
    }

    private void assertRefused(MockHttpServletResponse response, TurnstileFailureEnum failure) {
        cloudflare.verify();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getErrorMessage()).isEqualTo(failure.message());
        assertThat(authService.guestsCreated).isZero();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    private MockMvc mvcFor(TurnstileClient turnstile) {
        AuthController controller = new AuthController(authService,
                new GoogleOAuthClient(RestClient.create(), "", "", "http://unused"), turnstile, false, "http://frontend.test");
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static MockHttpServletRequestBuilder guest(String token) {
        return guest(token, "203.0.113.7");
    }

    private static MockHttpServletRequestBuilder guest(String token, String ip) {
        return post("/api/auth/guest")
                .with(fromIp(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"turnstileToken\":\"" + token + "\"}");
    }

    private static RequestPostProcessor fromIp() {
        return fromIp("203.0.113.7");
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    /** Counts guests instead of touching a database. */
    private static class FakeAuthService extends AuthService {

        int guestsCreated;

        FakeAuthService() {
            super(null, null, null);
        }

        @Override
        public AuthResponse loginAsGuest() {
            guestsCreated++;
            return new AuthResponse("access", "refresh", new UserDto(1L, "Guest", "GUEST", null));
        }

        @Override
        public Duration getAccessTtl() {
            return Duration.ofMinutes(15);
        }

        @Override
        public Duration getRefreshTtl() {
            return Duration.ofDays(30);
        }
    }
}
