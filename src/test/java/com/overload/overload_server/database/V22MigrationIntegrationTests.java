package com.overload.overload_server.database;

import com.overload.testfixture.CatalogFixtures;
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
class V22MigrationIntegrationTests {
    @Container final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine").withDatabaseName("overload_bodyweight_v22_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    @Test void populatedUpgradePreservesLegacyNumbersAndEnforcesExplicitBodyweightEvidence(){
        assertThat(postgres.getMappedPort(5432)).isNotIn(5432,5433);
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("21").load().migrate();
        UUID owner=UUID.randomUUID(),routine=UUID.randomUUID(),session=UUID.randomUUID(),item=UUID.randomUUID(),set=UUID.randomUUID(),device=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES (?)",owner);jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'preserved',now(),now())",routine,owner);
        jdbc.update("""
            INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,initial_plan_snapshot,applied_plan_snapshot,last_workout_synced_at,created_at,updated_at)
            VALUES (?,?,0,?,'2026-11-01T14:50:00Z','2026-11-01','Asia/Seoul',?,?,'{"schemaVersion":2,"weightExercises":[],"cardioActivities":[]}','{"schemaVersion":2,"weightExercises":[],"cardioActivities":[]}',now(),now(),now())
            """,session,owner,routine,device,device);
        jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,standard_exercise_id) VALUES (?,?,?,0,'WEIGHT',?)",item,session,owner,CatalogFixtures.exercise(jdbc));
        jdbc.update("INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording,load_type) VALUES (?,?,?,1,80,8,now(),'TODAY_ONLY','TODAY_ONLY','BODYWEIGHT')",set,session,item);
        var tables=jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class);var before=new LinkedHashMap<String,List<String>>();
        for(String table:tables){assertThat(table).matches("[a-z_]+");before.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class));}
        var latest=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("22").load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();assertThat(latest.migrate().migrationsExecuted).isZero();
        for(String table:tables){String value=table.equals("performed_sets")?"(to_jsonb(t)-'bodyweight_load')":"to_jsonb(t)",where=table.equals("flyway_schema_history")?" WHERE installed_rank<=21":"";
            assertThat(jdbc.queryForList("SELECT "+value+"::text FROM "+table+" t"+where+" ORDER BY "+value+"::text",String.class)).as(table).isEqualTo(before.get(table));}
        assertThat(jdbc.queryForObject("SELECT bodyweight_load IS NULL FROM performed_sets WHERE id=?",Boolean.class,set)).isTrue();
        for(String valid:List.of("{\"additionalWeightKg\":\"10.000001\"}","{\"additionalWeightKg\":\"0\"}","{\"additionalWeightKg\":null}"))jdbc.update("UPDATE performed_sets SET bodyweight_load=?::jsonb WHERE id=?",valid,set);
        for(String invalid:List.of("{}","null","[]","{\"additionalWeightKg\":-1}","{\"additionalWeightKg\":\"-1\"}","{\"additionalWeightKg\":\"100000000000000\"}","{\"additionalWeightKg\":\"1.0000001\"}","{\"additionalWeightKg\":\"1.0\"}","{\"additionalWeightKg\":\"5\",\"bodyMass\":80}"))
            assertThatThrownBy(()->jdbc.update("UPDATE performed_sets SET bodyweight_load=?::jsonb WHERE id=?",invalid,set)).as(invalid).isInstanceOf(DataIntegrityViolationException.class);
        for(String load:Arrays.asList("PLATE",null))assertThatThrownBy(()->jdbc.update("INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording,load_type,bodyweight_load) VALUES (?,?,?,2,80,8,now(),'TODAY_ONLY','TODAY_ONLY',?,'{\"additionalWeightKg\":\"5\"}')",UUID.randomUUID(),session,item,load)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("performed_sets_bodyweight_load_check");
        jdbc.update("UPDATE performed_sets SET bodyweight_load=NULL WHERE id=?",set);
        assertThat(jdbc.queryForObject("SELECT weight FROM performed_sets WHERE id=?",java.math.BigDecimal.class,set)).isEqualByComparingTo("80");
    }
}
