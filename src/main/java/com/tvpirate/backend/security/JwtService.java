package com.tvpirate.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.tvpirate.backend.user.UserEntity;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Creates and validates the two kinds of tokens: a signed short-lived JWT
 * (access — self-contained, no DB lookup to trust it) and a long random
 * string (refresh — only its hash lives in the DB, enabling revocation).
 * vault:auth-deep-dive#tokens */
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

    public String generateAccessToken(UserEntity user) {
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
