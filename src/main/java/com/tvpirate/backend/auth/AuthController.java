package com.tvpirate.backend.auth;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.auth.dto.AuthResponse;
import com.tvpirate.backend.auth.dto.UserDto;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Public auth endpoints (whitelisted in SecurityConfig). A future provider
 * login (Google, ...) will add its endpoints here too.
 *
 * Tokens travel as httpOnly cookies instead of a JSON body:
 * <ul>
 * <li><b>access_token</b> — Path=/, sent with every request, read by the
 *     JwtAuthenticationFilter.
 * <li><b>refresh_token</b> — Path=/api/auth, so the browser only ever sends
 *     it to the auth endpoints.
 * </ul>
 * httpOnly means JS can't read them (XSS can't steal them) — but it also
 * means only the server can delete them, which is why logout is an endpoint.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final String ACCESS_COOKIE = "access_token";
    private static final String REFRESH_COOKIE = "refresh_token";
    private static final String REFRESH_PATH = "/api/auth";

    private final AuthService authService;
    private final boolean cookieSecure;

    public AuthController(AuthService authService,
                          @Value("${app.cookie.secure:false}") boolean cookieSecure) {
        this.authService = authService;
        this.cookieSecure = cookieSecure;
    }

    // SECURITY NOTE: creating a guest account costs a DB row + token pair and
    // requires no credentials, so this endpoint is a potential DoS weak spot
    // (unauthenticated callers can bloat users/refresh_tokens). REVISIT when
    // rate limiting is added.
    @PostMapping("/guest")
    public UserDto guest(HttpServletResponse response) {
        AuthResponse auth = authService.loginAsGuest();
        setAuthCookies(response, auth);
        return auth.user();
    }

    /**
     * The refresh token arrives as a cookie the browser adds automatically —
     * the client just POSTs here with an empty body. Rotation is unchanged:
     * the old token is burned and a new pair comes back in cookies.
     */
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
