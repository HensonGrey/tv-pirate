package com.tvpirate.backend.auth.dto;

import com.tvpirate.backend.user.User;

/** Successful auth response: the token pair plus basic user info for the client. */
public record AuthResponse(String accessToken, String refreshToken, UserDto user) {

    public static AuthResponse of(String accessToken, String refreshToken, User user) {
        return new AuthResponse(
                accessToken,
                refreshToken,
                new UserDto(user.getId(), user.getUsername(), user.getProvider().name()));
    }
}
