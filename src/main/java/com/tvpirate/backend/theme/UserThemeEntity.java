package com.tvpirate.backend.theme;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An account's light/dark choice, one row per user (migration 0011). No row means
 * the user never picked one, and the browser follows the OS. */
@Entity
@Table(name = "user_themes")
public class UserThemeEntity {

    @Id
    @Column(name = "user_id")
    private Long userId;

    /** "light" | "dark". */
    @Column(nullable = false)
    private String theme;

    protected UserThemeEntity() {
        // JPA needs a no-arg constructor; rows are only ever written via
        // UserThemeRepository.upsert's native INSERT.
    }

    public String getTheme() {
        return theme;
    }
}
