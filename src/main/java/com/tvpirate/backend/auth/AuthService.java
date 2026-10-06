package com.tvpirate.backend.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.auth.dto.AuthResponse;
import com.tvpirate.backend.auth.dto.GoogleProfile;
import com.tvpirate.backend.security.JwtService;
import com.tvpirate.backend.user.AuthProvider;
import com.tvpirate.backend.user.UserEntity;
import com.tvpirate.backend.user.UserRepository;

@Service
public class AuthService {

    private static final String GUEST_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       JwtService jwtService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
    }

    /** Creates a fresh guest account and logs it in. */
    @Transactional
    public AuthResponse loginAsGuest() {
        UserEntity guest = new UserEntity(generateGuestUsername(), null, AuthProvider.GUEST);
        userRepository.save(guest);
        return issueTokens(guest);
    }

    /** Finds the account by Google's permanent id, creating it on first sign-in. */
    @Transactional
    public AuthResponse loginWithGoogle(GoogleProfile profile) {
        UserEntity user = userRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, profile.sub())
                .orElseGet(() -> userRepository.save(createGoogleUser(profile)));
        // Google rotates picture URLs, so the stored one goes stale.
        user.setProfilePictureUrl(profile.picture());
        return issueTokens(user);
    }

    /**
     * Exchanges a refresh token for a new token pair. Rotation: the presented
     * token is deleted, so each token can only ever be used once.
     */
    @Transactional
    public AuthResponse refresh(String refreshToken) {
        RefreshTokenEntity stored = refreshTokenRepository.findByTokenHash(sha256(refreshToken))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            refreshTokenRepository.burn(stored.getId());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        // Two tabs refreshing with the same cookie both find the row; the atomic burn lets
        // exactly one win, and the other gets a plain 401 (the frontend then re-checks the session).
        if (refreshTokenRepository.burn(stored.getId()) == 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token already used");
        }
        return issueTokens(stored.getUser());
    }

    /** ON DELETE CASCADE takes the user's tokens, progress and favourites with the row. */
    @Transactional
    public void deleteAccount(long userId) {
        userRepository.deleteById(userId);
    }

    /** Logout: burn the refresh token row. Idempotent — a missing token is fine. */
    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.deleteByTokenHash(sha256(refreshToken));
    }

    public Duration getAccessTtl() {
        return jwtService.getAccessTtl();
    }

    public Duration getRefreshTtl() {
        return jwtService.getRefreshTtl();
    }

    private AuthResponse issueTokens(UserEntity user) {
        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken();
        Instant expiresAt = Instant.now().plus(jwtService.getRefreshTtl());
        refreshTokenRepository.save(new RefreshTokenEntity(sha256(refreshToken), user, expiresAt));
        return AuthResponse.of(accessToken, refreshToken, user);
    }

    private UserEntity createGoogleUser(GoogleProfile profile) {
        // email is unique: if a recreated Google account reuses one we already hold, keep the new account email-less.
        String email = profile.emailVerified() && userRepository.findByEmail(profile.email()).isEmpty()
                ? profile.email()
                : null;
        return new UserEntity(generateGoogleUsername(profile), email, AuthProvider.GOOGLE,
                profile.picture(), profile.sub());
    }

    /** The Google display name, suffixed only when another account already has it. */
    private String generateGoogleUsername(GoogleProfile profile) {
        String base = profile.name() == null || profile.name().isBlank() ? "user" : profile.name();
        String username = base;
        while (userRepository.existsByUsername(username)) {
            username = base + "-" + randomString(4);
        }
        return username;
    }

    private String generateGuestUsername() {
        String suffix;
        do {
            suffix = randomString(6);
        } while (userRepository.existsByUsername("guest-" + suffix));
        return "guest-" + suffix;
    }

    private String randomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(GUEST_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(GUEST_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
