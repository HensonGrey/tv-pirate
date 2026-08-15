package com.tvpirate.backend.api;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tvpirate.backend.auth.dto.UserDto;
import com.tvpirate.backend.security.AuthedUser;

/** Session probe: the frontend calls this on startup to learn whether the
 * httpOnly cookies still hold a valid session (JS can't read them itself). */
@RestController
@RequestMapping("/api")
public class MeController {

    @GetMapping("/me")
    public UserDto me(Authentication authentication) {
        AuthedUser principal = (AuthedUser) authentication.getPrincipal();
        return new UserDto(principal.id(), principal.username(), principal.provider(),
                principal.profilePictureUrl());
    }
}
