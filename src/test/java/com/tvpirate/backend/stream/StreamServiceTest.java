package com.tvpirate.backend.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.tvpirate.backend.stream.StreamProvider.ResolveRequest;
import com.tvpirate.backend.stream.StreamProvider.StreamSource;

/**
 * The registry + cache contract. Every assertion here is about how often we
 * touch a gray-market upstream: these services expect roughly one resolve per
 * playback session, so a prefetch racing a play must cost one call and a
 * provider that answers nothing must not be re-hammered on every re-click.
 * vault:streaming-providers-deep-dive#architecture
 */
class StreamServiceTest {

    // --- the registry ---

    @Test
    void anUnknownProviderNameIsRejected() {
        StreamService service = new StreamService(List.of(new CountingProvider("vixsrc")));

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> service.resolve("vidlink", movie(550)))
                .withMessage("unknown provider: vidlink");
    }

    @Test
    void theProviderListIsSortedSoThePickerOrderIsStable() {
        StreamService service = new StreamService(List.of(
                new CountingProvider("vixsrc"), new CountingProvider("videasy"), new CountingProvider("aardvark")));

        assertThat(service.providerNames()).containsExactly("aardvark", "videasy", "vixsrc");
    }

    @Test
    void eachProviderIsRegisteredUnderItsOwnName() {
        CountingProvider videasy = new CountingProvider("videasy");
        CountingProvider vixsrc = new CountingProvider("vixsrc");
        StreamService service = new StreamService(List.of(videasy, vixsrc));

        service.resolve("vixsrc", movie(550));

        assertThat(vixsrc.calls()).isEqualTo(1);
        assertThat(videasy.calls()).isZero();
    }

    // --- the cache ---

    @Test
    void repeatedResolvesOfTheSameTitleCostOneUpstreamCall() {
        CountingProvider provider = new CountingProvider("vixsrc");
        StreamService service = new StreamService(List.of(provider));

        for (int i = 0; i < 5; i++) {
            service.resolve("vixsrc", movie(550));
        }

        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void theCachedSourcesAreHandedBackUnchanged() {
        CountingProvider provider = new CountingProvider("vixsrc");
        StreamService service = new StreamService(List.of(provider));

        List<StreamSource> first = service.resolve("vixsrc", movie(550));
        List<StreamSource> second = service.resolve("vixsrc", movie(550));

        assertThat(first).isEqualTo(second).extracting(StreamSource::quality).containsExactly("1080p");
    }

    @Test
    void anEmptyAnswerIsNegativeCachedToo() {
        // A dead provider must not be re-hammered on every re-click.
        CountingProvider provider = new CountingProvider("vixsrc", List.of());
        StreamService service = new StreamService(List.of(provider));

        assertThat(service.resolve("vixsrc", movie(550))).isEmpty();
        assertThat(service.resolve("vixsrc", movie(550))).isEmpty();
        assertThat(provider.calls()).isEqualTo(1);
    }

    @Test
    void aPrefetchRacingAPlayStillCostsOneUpstreamCall() throws Exception {
        // Caffeine's atomic get() is the whole single-flight guarantee; the
        // slow provider widens the window a real upstream would leave open.
        CountingProvider provider = new CountingProvider("vixsrc", CountingProvider.ONE_SOURCE, 150);
        StreamService service = new StreamService(List.of(provider));
        CountDownLatch start = new CountDownLatch(1);
        int racers = 8;

        try (ExecutorService pool = Executors.newFixedThreadPool(racers)) {
            for (int i = 0; i < racers; i++) {
                pool.submit(() -> {
                    start.await();
                    return service.resolve("vixsrc", movie(550));
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(provider.calls()).isEqualTo(1);
    }

    // --- what makes a cache key ---

    @Test
    void differentTitlesAreDifferentKeys() {
        CountingProvider provider = new CountingProvider("vixsrc");
        StreamService service = new StreamService(List.of(provider));

        service.resolve("vixsrc", movie(550));
        service.resolve("vixsrc", movie(27205));

        assertThat(provider.calls()).isEqualTo(2);
    }

    @Test
    void aMovieAndATvEntryWithTheSameIdDoNotCollide() {
        CountingProvider provider = new CountingProvider("vixsrc");
        StreamService service = new StreamService(List.of(provider));

        service.resolve("vixsrc", movie(1396));
        service.resolve("vixsrc", new ResolveRequest("tv", 1396, 1, 1));

        assertThat(provider.calls()).isEqualTo(2);
    }

    @Test
    void everyEpisodeIsItsOwnKey() {
        CountingProvider provider = new CountingProvider("vixsrc");
        StreamService service = new StreamService(List.of(provider));

        service.resolve("vixsrc", new ResolveRequest("tv", 1396, 1, 1));
        service.resolve("vixsrc", new ResolveRequest("tv", 1396, 1, 2));
        service.resolve("vixsrc", new ResolveRequest("tv", 1396, 2, 1));
        service.resolve("vixsrc", new ResolveRequest("tv", 1396, 1, 1)); // back to the first

        assertThat(provider.calls()).isEqualTo(3);
    }

    @Test
    void twoProvidersResolvingTheSameTitleAreSeparateKeys() {
        CountingProvider videasy = new CountingProvider("videasy");
        CountingProvider vixsrc = new CountingProvider("vixsrc");
        StreamService service = new StreamService(List.of(videasy, vixsrc));

        service.resolve("videasy", movie(550));
        service.resolve("vixsrc", movie(550));

        assertThat(videasy.calls()).isEqualTo(1);
        assertThat(vixsrc.calls()).isEqualTo(1);
    }

    // --- helpers ---

    private static ResolveRequest movie(long tmdbId) {
        return new ResolveRequest("movie", tmdbId, null, null);
    }

    /** A provider that records every invocation — the only way to see the cache
     *  working from outside. */
    private static final class CountingProvider implements StreamProvider {

        static final List<StreamSource> ONE_SOURCE = List.of(
                new StreamSource("1080p", "https://cdn.example/1080/index.m3u8", Map.of(), "hls"));

        private final String name;
        private final List<StreamSource> answer;
        private final long delayMillis;
        private final AtomicInteger calls = new AtomicInteger();

        CountingProvider(String name) {
            this(name, ONE_SOURCE, 0);
        }

        CountingProvider(String name, List<StreamSource> answer) {
            this(name, answer, 0);
        }

        CountingProvider(String name, List<StreamSource> answer, long delayMillis) {
            this.name = name;
            this.answer = answer;
            this.delayMillis = delayMillis;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<StreamSource> resolve(ResolveRequest request) {
            calls.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return answer;
        }

        int calls() {
            return calls.get();
        }
    }
}
