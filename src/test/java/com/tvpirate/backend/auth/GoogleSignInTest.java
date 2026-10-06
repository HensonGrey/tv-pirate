package com.tvpirate.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.util.List;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import com.tvpirate.backend.auth.dto.AuthResponse;
import com.tvpirate.backend.auth.dto.GoogleProfile;
import com.tvpirate.backend.auth.dto.UserDto;

import jakarta.servlet.http.Cookie;

/** Both redirect endpoints through MockMvc, with the real Google client talking to a
 * fake Google that fails the test on any request it wasn't told to expect. */
class GoogleSignInTest {

    private static final String FRONTEND = "http://frontend.test";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";
    private static final String PROFILE_JSON = """
            {"sub":"g-123","email":"someone@example.com","email_verified":true,"name":"Someone","picture":"https://pic"}""";

    private MockRestServiceServer google;
    private FakeAuthService authService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        google = MockRestServiceServer.bindTo(builder).build();
        authService = new FakeAuthService();
        mockMvc = mvcFor(new GoogleOAuthClient(builder.build(), "client-id", "client-secret",
                "http://api.test/api/auth/google/callback"));
    }

    @Test
    void startSendsTheBrowserToGoogleWithTheStateItAlsoSetsAsACookie() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/auth/google")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(302);
        String stateCookie = setCookie(response, "oauth_state");
        assertThat(stateCookie).contains("HttpOnly").contains("Path=/api/auth/google").contains("SameSite=Lax");
        String state = stateCookie.substring("oauth_state=".length(), stateCookie.indexOf(';'));
        assertThat(response.getRedirectedUrl())
                .startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("client_id=client-id", "response_type=code", "scope=openid%20email%20profile",
                        "state=" + state);
    }

    @Test
    void startWithoutCredentialsBouncesBackAsUnavailable() throws Exception {
        MockMvc unconfigured = mvcFor(new GoogleOAuthClient(RestClient.create(), "", "", "http://unused"));

        MockHttpServletResponse response = unconfigured.perform(get("/api/auth/google")).andReturn().getResponse();

        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?signInError=unavailable");
    }

    @Test
    void aMatchingCallbackSignsTheUserInAndLandsOnTheFrontend() throws Exception {
        google.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formDataContains(Map.of("code", "the-code", "client_secret", "client-secret",
                        "grant_type", "authorization_code")))
                .andRespond(withSuccess("{\"access_token\":\"google-at\"}", MediaType.APPLICATION_JSON));
        google.expect(requestTo(USERINFO_URL))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer google-at"))
                .andRespond(withSuccess(PROFILE_JSON, MediaType.APPLICATION_JSON));

        MockHttpServletResponse response = mockMvc.perform(callback("the-code", "s1", "s1")).andReturn().getResponse();

        google.verify();
        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/");
        assertThat(authService.signedIn).isEqualTo(
                new GoogleProfile("g-123", "someone@example.com", true, "Someone", "https://pic"));
        assertThat(setCookie(response, "access_token")).startsWith("access_token=access;");
        assertThat(setCookie(response, "refresh_token")).startsWith("refresh_token=refresh;");
        assertThat(setCookie(response, "oauth_state")).contains("Max-Age=0");
    }

    @Test
    void aStateThatDoesNotMatchTheCookieIsRefusedWithoutAskingGoogle() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(callback("the-code", "forged", "s1")).andReturn().getResponse();

        assertRefused(response, "failed");
    }

    @Test
    void aCallbackWithNoStateCookieIsRefused() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(callback("the-code", "s1", null)).andReturn().getResponse();

        assertRefused(response, "failed");
    }

    @Test
    void backingOutOnTheConsentScreenReadsAsCancelled() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/auth/google/callback")
                .param("error", "access_denied").param("state", "s1").cookie(new Cookie("oauth_state", "s1")))
                .andReturn().getResponse();

        assertRefused(response, "cancelled");
    }

    @Test
    void aCodeGoogleRejectsReadsAsFailed() throws Exception {
        google.expect(requestTo(TOKEN_URL)).andRespond(withBadRequest());

        MockHttpServletResponse response = mockMvc.perform(callback("stale-code", "s1", "s1")).andReturn().getResponse();

        assertRefused(response, "failed");
    }

    private void assertRefused(MockHttpServletResponse response, String error) {
        google.verify();
        assertThat(response.getRedirectedUrl()).isEqualTo(FRONTEND + "/login?signInError=" + error);
        assertThat(authService.signedIn).isNull();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(c -> c.startsWith("access_token="));
    }

    private MockMvc mvcFor(GoogleOAuthClient client) {
        return MockMvcBuilders.standaloneSetup(new AuthController(authService, client,
                new TurnstileClient(RestClient.create(), ""), false, FRONTEND)).build();
    }

    private static MockHttpServletRequestBuilder callback(String code, String state, String cookieState) {
        MockHttpServletRequestBuilder request = get("/api/auth/google/callback").param("code", code).param("state", state);
        return cookieState == null ? request : request.cookie(new Cookie("oauth_state", cookieState));
    }

    private static String setCookie(MockHttpServletResponse response, String name) {
        List<String> matches = response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(c -> c.startsWith(name + "="))
                .toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    /** Records who signed in instead of touching a database. */
    private static class FakeAuthService extends AuthService {

        GoogleProfile signedIn;

        FakeAuthService() {
            super(null, null, null);
        }

        @Override
        public AuthResponse loginWithGoogle(GoogleProfile profile) {
            signedIn = profile;
            return new AuthResponse("access", "refresh", new UserDto(1L, "Someone", "GOOGLE", "https://pic"));
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
