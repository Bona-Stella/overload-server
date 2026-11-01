package com.overload.overload_server.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres") @Testcontainers
class V15MigrationIntegrationTests {
    @Container final PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_v15_isolated_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    @Test void migrationPreservesEveryV14FactAndChecksumWithoutChangingClosure() {
        assertThat(postgres.getMappedPort(5432)).isNotIn(5432,5433);assertThat(postgres.getDatabaseName()).isEqualTo("overload_v15_isolated_test");
        var jdbc=new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("14").load().migrate();
        UUID owner=UUID.randomUUID(),routine=UUID.randomUUID(),definition=UUID.randomUUID(),placement=UUID.randomUUID(),session=UUID.randomUUID(),item=UUID.randomUUID(),device=UUID.randomUUID(),approval=UUID.randomUUID(),performed=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES (?)",owner);
        jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name) VALUES (?,?,0,'preserve')",definition,owner);
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,version,created_at,updated_at) VALUES (?,?,0,'preserve',1,now(),now())",routine,owner);
        jdbc.update("INSERT INTO routine_weight_placements(id,user_id,data_generation,routine_id,exercise_definition_id,position,set_count,min_reps,max_reps,initial_weight,approved_weight,created_at,updated_at) VALUES (?,?,0,?,?,0,3,6,10,60,62.5,now(),now())",placement,owner,routine,definition);
        String initial="""
                {"schemaVersion":1,"routineId":"%s","routineName":"preserve","routineVersion":"0",
                "weightExercises":[{"sessionExerciseId":"%s","sourcePlacementId":"%s","exerciseDefinitionId":"%s","exerciseName":"preserve","setCount":3,"minReps":6,"maxReps":10,"initialWeightKg":"60","approvedWeightKg":"60"}],"cardioActivities":[]}
                """.formatted(routine,item,placement,definition);
        String plan="""
                {"schemaVersion":1,"weightExercises":[{"sessionExerciseId":"%s","setCount":3,"minReps":6,"maxReps":10}],"cardioActivities":[]}
                """.formatted(item);
        jdbc.update("INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,status,version,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,writer_epoch,local_revision,initial_plan_snapshot,last_workout_synced_at,created_at,updated_at,applied_plan_snapshot,last_snapshot_hash,last_snapshot_saved_at) VALUES (?,?,0,?,'ACTIVE',1,'2026-10-31T10:00:00Z','2026-10-31','UTC',?,?,0,1,?::jsonb,now(),now(),now(),?::jsonb,?,now())",session,owner,routine,device,device,initial,plan,"a".repeat(64));
        jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,exercise_definition_id) VALUES (?,?,?,0,'WEIGHT',?)",item,session,owner,definition);
        jdbc.update("INSERT INTO session_weight_approval_requests(id,session_id,session_exercise_id,sequence,expected_routine_version,previous_weight,next_weight,occurred_at,status,routine_version_before,routine_version_after) VALUES (?,?,?,1,0,60,62.5,'2026-10-31T10:01:00Z','APPLIED',0,1)",approval,session,item);
        jdbc.update("INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_request_id,approval_state_at_recording,approved_weight_at_recording) VALUES (?,?,?,4,62.5,8,'2026-10-31T10:02:00Z','APPROVAL_REQUEST',?,'APPLIED',62.5)",performed,session,item,approval);
        jdbc.update("INSERT INTO progress_change_events(id,user_id,data_generation,routine_id,placement_id,routine_name,exercise_name,previous_weight,next_weight,occurred_at,recorded_at,source,session_id,session_exercise_id,approval_request_id) VALUES (?,?,0,?,?,'preserve','preserve',60,62.5,'2026-10-31T10:01:00Z',now(),'WORKOUT',?,?,?)",UUID.randomUUID(),owner,routine,placement,session,item,approval);
        jdbc.update("INSERT INTO record_change_events(id,session_id,record_kind,record_id,change_kind,before_value,after_value,recorded_at) VALUES (?,?,'SET',?,'CORRECTED','{\"reps\":9}','{\"reps\":8}',now())",UUID.randomUUID(),session,performed);
        jdbc.update("UPDATE workout_sessions SET status='FINISHED',ended_at='2026-10-31T10:10:00Z',closed_at=now(),completion_assessment_snapshot='{\"schemaVersion\":1,\"preserved\":true}'::jsonb WHERE id=?",session);
        for(String status:List.of("ACTIVE","AUTO_CLOSED")) {
            UUID other=UUID.randomUUID(),otherItem=UUID.randomUUID();
            jdbc.update("INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,status,version,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,writer_epoch,local_revision,initial_plan_snapshot,last_workout_synced_at,created_at,updated_at,applied_plan_snapshot,last_snapshot_hash,last_snapshot_saved_at,ended_at,closed_at,completion_assessment_snapshot) SELECT ?,user_id,data_generation,source_routine_id,?,version,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,writer_epoch,local_revision,?::jsonb,last_workout_synced_at,created_at,updated_at,?::jsonb,last_snapshot_hash,last_snapshot_saved_at,NULL,CASE WHEN ?='AUTO_CLOSED' THEN now() ELSE NULL END,NULL FROM workout_sessions WHERE id=?",
                    other,status,initial.replace(item.toString(),otherItem.toString()),plan.replace(item.toString(),otherItem.toString()),status,session);
            jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,exercise_definition_id) VALUES (?,?,?,0,'WEIGHT',?)",otherItem,other,owner,definition);
        }
        var tables=jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename",String.class);
        var rows=new LinkedHashMap<String,List<String>>();
        for(String table:tables)rows.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class));
        var latest=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("15").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        for(String table:tables){
            String value=switch(table){case "workout_sessions"->"(to_jsonb(t)-'deleted_at')";case "session_weight_approval_requests"->"(to_jsonb(t)-'created_after_closure')";case "record_change_events"->"(to_jsonb(t)-'change_context')";default->"to_jsonb(t)";};
            String filter=table.equals("flyway_schema_history")?" WHERE installed_rank<=14":"";
            assertThat(jdbc.queryForList("SELECT "+value+"::text FROM "+table+" t"+filter+" ORDER BY "+value+"::text",String.class)).as(table).isEqualTo(rows.get(table));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_sessions WHERE status='FINISHED' AND deleted_at IS NULL AND completion_assessment_snapshot->>'preserved'='true'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT created_after_closure FROM session_weight_approval_requests WHERE id=?",Boolean.class,approval)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_sessions WHERE status IN ('ACTIVE','AUTO_CLOSED') AND ended_at IS NULL AND completion_assessment_snapshot IS NULL AND deleted_at IS NULL",Integer.class)).isEqualTo(2);
        latest.validate();assertThat(latest.migrate().migrationsExecuted).isZero();
    }
}
