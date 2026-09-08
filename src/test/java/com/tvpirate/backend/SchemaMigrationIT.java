package com.tvpirate.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The migrations build a usable schema from an empty database, and Hibernate
 * agrees with the result. Booting on the "it" profile at all is most of the
 * assertion — ddl-auto=validate fails the context if any entity has drifted
 * from what Liquibase produced. vault:testing-deep-dive#db-tier
 */
@SpringBootTest
@ActiveProfiles("it")
class SchemaMigrationIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void liquibaseBuildsEveryTableTheAppNeeds() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables)
                .contains("users", "refresh_tokens", "watch_progress", "favourites")
                .contains("databasechangelog"); // proof the migrations ran rather than ddl-auto
    }

    @Test
    void everyChangesetRanCleanly() {
        Integer applied = jdbc.queryForObject("SELECT count(*) FROM databasechangelog", Integer.class);

        assertThat(applied).isNotNull().isPositive();
    }

    @Test
    void watchProgressKeepsBothPartialUniqueIndexes() {
        // The upsert has to name the matching predicate: an ON CONFLICT listing
        // only the columns matches neither index and duplicates rows instead.
        // vault:watch-progress-deep-dive
        List<String> partial = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'watch_progress' AND indexdef LIKE '%WHERE%'",
                String.class);

        assertThat(partial).hasSize(2);
        assertThat(partial)
                .anySatisfy(def -> assertThat(def).containsIgnoringCase("season_number IS NOT NULL"))
                .anySatisfy(def -> assertThat(def).containsIgnoringCase("season_number IS NULL"));
    }

    @Test
    void theTestDatabaseIsNotTheDevelopmentOne() {
        // A guard, not a schema check: this tier truncates freely, so it must
        // never be pointed at the database holding real rows.
        String database = jdbc.queryForObject("SELECT current_database()", String.class);

        assertThat(database).isEqualTo("tv-pirate-test");
    }
}
