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
class V21MigrationIntegrationTests {
    @Container final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine").withDatabaseName("overload_cardio_v21_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    @Test void populatedLegacyUpgradePreservesAllFactsAndDoesNotInventSpeedOrProvenance(){
        assertThat(postgres.getMappedPort(5432)).isNotIn(5432,5433);
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("20").load().migrate();
        UUID owner=UUID.randomUUID(),routine=UUID.randomUUID(),session=UUID.randomUUID(),activity=UUID.randomUUID(),result=UUID.randomUUID(),device=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES (?)",owner);jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'preserved',now(),now())",routine,owner);
        jdbc.update("""
            INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,initial_plan_snapshot,applied_plan_snapshot,last_workout_synced_at,created_at,updated_at)
            VALUES (?,?,0,?,'2026-11-01T14:50:00Z','2026-11-01','Asia/Seoul',?,?,'{"schemaVersion":2,"weightExercises":[],"cardioActivities":[]}','{"schemaVersion":2,"weightExercises":[],"cardioActivities":[]}',now(),now(),now())
            """,session,owner,routine,device,device);
        jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,standard_exercise_id) VALUES (?,?,?,0,'CARDIO',null)",activity,session,owner);
        jdbc.update("INSERT INTO cardio_results(id,session_id,session_activity_id,status,started_at,occurred_at,timer_ms,active_ms,corrected,distance) VALUES (?,?,?,'COMPLETED','2026-11-01T14:50:00Z','2026-11-01T15:20:00Z',1800000,1800000,false,3.8)",result,session,activity);
        var tables=jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class);var before=new LinkedHashMap<String,List<String>>();
        for(String table:tables){assertThat(table).matches("[a-z_]+");before.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class));}
        var latest=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("21").load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();assertThat(latest.migrate().migrationsExecuted).isZero();
        for(String table:tables){String value=table.equals("cardio_results")?"(to_jsonb(t)-'measurement')":"to_jsonb(t)",where=table.equals("flyway_schema_history")?" WHERE installed_rank<=20":"";
            assertThat(jdbc.queryForList("SELECT "+value+"::text FROM "+table+" t"+where+" ORDER BY "+value+"::text",String.class)).as(table).isEqualTo(before.get(table));}
        assertThat(jdbc.queryForObject("SELECT measurement IS NULL FROM cardio_results WHERE id=?",Boolean.class,result)).isTrue();
        jdbc.update("UPDATE cardio_results SET distance=4,measurement='{\"actualSpeedKmh\":\"8\",\"distanceSource\":\"CALCULATED\"}' WHERE id=?",result);
        assertThatThrownBy(()->jdbc.update("UPDATE cardio_results SET distance=5 WHERE id=?",result)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE cardio_results SET distance=3.8,measurement='{\"actualSpeedKmh\":\"8\",\"distanceSource\":\"MANUAL\"}' WHERE id=?",result);
        jdbc.update("UPDATE cardio_results SET distance=0,measurement='{\"actualSpeedKmh\":\"0\",\"distanceSource\":\"CALCULATED\"}' WHERE id=?",result);
        assertThatThrownBy(()->jdbc.update("UPDATE cardio_results SET measurement='{\"actualSpeedKmh\":null,\"distanceSource\":\"CALCULATED\"}' WHERE id=?",result)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
