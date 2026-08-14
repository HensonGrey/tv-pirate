package com.tvpirate.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tvpirate.backend.user.User;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Creates and validates the two kinds of tokens:
 *
 * <ul>
 * <li><b>Access token</b> — a signed JWT, short-lived (15 min). Sent with
 * every request as {@code Authorization: Bearer <token>}. Self-contained:
 * the signature proves we issued it, so no DB lookup is needed to trust it.
 * <li><b>Refresh token</b> — a long random string (not a JWT), long-lived
 * (30 days). Only used at {@code /api/auth/refresh} to get a new access
 * token. Its hash is stored in the DB, which is what makes revocation and
 * rotation possible.
 * </ul>
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.access-minutes}") long accessMinutes,
                      @Value("${jwt.refresh-days}") long refreshDays) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTtl = Duration.ofMinutes(accessMinutes);
        this.refreshTtl = Duration.ofDays(refreshDays);
    }

    public String generateAccessToken(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("provider", user.getProvider().name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + accessTtl.toMillis()))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Verifies the signature and expiry, returns the user id (the subject).
     * Throws if the token is invalid, expired, or tampered with.
     */
    public String extractUserId(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    /** A refresh token is just an unguessable random string — the DB row is the source of truth. */
    public String generateRefreshToken() {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Duration getRefreshTtl() {
        return refreshTtl;
    }

    public Duration getAccessTtl() {
        return accessTtl;
    }
}
