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
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import com.tvpirate.backend.auth.dto.AuthResponse;
import com.tvpirate.backend.auth.dto.UserDto;

/** The guest endpoint through MockMvc, with the real Turnstile client talking to a
 * fake Cloudflare that fails the test on any request it wasn't told to expect. */
class GuestTurnstileTest {

    private static final String SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private MockRestServiceServer cloudflare;
    private FakeAuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        cloudflare = MockRestServiceServer.bindTo(builder).build();
        authService = new FakeAuthService();
        mockMvc = mvcFor(new TurnstileClient(builder.build(), "the-secret"));
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
    void aTokenCloudflareRejectsIsRefused() throws Exception {
        cloudflare.expect(requestTo(SITEVERIFY_URL))
                .andRespond(withSuccess("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}",
                        MediaType.APPLICATION_JSON));

        assertRefused(mockMvc.perform(guest("bad-token")).andReturn().getResponse());
    }

    @Test
    void noTokenIsRefusedWithoutAskingCloudflare() throws Exception {
        assertRefused(mockMvc.perform(post("/api/auth/guest").with(fromIp())).andReturn().getResponse());
    }

    @Test
    void cloudflareBeingDownFailsClosed() throws Exception {
        cloudflare.expect(requestTo(SITEVERIFY_URL)).andRespond(withServerError());

        assertRefused(mockMvc.perform(guest("good-token")).andReturn().getResponse());
    }

    @Test
    void withoutASecretTheCheckIsSkipped() throws Exception {
        MockMvc unconfigured = mvcFor(new TurnstileClient(RestClient.create(), ""));

        MockHttpServletResponse response = unconfigured.perform(post("/api/auth/guest")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(authService.guestsCreated).isEqualTo(1);
    }

    private void assertRefused(MockHttpServletResponse response) {
        cloudflare.verify();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(authService.guestsCreated).isZero();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    private MockMvc mvcFor(TurnstileClient turnstile) {
        AuthController controller = new AuthController(authService,
                new GoogleOAuthClient(RestClient.create(), "", "", "http://unused"), turnstile, false, "http://frontend.test");
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static MockHttpServletRequestBuilder guest(String token) {
        return post("/api/auth/guest")
                .with(fromIp())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"turnstileToken\":\"" + token + "\"}");
    }

    private static RequestPostProcessor fromIp() {
        return request -> {
            request.setRemoteAddr("203.0.113.7");
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
