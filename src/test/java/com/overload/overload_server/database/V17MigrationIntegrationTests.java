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
class V17MigrationIntegrationTests {
    @Container final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_catalog_v17_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    JdbcTemplate jdbc;
    Flyway flyway(String target) {
        var config=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        return (target==null?config:config.target(target)).load();
    }
    @BeforeEach void v16() {
        assertThat(postgres.getMappedPort(5432)).isNotIn(5432,5433);
        assertThat(postgres.getDatabaseName()).isEqualTo("overload_catalog_v17_test");
        jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        flyway("16").migrate();
    }
    @Test void emptyV16UpgradesWithoutChangingHistoryOwnershipTriggersOrUniqueIndexes() {
        var history=jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        var ownership=jdbc.queryForList("SELECT conname,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE contype='f' AND confrelid IN ('users'::regclass,'routines'::regclass,'workout_sessions'::regclass) ORDER BY conname");
        var indexes=jdbc.queryForList("SELECT indexname,indexdef FROM pg_indexes WHERE tablename IN ('routines','routine_weight_placements','session_exercises','performed_sets','workout_sessions') ORDER BY indexname");
        var triggers=jdbc.queryForList("SELECT tgname,pg_get_triggerdef(oid) AS definition FROM pg_trigger WHERE tgrelid='workout_sessions'::regclass AND NOT tgisinternal ORDER BY tgname");
        UUID owner=UUID.randomUUID(),personal=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES (?)",owner);
        jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name) VALUES (?,?,0,'preserved personal')",personal,owner);
        var legacy=jdbc.queryForMap("SELECT * FROM exercise_definitions WHERE id=?",personal);
        var latest=flyway(null);assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);latest.validate();
        assertThat(latest.info().current().getVersion().toString()).isEqualTo("17");assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE installed_rank<=16 ORDER BY installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForMap("SELECT * FROM exercise_definitions WHERE id=?",personal)).isEqualTo(legacy);
        assertThat(jdbc.queryForList("SELECT conname,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE contype='f' AND confrelid IN ('users'::regclass,'routines'::regclass,'workout_sessions'::regclass) ORDER BY conname")).isEqualTo(ownership);
        assertThat(jdbc.queryForList("SELECT indexname,indexdef FROM pg_indexes WHERE tablename IN ('routines','routine_weight_placements','session_exercises','performed_sets','workout_sessions') ORDER BY indexname")).isEqualTo(indexes);
        assertThat(jdbc.queryForList("SELECT tgname,pg_get_triggerdef(oid) AS definition FROM pg_trigger WHERE tgrelid='workout_sessions'::regclass AND NOT tgisinternal ORDER BY tgname")).isEqualTo(triggers);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype='f' AND confrelid='exercise_definitions'::regclass AND conrelid IN ('routine_weight_placements'::regclass,'session_exercises'::regclass)",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype='f' AND confrelid='standard_exercise'::regclass AND conrelid IN ('routine_weight_placements'::regclass,'session_exercises'::regclass)",Integer.class)).isEqualTo(2);
        var checks=jdbc.queryForList("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid='workout_sessions'::regclass AND contype='c' AND pg_get_constraintdef(oid) LIKE '%schemaVersion%'",String.class);
        assertThat(checks).hasSize(3).allSatisfy(c->assertThat(c).contains("'2'::jsonb").doesNotContain("'1'::jsonb"));
        UUID routine=UUID.randomUUID(),standard=UUID.randomUUID(),manufacturer=UUID.randomUUID(),session=UUID.randomUUID(),item=UUID.randomUUID();
        jdbc.update("INSERT INTO standard_exercise(id,name) VALUES (?,'standard')",standard);
        jdbc.update("INSERT INTO manufacturer(id,name) VALUES (?,'manufacturer')",manufacturer);
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'routine',now(),now())",routine,owner);
        String placement="INSERT INTO routine_weight_placements(id,user_id,data_generation,routine_id,standard_exercise_id,position,set_count,min_reps,max_reps,initial_weight,approved_weight,created_at,updated_at,manufacturer_id,load_type) VALUES (?,?,0,?,?,0,3,6,10,60,60,now(),now(),?,?)";
        assertThatThrownBy(()->jdbc.update(placement,UUID.randomUUID(),owner,routine,personal,manufacturer,"PLATE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update(placement,UUID.randomUUID(),owner,routine,standard,UUID.randomUUID(),"PLATE")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update(placement,UUID.randomUUID(),owner,routine,standard,manufacturer,"WEIGHT_STACK")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(placement,UUID.randomUUID(),owner,routine,standard,manufacturer,"PLATE");
        String insert="INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,initial_plan_snapshot,applied_plan_snapshot,last_workout_synced_at,created_at,updated_at) VALUES (?,?,0,?,now(),current_date,'UTC',?,?,?::jsonb,?::jsonb,now(),now(),now())";
        assertThatThrownBy(()->jdbc.update(insert,session,owner,routine,UUID.randomUUID(),UUID.randomUUID(),"{\"schemaVersion\":1}","{\"schemaVersion\":2}")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update(insert,session,owner,routine,UUID.randomUUID(),UUID.randomUUID(),"{\"schemaVersion\":2}","{\"schemaVersion\":1}")).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(insert,session,owner,routine,UUID.randomUUID(),UUID.randomUUID(),"{\"schemaVersion\":2}","{\"schemaVersion\":2}");
        String registry="INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,standard_exercise_id) VALUES (?,?,?,0,?,?)";
        assertThatThrownBy(()->jdbc.update(registry,item,session,owner,"WEIGHT",null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update(registry,item,session,owner,"CARDIO",standard)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(registry,item,session,owner,"WEIGHT",standard);
        UUID set=UUID.randomUUID();
        assertThatThrownBy(()->jdbc.update("INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording,manufacturer_id,load_type) VALUES (?,?,?,2,60,8,now(),'TODAY_ONLY','TODAY_ONLY',?,'PLATE')",UUID.randomUUID(),session,item,UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording,manufacturer_id,load_type) VALUES (?,?,?,2,60,8,now(),'TODAY_ONLY','TODAY_ONLY',?,'FREE_WEIGHT')",UUID.randomUUID(),session,item,manufacturer)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording,manufacturer_id,load_type) VALUES (?,?,?,1,60,8,now(),'TODAY_ONLY','TODAY_ONLY',?,'PLATE')",set,session,item,manufacturer);
        assertThatThrownBy(()->jdbc.update("UPDATE performed_sets SET load_type='STACK' WHERE id=?",set)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE performed_sets SET manufacturer_id=null WHERE id=?",set)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workout_sessions SET initial_plan_snapshot='{\"schemaVersion\":2,\"changed\":true}' WHERE id=?",session)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE workout_sessions SET status='FINISHED',ended_at=now(),closed_at=now(),completion_assessment_snapshot='{\"schemaVersion\":1}' WHERE id=?",session)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void populatedV16FailsAtomicallyWithoutDeletingOrReinterpretingAnything() {
        UUID owner=UUID.randomUUID(),routine=UUID.randomUUID();jdbc.update("INSERT INTO users(id) VALUES (?)",owner);
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'existing',now(),now())",routine,owner);
        var before=jdbc.queryForList("SELECT * FROM routines");var history=jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        assertThatThrownBy(()->flyway(null).migrate()).hasStackTraceContaining("V17 requires empty routine/session data");
        assertThat(jdbc.queryForList("SELECT * FROM routines")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='routine_weight_placements' AND column_name='exercise_definition_id'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='performed_sets' AND column_name='manufacturer_id'",Integer.class)).isZero();
    }
}
