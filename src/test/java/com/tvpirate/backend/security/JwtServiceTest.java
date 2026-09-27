package com.tvpirate.backend.security;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.tvpirate.backend.user.AuthProvider;
import com.tvpirate.backend.user.UserEntity;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;

/**
 * The two-token scheme. An access token is trusted on its signature alone —
 * no DB lookup — so "we issued this, unmodified, and it has not expired" is
 * the whole contract. A refresh token is deliberately not a JWT.
 * vault:auth-deep-dive#tokens
 */
class JwtServiceTest {

    /** HS256 needs >= 32 bytes of key material; both of these clear it. */
    private static final String SECRET = "test-only-secret-not-used-for-anything-real";
    private static final String OTHER_SECRET = "a-completely-different-secret-of-its-own";

    private static final long ACCESS_MINUTES = 15;
    private static final long REFRESH_DAYS = 30;

    private final JwtService jwtService = new JwtService(SECRET, ACCESS_MINUTES, REFRESH_DAYS);

    // --- access tokens: the round trip ---

    @Test
    void anIssuedTokenParsesBackToTheUserItWasIssuedFor() {
        UserEntity user = user(7L, "guest-ab12cd", AuthProvider.GUEST);

        String subject = jwtService.extractUserId(jwtService.generateAccessToken(user));

        // The subject is the user id, not the username. vault:auth-deep-dive#user-loading
        assertThat(subject).isEqualTo("7");
    }

    @Test
    void theTokenCarriesTheUsernameAndProviderClaims() {
        UserEntity user = user(7L, "guest-ab12cd", AuthProvider.GUEST);

        Claims claims = parseWith(SECRET, jwtService.generateAccessToken(user));

        assertThat(claims.getSubject()).isEqualTo("7");
        assertThat(claims.get("username")).isEqualTo("guest-ab12cd");
        assertThat(claims.get("provider")).isEqualTo("GUEST");
    }

    @Test
    void theExpiryIsTheConfiguredAccessLifetimeAfterIssue() {
        Claims claims = parseWith(SECRET, jwtService.generateAccessToken(user(7L, "guest-ab12cd", AuthProvider.GUEST)));

        long livesForSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertThat(livesForSeconds).isEqualTo(Duration.ofMinutes(ACCESS_MINUTES).toSeconds());
    }

    // --- access tokens: what must be rejected ---

    @Test
    void anExpiredAccessTokenIsRejected() {
        // Lifetime comes from the constructor, so the token is born expired
        // and nothing has to sleep.
        String stale = new JwtService(SECRET, -1, REFRESH_DAYS)
                .generateAccessToken(user(7L, "guest-ab12cd", AuthProvider.GUEST));

        assertThatThrownBy(() -> jwtService.extractUserId(stale))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void aTokenSignedWithADifferentSecretIsRejected() {
        String forged = new JwtService(OTHER_SECRET, ACCESS_MINUTES, REFRESH_DAYS)
                .generateAccessToken(user(7L, "guest-ab12cd", AuthProvider.GUEST));

        assertThatThrownBy(() -> jwtService.extractUserId(forged))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void aTamperedPayloadIsRejected() {
        // Privilege escalation attempt: keep our signature, swap the subject
        // so the token names someone else's account.
        String token = jwtService.generateAccessToken(user(7L, "guest-ab12cd", AuthProvider.GUEST));
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), UTF_8);
        assertThat(payload).contains("\"sub\":\"7\"");

        String forged = parts[0] + "." + base64Url(payload.replace("\"sub\":\"7\"", "\"sub\":\"1\"")) + "." + parts[2];

        assertThat(forged).isNotEqualTo(token);
        assertThatThrownBy(() -> jwtService.extractUserId(forged))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void garbageIsRejectedRatherThanParsedLeniently() {
        assertThatThrownBy(() -> jwtService.extractUserId("not-a-token")).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jwtService.extractUserId("")).isInstanceOf(Exception.class);
    }

    // --- refresh tokens: random, not signed ---

    @Test
    void aRefreshTokenIsRandomBytesRatherThanAJwt() {
        String refreshToken = jwtService.generateRefreshToken();

        // 48 bytes, url-safe base64, no padding — and no JWT dots to parse.
        assertThat(Base64.getUrlDecoder().decode(refreshToken)).hasSize(48);
        assertThat(refreshToken).doesNotContain(".").doesNotContain("=").doesNotContain("+").doesNotContain("/");
    }

    @Test
    void everyRefreshTokenIsDistinct() {
        assertThat(Stream.generate(jwtService::generateRefreshToken).limit(200).distinct().count()).isEqualTo(200);
    }

    // --- lifetimes the cookies are built from ---

    @Test
    void theLifetimesComeFromTheConfiguredValues() {
        // AuthController turns these straight into cookie Max-Age.
        assertThat(jwtService.getAccessTtl()).isEqualTo(Duration.ofMinutes(ACCESS_MINUTES));
        assertThat(jwtService.getRefreshTtl()).isEqualTo(Duration.ofDays(REFRESH_DAYS));
    }

    // --- helpers ---

    private static Claims parseWith(String secret, String token) {
        return Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(UTF_8));
    }

    /** The id is DB-generated in production, so tests stamp one in directly. */
    private static UserEntity user(Long id, String username, AuthProvider provider) {
        UserEntity user = new UserEntity(username, null, provider);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
