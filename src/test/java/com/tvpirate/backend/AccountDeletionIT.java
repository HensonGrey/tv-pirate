package com.tvpirate.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import com.tvpirate.backend.auth.AuthService;
import com.tvpirate.backend.auth.dto.AuthResponse;
import com.tvpirate.backend.auth.dto.GoogleProfile;
import com.tvpirate.backend.user.AuthProvider;
import com.tvpirate.backend.user.UserRepository;

/** Deleting a Google account removes the user, ends its session, and a later
 * sign-in with the same Google account starts a fresh, empty one. */
@SpringBootTest
@ActiveProfiles("it")
class AccountDeletionIT {

    private static final GoogleProfile PROFILE =
            new GoogleProfile("google-sub-1", "someone@example.com", true, "Someone", "https://pic/1");

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void truncate() {
        jdbc.execute("TRUNCATE users, refresh_tokens, watch_progress, favourites RESTART IDENTITY CASCADE");
    }

    @Test
    void deletingTheAccountRemovesTheUserAndBurnsItsSession() {
        AuthResponse auth = authService.loginWithGoogle(PROFILE);

        authService.deleteAccount(auth.user().id());

        assertThat(userRepository.findById(auth.user().id())).isEmpty();
        assertThatThrownBy(() -> authService.refresh(auth.refreshToken()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid refresh token");
    }

    @Test
    void signingInAgainAfterDeletionCreatesANewAccount() {
        long deletedId = authService.loginWithGoogle(PROFILE).user().id();
        authService.deleteAccount(deletedId);

        long newId = authService.loginWithGoogle(PROFILE).user().id();

        assertThat(newId).isNotEqualTo(deletedId);
        assertThat(userRepository.findByProviderAndProviderSubject(AuthProvider.GOOGLE, PROFILE.sub()))
                .get().extracting(user -> user.getEmail()).isEqualTo(PROFILE.email());
    }
}
