package com.overload.overload_server.database;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.overload_server.security.SessionMetadata;
import com.overload.overload_server.workout.WorkoutReadService;
import com.overload.testfixture.MixedPersistenceAssertions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
@Testcontainers
class V10MigrationIntegrationTests {
    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_v10_migration_test")
            .withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static final List<String> REMOVED = List.of("workout_change_batches", "sync_operations", "workout_sync_state");
    static final JsonMapper JSON = JsonMapper.builder().build();

    JdbcTemplate jdbc() {
        assertThat(postgres.getDatabaseName()).isEqualTo("overload_v10_migration_test");
        return new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }
    Flyway historical(String target) {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }
    Flyway latest() {
        return Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").target("10").load();
    }
    ConfigurableApplicationContext app(boolean latestSchema) {
        if (!latestSchema) {
            // Historical JDBC provisioning does not validate future JPA entities against V9.
            var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext();
            context.registerBean(javax.sql.DataSource.class, () -> new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(context.getBean(javax.sql.DataSource.class)));
            context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(context.getBean(javax.sql.DataSource.class)));
            context.register(org.springframework.util.ClassUtils.resolveClassName("com.overload.overload_server.account.AccountRepository", getClass().getClassLoader()), AccountProvisioningService.class);
            context.refresh(); return context;
        }
        return new SpringApplicationBuilder(OverloadServerApplication.class).web(WebApplicationType.NONE)
                .run("--spring.profiles.active=integration", "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(), "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.flyway.target=17",
                        "--GOOGLE_OIDC_ENABLED=false", "--OTP_EMAIL_ENABLED=false", "--logging.level.root=WARN", "--spring.main.banner-mode=off");
    }

    @Test void emptyDatabaseMigratesThroughV10AndBootsCurrentMappings() throws Exception {
        var flyway = latest();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(10);
        assertRemoved();
        assertThat(jdbc().queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
        flyway.validate();
        try (var context = app(true)) {
            MixedPersistenceAssertions.verify(context);
            var account = context.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email("fresh-v10@example.test"), null);
            var read = context.getBean(WorkoutReadService.class);
            assertThat(read.exercises(account.id()).exercises()).isEmpty();
            assertThat(read.settings(account.id()).restLowSeconds()).isEqualTo(180);
        }
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"8", "9"})
    void populatedUpgradePreservesAllOtherRowsAndExistingSeed(String start) throws Exception {
        historical(start).migrate();
        var jdbc = jdbc();
        var owner = UUID.randomUUID(); var google = UUID.randomUUID(); var exercise = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,data_generation,nickname,created_at,updated_at) VALUES (?,9007199254740993,'preserved','2026-10-01T00:00:00.123456Z','2026-10-02T00:00:00.654321Z')", owner);
        jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name) VALUES (?,?,9007199254740993,'  同名 벤치 Ω  ')", exercise, owner);
        jdbc.update("INSERT INTO user_workout_settings(user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds) VALUES (?,9007199254740993,17,29,43)", owner);
        jdbc.update("INSERT INTO auth_identities(id,user_id,provider,issuer,subject) VALUES (?,?,'google','https://accounts.google.com','isolated-google-subject')", google, owner);
        String session = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        jdbc.update("INSERT INTO spring_session VALUES (?,?,?,?,?,?,?)", session, UUID.randomUUID().toString(), now, now, 7776000, now+7776000000L, owner.toString());
        var at = Instant.parse("2026-10-01T00:00:00.123456Z");
        var bytes = new org.springframework.core.serializer.support.SerializingConverter().convert(new SessionMetadata(owner,at,at.plusSeconds(7776000),at));
        jdbc.update("INSERT INTO spring_session_attributes VALUES (?,?,?)", session, SessionMetadata.ATTRIBUTE, bytes);
        historical("9").migrate();
        jdbc.update("UPDATE exercise_definitions SET version=7,deleted_at='2026-10-02T00:00:00.654321Z',intent_stamps='{\"name\":{\"legacy\":true}}'::jsonb WHERE id=?", exercise);
        jdbc.update("UPDATE user_workout_settings SET version=11,intent_stamps='{\"restLowSeconds\":{\"legacy\":true}}'::jsonb WHERE user_id=?", owner);
        UUID emailOwner;
        try (var context = app(false)) {
            emailOwner = context.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email("preserved-v10@example.test"), null).id();
        }
        seedObsolete(owner);
        var protectedBefore = protectedRows(); var historyBefore = history();
        var constraints = constraints(); var indexes = indexes();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype='f' AND conrelid IN ('workout_sync_state'::regclass,'sync_operations'::regclass,'workout_change_batches'::regclass)", Integer.class)).isEqualTo(6);
        assertThat(indexes).hasSize(6);
        var flyway = latest(); assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(protectedRows()).isEqualTo(protectedBefore);
        assertThat(history().subList(0,9)).isEqualTo(historyBefore);
        assertThat(jdbc.queryForObject("SELECT attribute_bytes FROM spring_session_attributes WHERE session_primary_id=?", byte[].class,session)).isEqualTo(bytes);
        assertRemoved(); flyway.validate(); assertThat(flyway.migrate().migrationsExecuted).isZero();
        try (var context = app(true)) {
            MixedPersistenceAssertions.verify(context);
            var read = context.getBean(WorkoutReadService.class);
            assertThat(read.exercises(owner).dataGeneration()).isEqualTo(9007199254740993L);
            assertThat(read.exercises(owner).exercises()).hasSize(1);
            assertThat(read.settings(owner).restLowSeconds()).isEqualTo(17);
            var provisioning = context.getBean(AccountProvisioningService.class);
            assertThat(provisioning.provision(VerifiedIdentity.email("preserved-v10@example.test"),null).id()).isEqualTo(emailOwner);
            assertThat(provisioning.provision(VerifiedIdentity.google(VerifiedIdentity.GOOGLE_ISSUER,"isolated-google-subject"),null).id()).isEqualTo(owner);
            assertThat(read.exercises(owner).exercises()).hasSize(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"8", "9"})
    void unexpectedDependencyRollsBackV10ButPreservesCommittedV9(String start) {
        historical(start).migrate(); var jdbc=jdbc(); var owner=UUID.randomUUID();
        var exercise=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id) VALUES (?)",owner);
        jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name) VALUES (?,?,0,'rollback exercise')",exercise,owner);
        jdbc.update("INSERT INTO user_workout_settings(user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds) VALUES (?,0,180,120,90)",owner);
        // V9 is a separately committed migration even if a later V10 fails.
        historical("9").migrate();
        jdbc.update("UPDATE exercise_definitions SET intent_stamps='{\"legacy\":true}'::jsonb WHERE id=?",exercise);
        jdbc.update("UPDATE user_workout_settings SET intent_stamps='{\"legacy\":true}'::jsonb WHERE user_id=?",owner);
        seedObsolete(owner,0);
        jdbc.execute("CREATE VIEW v10_unexpected_dependency AS SELECT intent_stamps FROM user_workout_settings");
        var rowsBefore=protectedRows(); var historyBefore=history(); var constraintsBefore=constraints();
        var exerciseStamp=jdbc.queryForObject("SELECT intent_stamps::text FROM exercise_definitions WHERE id=?",String.class,exercise);
        var settingsStamp=jdbc.queryForObject("SELECT intent_stamps::text FROM user_workout_settings WHERE user_id=?",String.class,owner);
        var obsoleteBefore=new LinkedHashMap<String,Object>();
        for(var table:REMOVED) obsoleteBefore.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class));
        var flyway=latest();
        assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class).satisfies(failure -> {
            var root=failure;
            while(root.getCause()!=null) root=root.getCause();
            assertThat(root).isInstanceOf(SQLException.class);
            assertThat(((SQLException)root).getSQLState()).isEqualTo("2BP01");
        });
        assertThat(protectedRows()).isEqualTo(rowsBefore); assertThat(history()).isEqualTo(historyBefore);
        assertThat(constraints()).isEqualTo(constraintsBefore);
        assertThat(history()).hasSize(9);
        assertThat(history().getLast()).containsEntry("version", "9").containsEntry("success", true);
        assertThat(jdbc.queryForObject("SELECT intent_stamps::text FROM exercise_definitions WHERE id=?",String.class,exercise)).isEqualTo(exerciseStamp);
        assertThat(jdbc.queryForObject("SELECT intent_stamps::text FROM user_workout_settings WHERE user_id=?",String.class,owner)).isEqualTo(settingsStamp);
        for(var table:REMOVED) assertThat(jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class)).isEqualTo(obsoleteBefore.get(table));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('exercise_definitions','user_workout_settings') AND column_name='intent_stamps'",Integer.class)).isEqualTo(2);
    }

    void seedObsolete(UUID owner) { seedObsolete(owner,9007199254740993L); }
    void seedObsolete(UUID owner,long generation) {
        var jdbc=jdbc(); var tx=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(status->{
            UUID operation=UUID.randomUUID(); String fingerprint="a".repeat(64);
            jdbc.update("INSERT INTO workout_sync_state VALUES (?,?,1)",owner,generation);
            String ack=JSON.writeValueAsString(Map.of("ackSchemaVersion",1,"userId",owner.toString(),"dataGeneration",Long.toString(generation),"operationId",operation.toString(),"fingerprint",fingerprint,"resultVersions",List.of(),"result",Map.of()));
            jdbc.update("INSERT INTO sync_operations(user_id,data_generation,operation_id,fingerprint,command_schema_version,command_type,state,http_status,ack_body,change_revision) VALUES (?,?,?,?,1,'CREATE_EXERCISE','SUCCEEDED',200,?::jsonb,1)",owner,generation,operation,fingerprint,ack);
            jdbc.update("INSERT INTO workout_change_batches(user_id,data_generation,revision,operation_id,changes) VALUES (?,?,1,?,'[{\"legacy\":true}]'::jsonb)",owner,generation,operation);
            UUID next=UUID.randomUUID(); String nextAck=JSON.writeValueAsString(Map.of("ackSchemaVersion",1,"userId",owner.toString(),"dataGeneration",Long.toString(generation),"operationId",next.toString(),"fingerprint",fingerprint,"resultVersions",List.of(),"result",Map.of()));
            jdbc.update("INSERT INTO sync_operations(user_id,data_generation,operation_id,fingerprint,command_schema_version,command_type,state,http_status,ack_body,origin_operation_id,origin_fingerprint) VALUES (?,?,?,?,1,'RENAME_EXERCISE','SUCCEEDED',200,?::jsonb,?,?)",owner,generation,next,fingerprint,nextAck,operation,fingerprint);
            UUID rejected=UUID.randomUUID(); String intent=JSON.writeValueAsString(Map.of("commandSchemaVersion",1,"expectedUserId",owner.toString(),"dataGeneration",Long.toString(generation),"operationId",rejected.toString(),"type","RENAME_EXERCISE"));
            jdbc.update("INSERT INTO sync_operations(user_id,data_generation,operation_id,fingerprint,command_schema_version,command_type,state,http_status,rejection_code,intent_command) VALUES (?,?,?,?,1,'RENAME_EXERCISE','REJECTED',409,'VERSION_CONFLICT',?::jsonb)",owner,generation,rejected,fingerprint,intent);
        });
    }
    Map<String,List<String>> protectedRows() {
        var jdbc=jdbc(); var snapshot=new LinkedHashMap<String,List<String>>();
        var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name",String.class);
        for(var table:tables) if(!REMOVED.contains(table)&&!table.equals("flyway_schema_history")) {
            if(!table.matches("[a-z_]+")) throw new IllegalStateException("Unexpected table name");
            String row=table.equals("exercise_definitions")||table.equals("user_workout_settings")?"(to_jsonb(t)-'intent_stamps')::text":"to_jsonb(t)::text";
            snapshot.put(table,jdbc.queryForList("SELECT "+row+" FROM "+table+" t ORDER BY "+row,String.class));
        }
        return snapshot;
    }
    List<Map<String,Object>> history() { return jdbc().queryForList("SELECT installed_rank,version,script,checksum,success FROM flyway_schema_history ORDER BY installed_rank"); }
    List<Map<String,Object>> constraints() { return jdbc().queryForList("SELECT conrelid::regclass::text AS table_name,conname,contype::text,condeferrable,condeferred,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE conrelid IN ('workout_sync_state'::regclass,'sync_operations'::regclass,'workout_change_batches'::regclass) ORDER BY table_name,conname"); }
    List<Map<String,Object>> indexes() { return jdbc().queryForList("SELECT tablename,indexname,indexdef FROM pg_indexes WHERE schemaname='public' AND tablename IN ('workout_sync_state','sync_operations','workout_change_batches') ORDER BY tablename,indexname"); }
    void assertRemoved() {
        var jdbc=jdbc(); for(var table:REMOVED) assertThat(jdbc.queryForObject("SELECT to_regclass(?)::text",String.class,"public."+table)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name IN ('exercise_definitions','user_workout_settings') AND column_name='intent_stamps'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND ((table_name='exercise_definitions' AND column_name IN ('version','deleted_at')) OR (table_name='user_workout_settings' AND column_name='version'))",Integer.class)).isEqualTo(3);
    }
}
