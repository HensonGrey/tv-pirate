package com.tvpirate.backend.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.tvpirate.backend.user.User;
import com.tvpirate.backend.user.UserRepository;

/**
 * Spring Security's seam for loading a user record. Historically named
 * "loadUserByUsername", but our tokens carry the user <b>id</b>, so that is
 * what gets passed in. Loading the user on every request (instead of trusting
 * the JWT alone) means a deleted account loses access immediately.
 */
@Service
public class JwtUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public JwtUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String userId) {
        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));

        return new AuthedUser(user.getId(), user.getUsername(), user.getProvider().name());
    }
}
