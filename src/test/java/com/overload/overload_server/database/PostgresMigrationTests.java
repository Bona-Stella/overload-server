package com.overload.overload_server.database;

import com.overload.overload_server.OverloadServerApplication;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
@Testcontainers
class PostgresMigrationTests {

	@Container
	private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_task007_test")
			.withUsername("overload_test")
			.withPassword(UUID.randomUUID().toString());

	@Test
	void migratesEmptyDatabaseOnceAndPreservesUsersAcrossRestart() throws Exception {
		try (var connection = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			 var statement = connection.createStatement();
			 var tables = statement.executeQuery(
					 "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")) {
			assertThat(tables.next()).isTrue();
			assertThat(tables.getInt(1)).isZero();
		}

		var userId = UUID.randomUUID();
		List<java.util.Map<String, Object>> history;
		try (var context = startApplication(WebApplicationType.NONE)) {
			var jdbc = context.getBean(JdbcTemplate.class);
			var flyway = context.getBean(Flyway.class);
			assertThat(jdbc.queryForList("""
					SELECT table_name FROM information_schema.tables
					WHERE table_schema = 'public' ORDER BY table_name
					""", String.class)).containsExactly("auth_identities", "cardio_activity_catalog", "cardio_results", "daily_training_assessments", "deload_analysis_versions", "deload_periods", "deload_temporary_plans", "deload_weekly_assessments", "email_otp_challenges", "email_otp_limits",
					"email_otp_login_claims", "email_otp_rate_limits", "exercise_alias", "exercise_definitions", "exercise_muscle",
					"flyway_schema_history", "google_identity_change_versions", "manufacturer", "muscle", "performed_sets", "progress_change_events", "reauth_email_candidates", "record_change_events", "routine_cardio_placements", "routine_plan_history", "routine_weight_placements", "routines", "session_exercises", "session_weight_approval_requests", "spring_session", "spring_session_attributes", "standard_exercise", "user_reauthentication_emails", "user_workout_settings", "users", "weekly_schedule_versions", "workout_calendar_control", "workout_sessions");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isZero();
			assertThat(flyway.info().current().getVersion().toString()).isEqualTo("27");
			assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
			assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
			flyway.validate();
			history = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
			assertThat(history).hasSize(27);
            assertThat(history.get(25)).containsEntry("version", "26").containsEntry("script", "V26__add_excluded_deload_weekly_assessments.sql").containsEntry("success", true);
            assertThat(history.get(21)).containsEntry("version", "22").containsEntry("script", "V22__add_bodyweight_additional_load.sql").containsEntry("success", true);
            assertThat(history.get(20)).containsEntry("version", "21").containsEntry("script", "V21__add_actual_cardio_measurements.sql").containsEntry("success", true);
			assertThat(history.get(19)).containsEntry("version", "20").containsEntry("script", "V20__allow_immediate_first_weekly_schedule.sql").containsEntry("success", true);
			assertThat(history.get(18)).containsEntry("version", "19").containsEntry("script", "V19__add_weekly_schedule_and_routine_plan_history.sql").containsEntry("success", true);
			assertThat(history.get(17)).containsEntry("version", "18")
					.containsEntry("script", "V18__add_standard_exercise_joint_type.sql").containsEntry("success", true);
			assertThat(history.getFirst()).containsEntry("version", "1")
					.containsEntry("script", "V1__create_users.sql").containsEntry("success", true);
			assertThat(history.get(1)).containsEntry("version", "2")
					.containsEntry("script", "V2__create_account_provisioning.sql").containsEntry("success", true);
			assertThat(history.get(2)).containsEntry("version", "3")
					.containsEntry("script", "V3__create_spring_session.sql").containsEntry("success", true);
			assertThat(history.get(3)).containsEntry("version", "4")
					.containsEntry("script", "V4__create_email_otp.sql").containsEntry("success", true);
			assertThat(history.get(4)).containsEntry("version", "5")
					.containsEntry("script", "V5__add_email_reauthentication.sql").containsEntry("success", true);
			assertThat(history.get(5)).containsEntry("version","6").containsEntry("script","V6__add_reauthentication_emails.sql").containsEntry("success",true);
			assertThat(history.get(6)).containsEntry("version","7").containsEntry("script","V7__preserve_retired_email_otp_identity_references.sql").containsEntry("success",true);
			assertThat(history.get(7)).containsEntry("version","8").containsEntry("script","V8__preserve_google_unlink_references.sql").containsEntry("success",true);
			assertThat(history.get(8)).containsEntry("version","9").containsEntry("script","V9__add_workout_write_foundation.sql").containsEntry("success",true);
			assertThat(history.get(9)).containsEntry("version","10").containsEntry("script","V10__remove_obsolete_workout_command_storage.sql").containsEntry("success",true);
			assertThat(history.get(10)).containsEntry("version","11").containsEntry("script","V11__create_routine_management.sql").containsEntry("success",true);
			assertThat(history.get(13)).containsEntry("version","14").containsEntry("script","V14__add_workout_session_closure.sql").containsEntry("success",true);
			assertThat(history.get(14)).containsEntry("version","15").containsEntry("script","V15__add_closed_workout_record_edits.sql").containsEntry("success",true);
			assertThat(history.get(15)).containsEntry("version","16").containsEntry("script","V16__create_standard_exercise_catalog.sql").containsEntry("success",true);
			assertThat(history.get(12)).containsEntry("version","13").containsEntry("script","V13__add_workout_snapshots.sql").containsEntry("success",true);
			assertThat(history.get(11)).containsEntry("version","12").containsEntry("script","V12__create_workout_sessions.sql").containsEntry("success",true);
            jdbc.update("INSERT INTO users (id) VALUES (?)", userId);
			var user = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", userId);
			assertThat(user).containsEntry("id", userId).containsEntry("data_generation", 0L)
					.containsEntry("nickname", null);
			assertThat(jdbc.queryForObject("SELECT created_at FROM users WHERE id = ?",
					OffsetDateTime.class, userId)).isNotNull();
			assertThat(jdbc.queryForObject("SELECT created_at = updated_at FROM users WHERE id = ?",
					Boolean.class, userId)).isTrue();
		}

		try (var context = startApplication(WebApplicationType.NONE)) {
			var jdbc = context.getBean(JdbcTemplate.class);
			assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank"))
					.isEqualTo(history);
			assertThat(jdbc.queryForObject("SELECT id FROM users", UUID.class)).isEqualTo(userId);
			assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
		}
	}

