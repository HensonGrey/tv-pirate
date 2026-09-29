package com.tvpirate.backend.tmdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tvpirate.backend.tmdb.dto.MediaItem;
import com.tvpirate.backend.tmdb.dto.PageResponse;

/** Trending and search are per type: the frontend pages the movie and show
 * rows independently, so one request must touch exactly one TMDB index. */
class TmdbServiceTest {

    @Test
    void searchAsksOnlyThatTypesIndex() {
        FakeClient client = new FakeClient(List.of(entry(1, null, 7.0), entry(2, null, 6.0)));

        PageResponse<MediaItem> page = new TmdbService(client).search("tv", "  lost ", 2);

        assertThat(client.calls).containsExactly("search:tv:lost:2");
        assertThat(page.results()).extracting(MediaItem::mediaType).containsOnly("tv");
        assertThat(page.totalPages()).isEqualTo(9);
        assertThat(page.totalResults()).isEqualTo(170);
    }

    @Test
    void trendingAsksOnlyThatTypesListAndRanksByRating() {
        FakeClient client = new FakeClient(List.of(entry(1, "movie", 5.0), entry(2, "movie", 0.0), entry(3, "movie", 8.0)));

        PageResponse<MediaItem> page = new TmdbService(client).trending("movie", "day", 1);

        assertThat(client.calls).containsExactly("trending:movie:day:1");
        assertThat(page.results()).extracting(MediaItem::id).containsExactly(3L, 1L, 2L);
    }

    @Test
    void anEntryWithoutAMediaTypeTakesTheRequestedOne() {
        FakeClient client = new FakeClient(List.of(entry(1, null, 7.0)));

        PageResponse<MediaItem> page = new TmdbService(client).trending("tv", "week", 1);

        assertThat(page.results().getFirst().mediaType()).isEqualTo("tv");
    }

    private static TmdbClient.TmdbEntry entry(long id, String mediaType, double rating) {
        return new TmdbClient.TmdbEntry(id, mediaType, "Title " + id, "Title " + id, null, null, null,
                rating, List.of(), "2020-01-01", "2020-01-01");
    }

    /** Answers every list call with the same page and records what was asked. */
    private static final class FakeClient extends TmdbClient {
        final List<String> calls = new ArrayList<>();
        private final List<TmdbEntry> results;

        FakeClient(List<TmdbEntry> results) {
            super(null);
            this.results = results;
        }

        @Override
        public TmdbPage<TmdbEntry> trending(String type, String window, int page) {
            calls.add("trending:" + type + ':' + window + ':' + page);
            return new TmdbPage<>(page, results, 9, 170);
        }

        @Override
        public TmdbPage<TmdbEntry> search(String type, String query, int page) {
            calls.add("search:" + type + ':' + query + ':' + page);
            return new TmdbPage<>(page, results, 9, 170);
        }

        @Override
        public List<GenreEntry> genreTable(String type) {
            return List.of();
        }

        @Override
        public ImageSettings imageConfig() {
            return new ImageSettings("https://img/", List.of(), List.of());
        }
    }
}
