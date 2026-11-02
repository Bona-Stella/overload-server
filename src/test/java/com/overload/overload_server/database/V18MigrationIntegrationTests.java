package com.overload.overload_server.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@Testcontainers
class V18MigrationIntegrationTests {
    @Container final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_catalog_v18_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc;

    Flyway flyway(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target(target).load();
    }

    @BeforeEach void v17() {
        assertThat(postgres.getMappedPort(5432)).isNotIn(5432, 5433);
        assertThat(postgres.getDatabaseName()).isEqualTo("overload_catalog_v18_test");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        flyway("17").migrate();
    }

    @Test void populatedV17UpgradePreservesEveryExistingRowConstraintIndexAndTrigger() {
        UUID exercise = UUID.randomUUID(), muscle = UUID.randomUUID(), owner = UUID.randomUUID(), routine = UUID.randomUUID();
        UUID session = UUID.randomUUID(), item = UUID.randomUUID();
        jdbc.update("INSERT INTO standard_exercise(id,name,is_active) VALUES (?,'unclassified',false)", exercise);
        jdbc.update("INSERT INTO muscle(id,group_code,name) VALUES (?,'LEGS','review pending')", muscle);
        jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'PRIMARY')", exercise, muscle);
        jdbc.update("INSERT INTO exercise_alias VALUES (?,'example','example')", exercise);
        jdbc.update("INSERT INTO users(id) VALUES (?)", owner);
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'preserved',now(),now())", routine, owner);
        jdbc.update("""
                INSERT INTO routine_weight_placements(id,user_id,data_generation,routine_id,standard_exercise_id,position,
                    set_count,min_reps,max_reps,initial_weight,approved_weight,created_at,updated_at)
                VALUES (?,?,0,?,?,0,3,6,10,60,62.5,now(),now())
                """, UUID.randomUUID(), owner, routine, exercise);
        jdbc.update("""
                INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,
                    started_time_zone,started_device_id,editor_device_id,initial_plan_snapshot,applied_plan_snapshot,
                    last_workout_synced_at,created_at,updated_at)
                VALUES (?,?,0,?,now(),current_date,'UTC',?,?,'{"schemaVersion":2}','{"schemaVersion":2}',now(),now(),now())
                """, session, owner, routine, UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,standard_exercise_id) VALUES (?,?,?,0,'WEIGHT',?)", item, session, owner, exercise);
        jdbc.update("""
                INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording,load_type)
                VALUES (?,?,?,1,62.5,8,now(),'TODAY_ONLY','TODAY_ONLY','STACK')
                """, UUID.randomUUID(), session, item);

        var tables = jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename<>'flyway_schema_history' ORDER BY tablename", String.class);
        var rows = new LinkedHashMap<String, List<String>>();
        for (String table : tables) rows.put(table, rows(table, false));
        var history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        var structure = structure();
        var latest = flyway("18");
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        latest.validate();
        assertThat(latest.info().current().getVersion().toString()).isEqualTo("18");
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE installed_rank<=17 ORDER BY installed_rank")).isEqualTo(history);
        assertThat(structure()).isEqualTo(structure);
        for (String table : tables) assertThat(rows(table, table.equals("standard_exercise"))).as(table).isEqualTo(rows.get(table));
        assertThat(jdbc.queryForObject("SELECT joint_type FROM standard_exercise WHERE id=?", String.class, exercise)).isNull();
        assertThat(jdbc.queryForMap("SELECT is_nullable,column_default FROM information_schema.columns WHERE table_name='standard_exercise' AND column_name='joint_type'"))
                .containsEntry("is_nullable", "YES").containsEntry("column_default", null);
        for (String type : List.of("COMPOUND", "ISOLATION", "OTHER")) {
            jdbc.update("UPDATE standard_exercise SET joint_type=? WHERE id=?", type, exercise);
            assertThat(jdbc.queryForObject("SELECT joint_type FROM standard_exercise WHERE id=?", String.class, exercise)).isEqualTo(type);
        }
        for (String type : List.of("", "UNKNOWN", "compound", "LARGE", "SMALL"))
            assertThatThrownBy(() -> jdbc.update("UPDATE standard_exercise SET joint_type=? WHERE id=?", type, exercise))
                    .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE standard_exercise SET joint_type=null WHERE id=?", exercise);
        UUID newExercise = UUID.randomUUID();
        jdbc.update("INSERT INTO standard_exercise(id,name) VALUES (?,'old writer')", newExercise);
        assertThat(jdbc.queryForObject("SELECT joint_type FROM standard_exercise WHERE id=?", String.class, newExercise)).isNull();
    }

    @Test void auditDistinguishesMissingPrimaryInactiveMappingsAndDevelopmentExamplesWithoutWriting() throws Exception {
        flyway("18").migrate();
        UUID exercise = UUID.randomUUID(), primary = UUID.randomUUID(), secondary = UUID.randomUUID();
        jdbc.update("INSERT INTO standard_exercise(id,name,joint_type) VALUES (?,'example','COMPOUND')", exercise);
        jdbc.update("INSERT INTO muscle(id,group_code,name,is_active) VALUES (?,'LEGS','개발 예시',false),(?,'LEGS','secondary',true)", primary, secondary);
        jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'PRIMARY'),(?,?,'SECONDARY')", exercise, primary, exercise, secondary);
        String sql = Files.readString(Path.of("docs/dev-data/standard-exercise-catalog-quality-audit.sql"));
        var before = rows("exercise_muscle", false);
        var audit = jdbc.queryForMap(sql);
        assertThat(audit).containsEntry("joint_type_missing", false).containsEntry("active_primary_missing", true)
                .containsEntry("inactive_muscle_present", true).containsEntry("development_label_present", true)
                .containsEntry("primary_count", 0L).containsEntry("secondary_count", 1L);
        assertThat(rows("exercise_muscle", false)).isEqualTo(before);
        jdbc.update("UPDATE muscle SET is_active=true WHERE id=?", primary);
        jdbc.update("UPDATE standard_exercise SET joint_type=null WHERE id=?", exercise);
        assertThat(jdbc.queryForMap(sql)).containsEntry("joint_type_missing", true).containsEntry("active_primary_missing", false)
                .containsEntry("inactive_muscle_present", false).containsEntry("primary_count", 1L);
    }

    private List<String> rows(String table, boolean omitJointType) {
        assertThat(table).matches("[a-z_]+");
        return jdbc.queryForList("SELECT (to_jsonb(t)" + (omitJointType ? " - 'joint_type'" : "")
                + ")::text AS row FROM " + table + " t ORDER BY row", String.class);
    }

    private Object structure() {
        return List.of(
                jdbc.queryForList("SELECT conrelid::regclass::text,conname,pg_get_constraintdef(oid) FROM pg_constraint WHERE connamespace='public'::regnamespace AND conname<>'standard_exercise_joint_type_check' ORDER BY conrelid,conname"),
                jdbc.queryForList("SELECT indexname,indexdef FROM pg_indexes WHERE schemaname='public' ORDER BY indexname"),
                jdbc.queryForList("SELECT tgrelid::regclass::text,tgname,pg_get_triggerdef(oid) FROM pg_trigger WHERE NOT tgisinternal ORDER BY tgrelid,tgname"));
    }
}
