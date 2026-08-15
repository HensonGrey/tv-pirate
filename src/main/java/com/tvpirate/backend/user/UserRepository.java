package com.tvpirate.backend.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/** Data access for users. Method names become SQL queries via Spring Data —
 * no SQL written anywhere. */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<UserEntity> findByEmail(String email); // for future social login
}
