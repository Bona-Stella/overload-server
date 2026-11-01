package com.overload.overload_server.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@Testcontainers
class V16MigrationIntegrationTests {
    @Container final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_catalog_v16_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());

    @Test void emptyV15UpgradeAddsOnlyFiveEmptyTablesAndPreservesExistingStructureAndHistory() {
        assertThat(postgres.getMappedPort(5432)).isNotIn(5432, 5433);
        assertThat(postgres.getDatabaseName()).isEqualTo("overload_catalog_v16_test");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        var old = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("15").load();
        old.migrate(); old.validate();
        var history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        var tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class);
        var structures = new LinkedHashMap<String, Object>();
        for (String table : tables) {
            assertThat(table).matches("[a-z_]+");
            structures.put(table, structure(jdbc, table));
            if (!table.equals("flyway_schema_history")) assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }

        var latest = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("16").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.info().current().getVersion().toString()).isEqualTo("16");
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE installed_rank<=15 ORDER BY installed_rank")).isEqualTo(history);
        var added = new ArrayList<>(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class));
        added.removeAll(tables);
        assertThat(added).containsExactly("exercise_alias", "exercise_muscle", "manufacturer", "muscle", "standard_exercise");
        for (String table : tables) assertThat(structure(jdbc, table)).as(table).isEqualTo(structures.get(table));
        for (String table : added) assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='public' AND tablename IN ('standard_exercise','exercise_alias','muscle','exercise_muscle','manufacturer')", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype='f' AND conrelid IN ('exercise_alias'::regclass,'exercise_muscle'::regclass)", Integer.class)).isEqualTo(3);
    }

    private static Object structure(JdbcTemplate jdbc, String table) {
        return List.of(
                jdbc.queryForList("SELECT column_name,data_type,is_nullable,column_default FROM information_schema.columns WHERE table_schema='public' AND table_name=? ORDER BY ordinal_position", table),
                jdbc.queryForList("SELECT conname,contype::text,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE conrelid=to_regclass(?) ORDER BY conname", "public." + table),
                jdbc.queryForList("SELECT indexname,indexdef FROM pg_indexes WHERE schemaname='public' AND tablename=? ORDER BY indexname", table),
                jdbc.queryForList("SELECT tgname,pg_get_triggerdef(oid) AS definition FROM pg_trigger WHERE tgrelid=to_regclass(?) AND NOT tgisinternal ORDER BY tgname", "public." + table));
    }
}
