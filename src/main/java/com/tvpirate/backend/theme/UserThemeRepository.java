package com.tvpirate.backend.theme;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserThemeRepository extends JpaRepository<UserThemeEntity, Long> {

    /** The primary key is the conflict target, so two tabs saving a first choice at once
     * can't surface as a 500 — the later one just wins. */
    @Modifying
    @Query(value = "INSERT INTO user_themes (user_id, theme) VALUES (:userId, :theme) "
            + "ON CONFLICT (user_id) DO UPDATE SET theme = EXCLUDED.theme", nativeQuery = true)
    void upsert(@Param("userId") Long userId, @Param("theme") String theme);
}