    @Test void v27AddsPeriodSchemaWithoutChangingExistingV26FactsOrChecksums() {
        var old=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("26").load();old.migrate();
        var jdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        UUID owner=UUID.randomUUID(),routine=UUID.randomUUID(),device=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,created_at,updated_at) VALUES (?,now(),now())",owner);
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'preserve V27 Ω',now(),now())",routine,owner);
        jdbc.update("INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,initial_plan_snapshot,applied_plan_snapshot,last_workout_synced_at,created_at,updated_at) VALUES (?,?,0,?,'2026-11-01T00:00:00Z','2026-11-01','UTC',?,?,'{\"schemaVersion\":2}','{\"schemaVersion\":2}','2026-11-01T00:00:00Z',now(),now())",UUID.randomUUID(),owner,routine,device,device);
        var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name<>'flyway_schema_history' ORDER BY table_name",String.class);var before=new java.util.HashMap<String,List<String>>();
        for(var table:tables)before.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class));
        var checksums=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
        var latest=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        for(var table:tables) {String value=table.equals("workout_sessions")?"(to_jsonb(t)-'deload_period_id'-'deload_plan_id')":"to_jsonb(t)";assertThat(jdbc.queryForList("SELECT "+value+"::text FROM "+table+" t ORDER BY "+value+"::text",String.class)).as(table).isEqualTo(before.get(table));}
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version::integer<=26 ORDER BY installed_rank")).isEqualTo(checksums);latest.validate();assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_periods",Integer.class)).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_temporary_plans",Integer.class)).isZero();
    }
    @Test void v26AddsExcludedStateWithoutRewritingAnyV25RowsOrChecksums() {
        var old=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("25").load();old.migrate();
        var jdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        var owner=UUID.randomUUID();jdbc.update("INSERT INTO users(id,created_at,updated_at) VALUES (?,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')",owner);
        var routine=UUID.randomUUID();var device=UUID.randomUUID();
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'preserve V26 Ω',now(),now())",routine,owner);
        jdbc.update("""
            INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,
                initial_plan_snapshot,applied_plan_snapshot,last_snapshot_hash,last_workout_synced_at,created_at,updated_at)
            VALUES(?,?,0,?,'2026-02-01T00:00:00Z','2026-02-01','UTC',?,?,
                '{"schemaVersion":2,"routineName":"preserve V26 Ω","weightExercises":[],"cardioActivities":[]}',
                '{"schemaVersion":2,"weightExercises":[],"cardioActivities":[]}',?,'2026-02-01T00:00:00Z',now(),now())
            """,UUID.randomUUID(),owner,routine,device,device,"c".repeat(64));
        jdbc.update("""
            INSERT INTO deload_analysis_versions(user_id,data_generation,version,enabled,epoch,recorded_at,epoch_started_at,time_zone,reason)
            VALUES (?,0,0,true,0,'2026-11-02T00:00:00Z','2026-11-02T00:00:00Z','UTC','ADOPTED_INITIAL')
            """,owner);
        jdbc.update("""
            INSERT INTO deload_weekly_assessments(user_id,data_generation,epoch,week_start,status,e_numerator,e_denominator,n,operation,contribution_numerator,contribution_denominator,rule_version,revision,input_revision,confirmed_at,updated_at)
            VALUES (?,0,0,'2026-11-02','CONFIRMED',2,1,2,'ADD',1,1,1,3,'existing-add',now(),now()),
                   (?,0,0,'2026-11-09','CONFIRMED',1,1,1,'RESET',0,1,1,4,'existing-reset',now(),now()),
                   (?,0,0,'2026-11-16','PENDING',NULL,NULL,NULL,NULL,NULL,NULL,1,5,'existing-pending',NULL,now())
            """,owner,owner,owner);
        var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name<>'flyway_schema_history' ORDER BY table_name",String.class);
        var before=new java.util.HashMap<String,List<String>>();
        for(var table:tables)before.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class));
        var checksums=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
        var latest=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("26").load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        for(var table:tables)assertThat(jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class)).as(table).isEqualTo(before.get(table));
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE version::integer<=25 ORDER BY installed_rank")).isEqualTo(checksums);
        latest.validate();assertThat(latest.migrate().migrationsExecuted).isZero();
        jdbc.update("UPDATE deload_weekly_assessments SET status='EXCLUDED',e_numerator=NULL,e_denominator=NULL,n=NULL,operation=NULL,contribution_numerator=NULL,contribution_denominator=NULL,confirmed_at=now(),updated_at=now() WHERE user_id=? AND week_start='2026-11-02'",owner);
        for(var bad:List.of("e_numerator=0","e_denominator=1","n=0","operation='RESET'","contribution_numerator=0","contribution_denominator=1","confirmed_at=NULL","confirmed_at='infinity'","confirmed_at=updated_at+interval '1 hour'","status='OTHER'"))
            assertThatThrownBy(()->jdbc.update("UPDATE deload_weekly_assessments SET "+bad+" WHERE user_id=? AND week_start='2026-11-02'",owner)).as(bad).isInstanceOf(DataIntegrityViolationException.class);
        // The old CONFIRMED arithmetic constraints remain intact after the new branch is introduced.
        assertThatThrownBy(()->jdbc.update("UPDATE deload_weekly_assessments SET contribution_numerator=1 WHERE user_id=? AND week_start='2026-11-09'",owner)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM deload_weekly_assessments WHERE user_id=? AND week_start='2026-11-02'",String.class,owner)).isEqualTo("EXCLUDED");
    }
    @Test void v25OnlyAddsSchemaWithoutAdoptingExistingAccountsOrChangingV24Facts() {
        var old=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("24").load();old.migrate();
        var jdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        UUID user=UUID.randomUUID(),routine=UUID.randomUUID(),session=UUID.randomUUID(),device=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,nickname,created_at,updated_at) VALUES (?,'legacy','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')",user);
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'preserve',now(),now())",routine,user);
        jdbc.update("""
            INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,
                initial_plan_snapshot,applied_plan_snapshot,last_snapshot_hash,last_workout_synced_at,created_at,updated_at)
            VALUES(?,?,0,?,'2026-02-01T00:00:00Z','2026-02-01','UTC',?,?,
                '{"schemaVersion":2,"routineName":"preserve Ω","weightExercises":[],"cardioActivities":[]}',
                '{"schemaVersion":2,"weightExercises":[],"cardioActivities":[]}',?,'2026-02-01T00:00:00Z',now(),now())
            """,session,user,routine,device,device,"b".repeat(64));
        var tables=jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_name<>'flyway_schema_history' ORDER BY table_name",String.class);
        var before=new java.util.HashMap<String,java.util.List<String>>();
        for(var table:tables)before.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class));
        var history=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
        var latest=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("25").load();assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        for(var table:tables)assertThat(jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class)).isEqualTo(before.get(table));
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE installed_rank<=24 ORDER BY installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_analysis_versions",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_weekly_assessments",Integer.class)).isZero();
        assertThat(latest.migrate().migrationsExecuted).isZero();latest.validate();
    }
    @Test void v24PromotesLatestIntentOnceAndNeverRewritesLegacyReservationRows() {
        var old=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("23").load();old.migrate();
        var jdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
        UUID user=UUID.randomUUID(),routine=UUID.randomUUID();jdbc.update("INSERT INTO users(id) VALUES (?)",user);
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'legacy',now(),now())",routine,user);
        jdbc.update("INSERT INTO weekly_schedule_versions VALUES (?,0,1,'2026-11-02T00:00:00Z','2026-11-02','Asia/Seoul','[]')",user);
        String latest="[{\"dayOfWeek\":2,\"routineId\":\""+routine+"\"}]";
        jdbc.update("INSERT INTO weekly_schedule_versions VALUES (?,0,2,'2026-11-06T00:00:00Z','2026-11-09','Asia/Seoul',?::jsonb)",user,latest);
        UUID session=UUID.randomUUID(),device=UUID.randomUUID();
        jdbc.update("""
            INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,
                initial_plan_snapshot,applied_plan_snapshot,last_snapshot_hash,last_workout_synced_at,created_at,updated_at)
            VALUES(?,?,0,?,'2026-11-02T14:59:00Z','2026-11-02','UTC',?,?,
                '{"schemaVersion":2,"routineName":"legacy Ω","weightExercises":[],"cardioActivities":[]}',
                '{"schemaVersion":2,"weightExercises":[],"cardioActivities":[]}',?,'2026-11-02T14:59:00Z','2026-11-02T14:59:00Z','2026-11-02T14:59:00Z')
            """,session,user,routine,device,device,"a".repeat(64));
        var originalSession=jdbc.queryForObject("SELECT to_jsonb(s)::text FROM workout_sessions s WHERE id=?",String.class,session);
        var before=jdbc.queryForList("SELECT * FROM weekly_schedule_versions WHERE user_id=? ORDER BY version",user);
        var history=jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history ORDER BY installed_rank");
        Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("24").load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM weekly_schedule_versions WHERE user_id=? AND version<3 ORDER BY version",user)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT version,checksum FROM flyway_schema_history WHERE installed_rank<=23 ORDER BY installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT (to_jsonb(s)-'schedule_version_at_start')::text FROM workout_sessions s WHERE id=?",String.class,session)).isEqualTo(originalSession);
        assertThat(jdbc.queryForObject("SELECT schedule_version_at_start FROM workout_sessions WHERE id=?",Long.class,session)).isNull();
        assertThat(jdbc.queryForObject("SELECT baseline_version FROM workout_calendar_control WHERE user_id=?",Long.class,user)).isEqualTo(3L);
        var promoted=jdbc.queryForMap("SELECT * FROM weekly_schedule_versions WHERE user_id=? AND version=3",user);
        assertThat(promoted.get("assignments").toString()).contains(routine.toString());
        jdbc.execute("SELECT initialize_workout_calendar('"+user+"',0)");jdbc.execute("SELECT initialize_workout_calendar('"+user+"',0)");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_schedule_versions WHERE user_id=?",Integer.class,user)).isEqualTo(3);
        assertThat(jdbc.queryForMap("SELECT * FROM weekly_schedule_versions WHERE user_id=? AND version=3",user)).isEqualTo(promoted);
        assertThatThrownBy(()->jdbc.update("UPDATE weekly_schedule_versions SET assignments='[]' WHERE user_id=? AND version=2",user)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT version FROM weekly_schedule_versions WHERE user_id=? AND version>=(SELECT baseline_version FROM workout_calendar_control WHERE user_id=?) ORDER BY version DESC LIMIT 1",Long.class,user,user)).isEqualTo(3L);
    }

	@Test
	void upgradesV1WithoutChangingExistingUsersOrApplyingSeedAtStartup() throws Exception {
		var v1 = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.target("1").load();
		v1.migrate();
		var existingUser = UUID.randomUUID();
		try (var connection = DriverManager.getConnection(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			 var statement = connection.prepareStatement(
					 "INSERT INTO users (id, nickname, data_generation) VALUES (?, 'existing', 7)")) {
			statement.setObject(1, existingUser);
			statement.executeUpdate();
		}
		try (var context = startApplication(WebApplicationType.NONE)) {
			var jdbc = context.getBean(JdbcTemplate.class);
			assertThat(jdbc.queryForMap("SELECT id, nickname, data_generation FROM users"))
					.containsEntry("id", existingUser).containsEntry("nickname", "existing")
					.containsEntry("data_generation", 7L);
			assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version = '1'",
					Integer.class)).isEqualTo(1980645960);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identities", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions", Integer.class)).isZero();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM user_workout_settings", Integer.class)).isZero();
			assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
		}
	}

	@Test
	void upgradesV2WithBothExistingMigrationChecksumsAndUserDataPreserved() {
		var v2 = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.target("2").load();
		v2.migrate();
		var checksums = java.util.Arrays.stream(v2.info().applied()).map(info -> info.getChecksum()).toList();
		try (var context = startApplication(WebApplicationType.NONE)) {
			var latest = context.getBean(Flyway.class);
			assertThat(latest.info().current().getVersion().toString()).isEqualTo("27");
			assertThat(java.util.Arrays.stream(latest.info().applied()).limit(2).map(info -> info.getChecksum()).toList())
					.isEqualTo(checksums);
			assertThat(latest.migrate().migrationsExecuted).isZero();
		}
	}

	@Test
	void upgradesV3WithAllExistingChecksumsAndUserDataUnchanged() throws Exception {
		var old = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
				.target("3").load();
		old.migrate();
		var checksums = java.util.Arrays.stream(old.info().applied()).map(info -> info.getChecksum()).toList();
		var userId = UUID.randomUUID();
		try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			 var insert = connection.prepareStatement("INSERT INTO users (id, data_generation) VALUES (?, 11)")) {
			insert.setObject(1, userId); insert.executeUpdate();
		}
		try (var context = startApplication(WebApplicationType.NONE)) {
			var latest = context.getBean(Flyway.class);
			assertThat(java.util.Arrays.stream(latest.info().applied()).limit(3).map(info -> info.getChecksum()).toList())
					.isEqualTo(checksums);
			assertThat(context.getBean(JdbcTemplate.class).queryForObject("SELECT data_generation FROM users WHERE id = ?",
					Long.class, userId)).isEqualTo(11L);
			assertThat(latest.migrate().migrationsExecuted).isZero();
		}
	}

	@Test
	void upgradesV4PreservingLoginStateCountersSessionBytesAndAllPriorChecksums() {
		var old = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("4").load();
		old.migrate();
		var checksums = java.util.Arrays.stream(old.info().applied()).map(info -> info.getChecksum()).toList();
		var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
		var jdbc = new JdbcTemplate(dataSource);
		var user = UUID.randomUUID(); var identity = UUID.randomUUID(); var challenge = UUID.randomUUID();
		var primary = UUID.randomUUID().toString(); var session = UUID.randomUUID().toString(); var key = "a".repeat(64);
		jdbc.update("INSERT INTO users (id,nickname,data_generation) VALUES (?,'preserved',7)", user);
		jdbc.update("INSERT INTO auth_identities (id,user_id,provider,issuer,subject) VALUES (?,?,'email','overload:email','migration@example.test')", identity, user);
		jdbc.update("INSERT INTO exercise_definitions (id,user_id,data_generation,name) VALUES (?,?,7,'preserved')", UUID.randomUUID(), user);
		jdbc.update("INSERT INTO user_workout_settings (user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds) VALUES (?,7,77,66,55)", user);
		jdbc.update("INSERT INTO email_otp_limits (email_key,purpose,last_issued_at,failed_attempts,failure_window_end) VALUES (?,'LOGIN',CURRENT_TIMESTAMP,3,CURRENT_TIMESTAMP+INTERVAL '30 minutes')", key);
		jdbc.update("""
				INSERT INTO email_otp_challenges (id,email_key,purpose,recipient,binding_hash,verifier,state,issued_at,expires_at,accepted_at,failed_attempts)
				VALUES (?,?,'LOGIN','migration@example.test',?,?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '5 minutes',CURRENT_TIMESTAMP,2)
				""", challenge, key, "b".repeat(64), new byte[32]);
		jdbc.update("UPDATE email_otp_limits SET active_challenge_id=?", challenge);
		jdbc.update("INSERT INTO email_otp_rate_limits VALUES (?,'LOGIN',CURRENT_TIMESTAMP,4)", "c".repeat(64));
		jdbc.update("INSERT INTO spring_session VALUES (?,?,1,2,60,9999999999999,?)", primary, session, user.toString());
		jdbc.update("INSERT INTO spring_session_attributes VALUES (?,'preserved',?)", primary, new byte[]{1,2,3});
		var tables = List.of("users", "auth_identities", "exercise_definitions", "user_workout_settings", "email_otp_limits", "email_otp_rate_limits", "spring_session");
		var snapshots = tables.stream().map(table -> legacyRows(jdbc, table)).toList();
		var bytes = jdbc.queryForObject("SELECT attribute_bytes FROM spring_session_attributes", byte[].class);
		try (var context = startApplication(WebApplicationType.NONE)) {
			var latest = context.getBean(Flyway.class);
			assertThat(java.util.Arrays.stream(latest.info().applied()).limit(4).map(info -> info.getChecksum()).toList()).isEqualTo(checksums);
			assertThat(tables.stream().map(table -> legacyRows(jdbc, table)).toList()).isEqualTo(snapshots);
			assertThat(jdbc.queryForObject("SELECT attribute_bytes FROM spring_session_attributes", byte[].class)).isEqualTo(bytes);
			assertThat(jdbc.queryForMap("SELECT purpose,state,failed_attempts,reauth_user_id,reauth_identity_id FROM email_otp_challenges"))
					.containsEntry("purpose", "LOGIN").containsEntry("state", "ACTIVE").containsEntry("failed_attempts", 2)
					.containsEntry("reauth_user_id", null).containsEntry("reauth_identity_id", null);
			assertThat(latest.migrate().migrationsExecuted).isZero();
			assertThatThrownBy(() -> jdbc.update("UPDATE email_otp_challenges SET purpose='REAUTH' WHERE id=?", challenge))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
	}

	@Test
	void upgradesV5BackfillingOnlyUnambiguousRetainedOtpProofWithoutChangingPriorData() throws Exception {
		var old=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("5").load();old.migrate();
		var checksums=java.util.Arrays.stream(old.info().applied()).map(info->info.getChecksum()).toList();
		var jdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
		var proven=UUID.randomUUID();var bare=UUID.randomUUID();var google=UUID.randomUUID();var ambiguous=UUID.randomUUID();
		for(var user:List.of(proven,bare,google,ambiguous)){jdbc.update("INSERT INTO users(id,nickname,data_generation) VALUES (?,'preserved',7)",user);}
		var provenId=legacyIdentity(jdbc,proven,"proof@example.test");legacyIdentity(jdbc,bare,"unproven@example.test");
		jdbc.update("INSERT INTO auth_identities(id,user_id,provider,issuer,subject) VALUES (?,?,'google','https://accounts.google.com','legacy-google-sub')",UUID.randomUUID(),google);
		var one=legacyIdentity(jdbc,ambiguous,"first@example.test");var two=legacyIdentity(jdbc,ambiguous,"second@example.test");
		legacyProof(jdbc,proven,provenId,"proof@example.test","LOGIN");legacyProof(jdbc,proven,provenId,"proof@example.test","REAUTH");
		legacyProof(jdbc,ambiguous,one,"first@example.test","LOGIN");legacyProof(jdbc,ambiguous,two,"second@example.test","LOGIN");
		var oldKey=legacyHash("email","proof@example.test");
		var newKey=legacyHash("email",proven+"\nproof@example.test");
		jdbc.update("INSERT INTO email_otp_limits(email_key,purpose,last_issued_at,failed_attempts,failure_window_end) VALUES (?,'REAUTH',CURRENT_TIMESTAMP,5,CURRENT_TIMESTAMP+INTERVAL '30 minutes')",oldKey);
		var budget=jdbc.queryForMap("SELECT last_issued_at,failed_attempts,failure_window_end FROM email_otp_limits WHERE email_key=? AND purpose='REAUTH'",oldKey);
		for(var scope:List.of("request-email","verify-email")){
			jdbc.update("INSERT INTO email_otp_rate_limits(bucket_key,purpose,window_started_at,hits) VALUES (?,'REAUTH',CURRENT_TIMESTAMP,5)",legacyHash(scope,"proof@example.test"));
		}
		var oldLimits=jdbc.queryForList("SELECT * FROM email_otp_limits ORDER BY email_key,purpose");
		var oldRates=jdbc.queryForList("SELECT * FROM email_otp_rate_limits ORDER BY bucket_key,purpose");
		var users=jdbc.queryForList("SELECT * FROM users ORDER BY id");var identities=jdbc.queryForList("SELECT * FROM auth_identities ORDER BY id");
		var challenges=jdbc.queryForList("SELECT id,purpose,state,consumed_at FROM email_otp_challenges ORDER BY id");
		try(var context=startApplication(WebApplicationType.NONE)){
			var latest=context.getBean(Flyway.class);
			assertThat(java.util.Arrays.stream(latest.info().applied()).limit(5).map(info->info.getChecksum()).toList()).isEqualTo(checksums);
			assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(users);assertThat(jdbc.queryForList("SELECT * FROM auth_identities ORDER BY id")).isEqualTo(identities);
			assertThat(jdbc.queryForList("SELECT id,purpose,state,consumed_at FROM email_otp_challenges ORDER BY id")).isEqualTo(challenges);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM user_reauthentication_emails",Integer.class)).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT user_id FROM user_reauthentication_emails",UUID.class)).isEqualTo(proven);
			assertThat(jdbc.queryForMap("SELECT email,status,verification_source FROM user_reauthentication_emails")).containsEntry("email","proof@example.test").containsEntry("status","VERIFIED").containsEntry("verification_source","HISTORICAL_OTP");
			assertThat(jdbc.queryForMap("SELECT last_issued_at,failed_attempts,failure_window_end FROM email_otp_limits WHERE email_key=? AND purpose='REAUTH'",newKey)).isEqualTo(budget);
			assertThat(jdbc.queryForObject("SELECT active_challenge_id FROM email_otp_limits WHERE email_key=? AND purpose='REAUTH'",UUID.class,newKey)).isNull();
			assertThat(jdbc.queryForList("SELECT * FROM email_otp_limits ORDER BY email_key,purpose")).containsAll(oldLimits);
			assertThat(jdbc.queryForList("SELECT * FROM email_otp_rate_limits ORDER BY bucket_key,purpose")).containsAll(oldRates).hasSize(oldRates.size()+2);
			for(var scope:List.of("request-email","verify-email")){
				var oldBucket=jdbc.queryForMap("SELECT window_started_at,hits FROM email_otp_rate_limits WHERE bucket_key=? AND purpose='REAUTH'",legacyHash(scope,"proof@example.test"));
				assertThat(jdbc.queryForMap("SELECT window_started_at,hits FROM email_otp_rate_limits WHERE bucket_key=? AND purpose='REAUTH'",legacyHash(scope,proven+"\nproof@example.test"))).isEqualTo(oldBucket);
			}
			assertThat(jdbc.queryForObject("SELECT count(*) FROM reauth_email_candidates",Integer.class)).isZero();assertThat(latest.migrate().migrationsExecuted).isZero();
		}
	}
	@Test
	void upgradesV6WithoutRewritingExistingIdentityOtpOrSerializedSessionData() {
		var old=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("6").load();old.migrate();
		var checksums=java.util.Arrays.stream(old.info().applied()).map(info->info.getChecksum()).toList();
		var jdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
		var user=UUID.randomUUID();jdbc.update("INSERT INTO users(id,nickname,data_generation) VALUES (?,'preserved',7)",user);
		var identity=legacyIdentity(jdbc,user,"preserved-email@example.test");legacyProof(jdbc,user,identity,"preserved-email@example.test","REAUTH");
		var primary=UUID.randomUUID().toString();var sid=UUID.randomUUID().toString();long now=System.currentTimeMillis();
		jdbc.update("INSERT INTO spring_session VALUES (?,?,?,?,?,?,?)",primary,sid,now,now,7776000,now+7776000000L,user.toString());
		var at=java.time.Instant.now();var metadata=new com.overload.overload_server.security.SessionMetadata(user,at,at.plus(java.time.Duration.ofDays(90)),at);
		var bytes=new org.springframework.core.serializer.support.SerializingConverter().convert(metadata);
		jdbc.update("INSERT INTO spring_session_attributes VALUES (?,?,?)",primary,com.overload.overload_server.security.SessionMetadata.ATTRIBUTE,bytes);
		var users=jdbc.queryForList("SELECT * FROM users");var identities=jdbc.queryForList("SELECT * FROM auth_identities");
		var history=jdbc.queryForList("SELECT id,state,reauth_identity_id,consumed_at,encode(verifier,'hex') AS verifier FROM email_otp_challenges");
		try(var context=startApplication(WebApplicationType.NONE)){
			var latest=context.getBean(Flyway.class);
			assertThat(java.util.Arrays.stream(latest.info().applied()).limit(6).map(info->info.getChecksum()).toList()).isEqualTo(checksums);
			assertThat(jdbc.queryForList("SELECT * FROM users")).isEqualTo(users);assertThat(jdbc.queryForList("SELECT * FROM auth_identities")).isEqualTo(identities);
			assertThat(jdbc.queryForList("SELECT id,state,reauth_identity_id,consumed_at,encode(verifier,'hex') AS verifier FROM email_otp_challenges")).isEqualTo(history);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM email_otp_challenges WHERE retired_reauth_identity_id IS NOT NULL",Integer.class)).isZero();
			var stored=jdbc.queryForObject("SELECT attribute_bytes FROM spring_session_attributes WHERE session_primary_id=?",byte[].class,primary);
			assertThat(stored).isEqualTo(bytes);assertThat(new org.springframework.core.serializer.support.DeserializingConverter().convert(stored)).isEqualTo(metadata);
			assertThat(latest.migrate().migrationsExecuted).isZero();
		}
	}
	@Test
	void upgradesV7PreservingGoogleCandidateOtpAndSerializedEmailProofWithoutCascadingHistory() {
		var old=Flyway.configure().dataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()).target("7").load();old.migrate();
		var checksums=java.util.Arrays.stream(old.info().applied()).map(info->info.getChecksum()).toList();
		var jdbc=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword()));
		var user=UUID.randomUUID();var identity=UUID.randomUUID();var contact=UUID.randomUUID();var candidate=UUID.randomUUID();
		jdbc.update("INSERT INTO users(id,nickname,data_generation) VALUES (?,'preserved',7)",user);
		jdbc.update("INSERT INTO auth_identities(id,user_id,provider,issuer,subject) VALUES (?,?,'google','https://accounts.google.com','preserved-google-sub')",identity,user);
		jdbc.update("INSERT INTO user_reauthentication_emails(user_id,id,email,verified_at,verification_source) VALUES (?,?,'preserved@example.test',CURRENT_TIMESTAMP,'GOOGLE_EMAIL_OTP')",user,contact);
		jdbc.update("INSERT INTO reauth_email_candidates(id,user_id,google_identity_id,google_subject,email,binding_hash,expires_at,consumed_at) VALUES (?,?,?,'preserved-google-sub','preserved@example.test',?,CURRENT_TIMESTAMP+INTERVAL '5 minutes',CURRENT_TIMESTAMP)",candidate,user,identity,"f".repeat(64));
		legacyProof(jdbc,user,identity,"preserved@example.test","CONTACT_ENROLL");
		var primary=UUID.randomUUID().toString();var sid=UUID.randomUUID().toString();long now=System.currentTimeMillis();
		jdbc.update("INSERT INTO spring_session VALUES (?,?,?,?,?,?,?)",primary,sid,now,now,7776000,now+7776000000L,user.toString());
		var at=java.time.Instant.now();var proof=com.overload.overload_server.security.EmailOwnershipProof.verified(contact,"preserved@example.test",at,com.overload.overload_server.security.EmailOwnershipProof.Source.CONTACT_ENROLL).bind(user,sid);
		var bytes=new org.springframework.core.serializer.support.SerializingConverter().convert(proof);
		jdbc.update("INSERT INTO spring_session_attributes VALUES (?,?,?)",primary,com.overload.overload_server.security.EmailOwnershipProof.ATTRIBUTE,bytes);
		var owners=jdbc.queryForList("SELECT * FROM auth_identities");var candidates=jdbc.queryForList("SELECT id,user_id,google_identity_id,email,consumed_at FROM reauth_email_candidates");
		var history=jdbc.queryForList("SELECT id,state,reauth_user_id,reauth_identity_id,consumed_at,encode(verifier,'hex') AS verifier FROM email_otp_challenges");
		try(var context=startApplication(WebApplicationType.NONE)){
			var latest=context.getBean(Flyway.class);
			assertThat(java.util.Arrays.stream(latest.info().applied()).limit(7).map(info->info.getChecksum()).toList()).isEqualTo(checksums);
			assertThat(jdbc.queryForList("SELECT * FROM auth_identities")).isEqualTo(owners);
			assertThat(jdbc.queryForList("SELECT id,user_id,google_identity_id,email,consumed_at FROM reauth_email_candidates")).isEqualTo(candidates);
			assertThat(jdbc.queryForList("SELECT id,state,reauth_user_id,reauth_identity_id,consumed_at,encode(verifier,'hex') AS verifier FROM email_otp_challenges")).isEqualTo(history);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM google_identity_change_versions",Integer.class)).isZero();
			var stored=jdbc.queryForObject("SELECT attribute_bytes FROM spring_session_attributes WHERE session_primary_id=?",byte[].class,primary);
			assertThat(stored).isEqualTo(bytes);assertThat(new org.springframework.core.serializer.support.DeserializingConverter().convert(stored)).isEqualTo(proof);
			assertThatThrownBy(()->jdbc.update("DELETE FROM auth_identities WHERE id=?",identity)).isInstanceOf(DataIntegrityViolationException.class);
			jdbc.update("UPDATE email_otp_challenges SET retired_reauth_identity_id=reauth_identity_id,reauth_identity_id=NULL WHERE purpose='CONTACT_ENROLL' AND reauth_identity_id=?",identity);
			// Candidate FK alone must prevent deletion; retaining the OTP FK must not mask CASCADE regressions.
			assertThatThrownBy(()->jdbc.update("DELETE FROM auth_identities WHERE id=?",identity)).isInstanceOf(DataIntegrityViolationException.class);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM reauth_email_candidates",Integer.class)).isEqualTo(1);
			assertThat(latest.migrate().migrationsExecuted).isZero();
		}
	}
	private String legacyHash(String scope,String recipient) throws Exception {
		var digest=java.security.MessageDigest.getInstance("SHA-256");
		digest.update(scope.getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);
		return java.util.HexFormat.of().formatHex(digest.digest(recipient.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
	}
	private UUID legacyIdentity(JdbcTemplate jdbc,UUID owner,String email){var id=UUID.randomUUID();jdbc.update("INSERT INTO auth_identities(id,user_id,provider,issuer,subject) VALUES (?,?,'email','overload:email',?)",id,owner,email);return id;}
	private void legacyProof(JdbcTemplate jdbc,UUID owner,UUID identity,String email,String purpose){
		String key=UUID.randomUUID().toString().replace("-","").repeat(2);var id=UUID.randomUUID();
		jdbc.update("INSERT INTO email_otp_limits(email_key,purpose,failure_window_end) VALUES (?,?,CURRENT_TIMESTAMP+INTERVAL '30 minutes')",key,purpose);
		jdbc.update("""
				INSERT INTO email_otp_challenges(id,email_key,purpose,recipient,binding_hash,verifier,state,issued_at,expires_at,accepted_at,consumed_at,reauth_user_id,reauth_identity_id)
				VALUES (?,?,?,?,?,?,'CONSUMED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '5 minutes',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)
				""",id,key,purpose,email,key,new byte[32],purpose.equals("LOGIN")?null:owner,purpose.equals("LOGIN")?null:identity);
	}

	@Test
	void enforcesMinimalUserSchemaAndRejectsInvalidWrites() {
		try (var context = startApplication(WebApplicationType.NONE)) {
			var jdbc = context.getBean(JdbcTemplate.class);
			assertThat(jdbc.queryForList("""
					SELECT column_name || ':' || udt_name || ':' || is_nullable
					FROM information_schema.columns
					WHERE table_schema = 'public' AND table_name = 'users'
					ORDER BY ordinal_position
					""", String.class)).containsExactly(
					"id:uuid:NO", "data_generation:int8:NO", "nickname:text:YES",
					"created_at:timestamptz:NO", "updated_at:timestamptz:NO");
			assertThatThrownBy(() -> jdbc.update(
					"INSERT INTO users (id, data_generation) VALUES (?, -1)", UUID.randomUUID()))
					.isInstanceOf(DataIntegrityViolationException.class)
					.hasMessageContaining("users_data_generation_nonnegative");
			assertThatThrownBy(() -> jdbc.update("INSERT INTO users (id) VALUES (NULL)"))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertThatThrownBy(() -> jdbc.update(
					"INSERT INTO users (id, data_generation) VALUES (?, NULL)", UUID.randomUUID()))
					.isInstanceOf(DataIntegrityViolationException.class);
			for (var column : List.of("created_at", "updated_at")) {
				assertThatThrownBy(() -> jdbc.update(
						"INSERT INTO users (id, " + column + ") VALUES (?, NULL)", UUID.randomUUID()))
						.isInstanceOf(DataIntegrityViolationException.class);
			}
			var userId = UUID.randomUUID();
			jdbc.update("INSERT INTO users (id, nickname) VALUES (?, ?)", userId, "테스트 사용자");
			assertThatThrownBy(() -> jdbc.update("INSERT INTO users (id) VALUES (?)", userId))
					.isInstanceOf(DataIntegrityViolationException.class);
			assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, userId))
					.isEqualTo("테스트 사용자");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(1);
		}
	}

	@Test
	void preservesHealthHttpContractWithRealPostgres() throws Exception {
		try (var context = startApplication(WebApplicationType.SERVLET);
			 var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			int port = ((WebServerApplicationContext) context).getWebServer().getPort();
			var uri = URI.create("http://127.0.0.1:" + port + "/api/v1/health");
			var get = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			assertThat(get.statusCode()).isEqualTo(200);
			assertThat(get.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
			assertThat(get.body()).isEqualTo("{\"status\":\"UP\"}");
			var post = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
					.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
			assertThat(post.statusCode()).isEqualTo(405);
		}
	}

	private List<java.util.Map<String, Object>> legacyRows(JdbcTemplate jdbc, String table) {
		// Compare every pre-V9 column, not the newly added metadata columns.
		var columns = switch (table) {
			case "exercise_definitions" -> "id,user_id,data_generation,name,created_at,updated_at";
			case "user_workout_settings" -> "user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds,created_at,updated_at";
			default -> "*";
		};
		return jdbc.queryForList("SELECT " + columns + " FROM " + table);
	}

	private ConfigurableApplicationContext startApplication(WebApplicationType webApplicationType) {
		var context = new SpringApplicationBuilder(OverloadServerApplication.class)
				.web(webApplicationType)
				.run("--spring.profiles.active=integration",
						"--spring.datasource.url=" + postgres.getJdbcUrl(),
						"--spring.datasource.username=" + postgres.getUsername(),
						"--spring.datasource.password=" + postgres.getPassword(),
						"--server.address=127.0.0.1", "--server.port=0");
		com.overload.testfixture.MixedPersistenceAssertions.verify(context);
		return context;
	}

}
