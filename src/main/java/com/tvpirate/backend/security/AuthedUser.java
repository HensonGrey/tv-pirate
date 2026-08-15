package com.tvpirate.backend.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The principal put into the SecurityContext by the JWT filter. Carries the
 * fields our controllers actually need (id, username, provider, profile
 * picture) so {@code /api/me} and friends can answer directly instead of
 * string-parsing Spring's default User.
 */
public record AuthedUser(Long id, String username, String provider, String profilePictureUrl) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getPassword() {
        return ""; // never checked — this app has no passwords at all
    }

    @Override
    public String getUsername() {
        return username;
    }
}
