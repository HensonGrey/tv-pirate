package com.tvpirate.backend.security;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/** The principal the JWT filter puts in the SecurityContext — carries the
 * fields controllers need, instead of Spring's string-parsing default User. */
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
