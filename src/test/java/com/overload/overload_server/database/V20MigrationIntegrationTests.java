package com.overload.overload_server.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres") @Testcontainers
class V20MigrationIntegrationTests {
    @Container final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_planning_v20_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    @Test void upgradePreservesAllV19RowsAndOnlyFirstVersionMayStartOnAWeekday() {
        assertThat(postgres.getMappedPort(5432)).isNotIn(5432,5433);
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("19").load().migrate();
        UUID old=UUID.randomUUID(),fresh=UUID.randomUUID();jdbc.update("INSERT INTO users(id) VALUES (?),(?)",old,fresh);
        jdbc.update("INSERT INTO weekly_schedule_versions VALUES (?,0,1,'2026-11-05T05:00:00Z','2026-11-09','Asia/Seoul','[]')",old);
        jdbc.update("INSERT INTO weekly_schedule_versions VALUES (?,0,2,'2026-11-06T05:00:00Z','2026-11-09','Asia/Seoul','[]')",old);
        var tables=jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class);
        var before=new LinkedHashMap<String,List<String>>();
        for(String table:tables){assertThat(table).matches("[a-z_]+");before.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class));}
        var latest=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("20").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();assertThat(latest.migrate().migrationsExecuted).isZero();
        for(String table:tables){String scope=table.equals("flyway_schema_history")?" WHERE installed_rank<=19":"";
            assertThat(jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t"+scope+" ORDER BY to_jsonb(t)::text",String.class)).as(table).isEqualTo(before.get(table));}
        jdbc.update("INSERT INTO weekly_schedule_versions VALUES (?,0,1,'2026-11-12T05:00:00Z','2026-11-12','Asia/Seoul','[]')",fresh);
        assertThatThrownBy(()->jdbc.update("INSERT INTO weekly_schedule_versions VALUES (?,0,2,now(),'2026-11-13','Asia/Seoul','[]')",fresh)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO weekly_schedule_versions VALUES (?,0,2,now(),'2026-11-16','Asia/Seoul','[]')",fresh);
        assertThatThrownBy(()->jdbc.update("UPDATE weekly_schedule_versions SET effective_from='2026-11-16' WHERE user_id=? AND version=1",fresh)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
