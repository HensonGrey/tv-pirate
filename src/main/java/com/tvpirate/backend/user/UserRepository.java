package com.tvpirate.backend.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The data access layer for users. Extending JpaRepository gives us
 * save/findById/delete etc. for free, and the findByXxx methods below are
 * turned into real SQL queries by Spring Data based on their names alone —
 * no SQL is written anywhere.
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<UserEntity> findByEmail(String email); // for future social login
}
