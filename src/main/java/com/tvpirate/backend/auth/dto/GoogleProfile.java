package com.tvpirate.backend.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The part of Google's userinfo answer we keep; sub is the account's permanent id. */
public record GoogleProfile(String sub,
                            String email,
                            @JsonProperty("email_verified") boolean emailVerified,
                            String name,
                            String picture) {
}
