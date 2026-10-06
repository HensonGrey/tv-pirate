package com.tvpirate.backend.auth;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.auth.dto.AuthResponse;
import com.tvpirate.backend.auth.dto.GoogleProfile;
import com.tvpirate.backend.auth.dto.GuestRequest;
import com.tvpirate.backend.auth.dto.UserDto;
import com.tvpirate.backend.ratelimit.RateLimitPolicy;
import com.tvpirate.backend.ratelimit.RateLimited;
import com.tvpirate.backend.security.AuthedUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Public auth endpoints (whitelisted in SecurityConfig); future provider
 * logins add their endpoints here. Tokens travel as httpOnly cookies:
 * access on Path=/, refresh only on Path=/api/auth — httpOnly keeps JS from
 * reading them, which is why logout is an endpoint. vault:auth-deep-dive#cookies */
@RestController
@RequestMapping("/api/auth")
@RateLimited(RateLimitPolicy.AUTH_SESSION)
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private static final String ACCESS_COOKIE = "access_token";
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_PATH = "/api/auth";
    private static final String STATE_COOKIE = "oauth_state";
    private static final String GOOGLE_PATH = "/api/auth/google";
    private static final Duration STATE_TTL = Duration.ofMinutes(10);

    private final AuthService authService;
    private final GoogleOAuthClient googleOAuthClient;
    private final TurnstileClient turnstileClient;
    private final boolean cookieSecure;
    private final String frontendUrl;
    private final SecureRandom random = new SecureRandom();

    public AuthController(AuthService authService,
                          GoogleOAuthClient googleOAuthClient,
                          TurnstileClient turnstileClient,
                          @Value("${app.cookie.secure:false}") boolean cookieSecure,
                          @Value("${app.frontend-url:http://localhost:5173}") String frontendUrl) {
        this.authService = authService;
        this.googleOAuthClient = googleOAuthClient;
        this.turnstileClient = turnstileClient;
        this.cookieSecure = cookieSecure;
        this.frontendUrl = frontendUrl;
    }

    // A DB row + token pair with no credentials, so the strictest limit. vault:rate-limiting-deep-dive#policies
    @PostMapping("/guest")
    @RateLimited(RateLimitPolicy.GUEST_CREATE)
    public UserDto guest(@RequestBody(required = false) GuestRequest body, Authentication authentication,
                         HttpServletRequest request, HttpServletResponse response) {
        // Already signed in: hand back the current session instead of piling
        // up a fresh user + refresh-token row on every double-click or retry.
        if (authentication != null && authentication.getPrincipal() instanceof AuthedUser existing) {
            return new UserDto(existing.id(), existing.username(), existing.provider(), existing.profilePictureUrl());
        }
        String token = body == null ? null : body.turnstileToken();
        if (turnstileClient.isConfigured() && !turnstileClient.verify(token, request.getRemoteAddr())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The bot check didn't pass. Please try again.");
        }
        AuthResponse auth = authService.loginAsGuest();
        setAuthCookies(response, auth);
        return auth.user();
    }

    /** A full-page navigation, not an XHR: the browser goes to Google's consent screen
     * carrying a state that only this browser also holds in a cookie. */
    @GetMapping("/google")
    public ResponseEntity<Void> startGoogleSignIn(HttpServletResponse response) {
        if (!googleOAuthClient.isConfigured()) {
            return redirectToLogin(SignInErrorEnum.UNAVAILABLE);
        }
        String state = generateState();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(STATE_COOKIE, state, GOOGLE_PATH, STATE_TTL).toString());
        return redirectTo(googleOAuthClient.buildAuthorizationUrl(state));
    }

    /** Google sends the browser back here. A state that doesn't match the cookie means
     * another site started this sign-in (login CSRF), so it's refused. */
    @GetMapping("/google/callback")
    public ResponseEntity<Void> finishGoogleSignIn(@RequestParam(required = false) String code,
                                                   @RequestParam(required = false) String state,
                                                   @RequestParam(required = false) String error,
                                                   @CookieValue(name = STATE_COOKIE, required = false) String expectedState,
                                                   HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(STATE_COOKIE, "", GOOGLE_PATH, Duration.ZERO).toString());
        if (error != null) {
            return redirectToLogin("access_denied".equals(error) ? SignInErrorEnum.CANCELLED : SignInErrorEnum.FAILED);
        }
        if (code == null || !stateMatches(expectedState, state)) {
            return redirectToLogin(SignInErrorEnum.FAILED);
        }
        GoogleProfile profile;
        try {
            profile = googleOAuthClient.fetchProfileForCode(code);
        } catch (RestClientException e) {
            // A wrong secret or redirect URI shows up here as Google's 400/401.
            log.warn("Google refused the sign-in code: {}", e.getMessage());
            return redirectToLogin(SignInErrorEnum.FAILED);
        }
        setAuthCookies(response, authService.loginWithGoogle(profile));
        return redirectTo(frontendUrl + "/");
    }

    /** The refresh cookie arrives automatically — the client POSTs with an
     * empty body and the new pair comes back in cookies. */
    @PostMapping("/refresh")
    public UserDto refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                           HttpServletResponse response) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing refresh token cookie");
        }
        AuthResponse auth = authService.refresh(refreshToken);
        setAuthCookies(response, auth);
        return auth.user();
    }

    /** Burns the refresh token in the DB and expires both cookies client-side. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                       HttpServletResponse response) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            authService.logout(refreshToken);
        }
        expireAuthCookies(response);
        return ResponseEntity.noContent().build();
    }

    /** Here rather than under /api/me because it must expire the same cookies logout does. */
    @DeleteMapping("/account")
    public ResponseEntity<Void> deleteAccount(Authentication authentication, HttpServletResponse response) {
        // /api/auth/** is permitAll, so the sign-in check is ours to make.
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthedUser user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not signed in");
        }
        authService.deleteAccount(user.id());
        expireAuthCookies(response);
        return ResponseEntity.noContent().build();
    }

    private String generateState() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean stateMatches(String expected, String actual) {
        return expected != null && actual != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private ResponseEntity<Void> redirectToLogin(SignInErrorEnum error) {
        return redirectTo(frontendUrl + "/login?signInError=" + error.queryValue());
    }

    private static ResponseEntity<Void> redirectTo(String url) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    private void setAuthCookies(HttpServletResponse response, AuthResponse auth) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie(ACCESS_COOKIE, auth.accessToken(), "/", authService.getAccessTtl()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                cookie(REFRESH_COOKIE, auth.refreshToken(), REFRESH_PATH, authService.getRefreshTtl()).toString());
    }

    private void expireAuthCookies(HttpServletResponse response) {
        // Max-Age=0 with the same name+path tells the browser to delete the cookie.
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(ACCESS_COOKIE, "", "/", Duration.ZERO).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(REFRESH_COOKIE, "", REFRESH_PATH, Duration.ZERO).toString());
    }

    private ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path(path)
                .maxAge(maxAge)
                .build();
    }
}
