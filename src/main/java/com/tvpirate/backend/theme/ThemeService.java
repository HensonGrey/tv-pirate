package com.tvpirate.backend.theme;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The account's theme. Saving is an upsert, so a repeat or two tabs at once is harmless. */
@Service
public class ThemeService {

    private final UserThemeRepository repository;

    public ThemeService(UserThemeRepository repository) {
        this.repository = repository;
    }

    /** Empty when the user never picked one. */
    @Transactional(readOnly = true)
    public Optional<String> find(Long userId) {
        return repository.findById(userId).map(UserThemeEntity::getTheme);
    }

    @Transactional
    public void save(Long userId, String theme) {
        repository.upsert(userId, theme);
    }
}
