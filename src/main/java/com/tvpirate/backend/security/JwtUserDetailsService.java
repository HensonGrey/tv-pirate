package com.tvpirate.backend.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.tvpirate.backend.user.UserEntity;
import com.tvpirate.backend.user.UserRepository;

/** Spring Security's user-loading seam — our tokens carry the user id, so
 * that's what's passed in. DB lookup per request means a deleted account
 * loses access immediately. vault:auth-deep-dive#user-loading */
@Service
public class JwtUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public JwtUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String userId) {
        UserEntity user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + userId));

        return new AuthedUser(user.getId(), user.getUsername(), user.getProvider().name(),
                user.getProfilePictureUrl());
    }
}
