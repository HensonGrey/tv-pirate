package com.tvpirate.backend.stream;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tvpirate.backend.stream.StreamProvider.ResolveRequest;
import com.tvpirate.backend.stream.StreamProvider.StreamSource;

/**
 * Registry over the StreamProviders plus the resolve cache. One provider
 * resolves per key, fast-fail, no fallback chains — and Caffeine's atomic
 * get() gives single-flight for free, so a prefetch racing a play costs one
 * upstream call, not two. vault:streaming-providers-deep-dive#architecture
 */
@Service
public class StreamService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /** Registry key: provider + title coordinates. Season/episode are null for movies. */
    private record StreamKey(String provider, String mediaType, long tmdbId, Integer season, Integer episode) {}

    private final Map<String, StreamProvider> providers;
    private final Cache<StreamKey, List<StreamSource>> cache;

    /** Spring injects every @Component implementing StreamProvider; each one's
     *  name() is its registry id. */
    public StreamService(List<StreamProvider> providerBeans) {
        this.providers = providerBeans.stream()
                .collect(Collectors.toUnmodifiableMap(StreamProvider::name, p -> p));
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(1000)
                .build();
    }

    /** Picker list, sorted — so the UI shows a stable order between visits. */
    public List<String> providerNames() {
        return providers.keySet().stream().sorted().toList();
    }

    /**
     * Resolve exactly the named provider. Unknown name → IllegalArgumentException
     * (the controller turns it into a 400). Empty list means "provider can't
     * serve" and is cached too — negative caching keeps re-clicks from
     * re-hammering an upstream while it's broken.
     */
    public List<StreamSource> resolve(String provider, ResolveRequest request) {
        StreamProvider impl = providers.get(provider);
        if (impl == null) {
            throw new IllegalArgumentException("unknown provider: " + provider);
        }
        StreamKey key = new StreamKey(provider, request.mediaType(),
                request.tmdbId(), request.season(), request.episode());
        return cache.get(key, k -> impl.resolve(request));
    }
}
