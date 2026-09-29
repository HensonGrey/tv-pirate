package com.tvpirate.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;

import com.github.benmanes.caffeine.cache.Cache;

/** Every cache must be bounded in size and time — including ones a future
 * @Cacheable names without registering them here. */
class TmdbConfigTest {

    private final CaffeineCacheManager manager = new TmdbConfig().cacheManager();

    @Test
    void everyCacheTheCodeUsesIsBoundedAndExpires() {
        for (String name : List.of("trending", "discover", "search", "tmdb-detail",
                "tmdb-genres", "tmdb-image-config", "tmdb-imdb-id")) {
            assertBounded(name);
        }
    }

    @Test
    void anUnregisteredCacheIsBoundedToo() {
        assertBounded("some-future-cache");
    }

    private void assertBounded(String name) {
        Cache<?, ?> cache = (Cache<?, ?>) manager.getCache(name).getNativeCache();
        assertThat(cache.policy().eviction()).as(name + " has a maximum size").isPresent();
        assertThat(cache.policy().expireAfterWrite()).as(name + " expires").isPresent();
    }
}
