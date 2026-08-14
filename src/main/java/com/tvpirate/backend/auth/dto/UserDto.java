package com.tvpirate.backend.auth.dto;

/** Safe subset of user data to send to the client (never anything sensitive). */
public record UserDto(Long id, String username, String provider) {
}
