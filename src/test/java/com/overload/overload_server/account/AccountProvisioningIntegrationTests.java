package com.overload.overload_server.account;

import com.overload.overload_server.OverloadServerApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
@Testcontainers
class AccountProvisioningIntegrationTests {

	@Container
	private final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_account_test")
			.withUsername("overload_test")
			.withPassword(UUID.randomUUID().toString());

	private ConfigurableApplicationContext context;
	private JdbcTemplate jdbc;
	private AccountProvisioningService service;

	@BeforeEach
	void startApplication() {
		context = new SpringApplicationBuilder(OverloadServerApplication.class).web(WebApplicationType.NONE)
				.run("--spring.profiles.active=integration", "--spring.datasource.url=" + postgres.getJdbcUrl(),
						"--spring.datasource.username=" + postgres.getUsername(),
						"--spring.datasource.password=" + postgres.getPassword(),
						"--spring.datasource.hikari.connection-init-sql=SET statement_timeout = '15s'");
		jdbc = context.getBean(JdbcTemplate.class);
		com.overload.testfixture.MixedPersistenceAssertions.verify(context);
		service = context.getBean(AccountProvisioningService.class);
	}

	@AfterEach
	void stopApplication() {
		if (context != null) {
			context.close();
		}
	}

	@Test
	void createsPrivateSeedsForIndependentIdentitiesWithoutMockWorkoutData() {
		assertCounts(0, 0, 0, 0);
		var first = service.provision(VerifiedIdentity.google(VerifiedIdentity.GOOGLE_ISSUER, "first"), "첫 계정");
		var second = service.provision(VerifiedIdentity.email("second@example.com"), null);
		assertThat(first.id()).isNotEqualTo(second.id());
		assertThat(first.dataGeneration()).isZero();
		assertThat(first.nickname()).isEqualTo("첫 계정");
		assertThat(second.nickname()).isNull();
		assertSeed(first);
		assertSeed(second);
		assertThat(exerciseIds(first)).isEmpty();assertThat(exerciseIds(second)).isEmpty();
		assertCounts(2, 2, 0, 2);
		assertThat(jdbc.queryForList("""
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = 'public' ORDER BY table_name
				""", String.class)).containsExactly("auth_identities", "cardio_activity_catalog", "cardio_results", "daily_training_assessments", "deload_analysis_versions", "deload_weekly_assessments", "email_otp_challenges", "email_otp_limits",
				"email_otp_login_claims", "email_otp_rate_limits", "exercise_alias", "exercise_definitions", "exercise_muscle",
				"flyway_schema_history", "google_identity_change_versions", "manufacturer", "muscle", "performed_sets", "progress_change_events", "reauth_email_candidates", "record_change_events", "routine_cardio_placements", "routine_plan_history", "routine_weight_placements", "routines", "session_exercises", "session_weight_approval_requests", "spring_session", "spring_session_attributes", "standard_exercise", "user_reauthentication_emails", "user_workout_settings", "users", "weekly_schedule_versions", "workout_calendar_control", "workout_sessions");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_schedule_versions",Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_plan_history",Integer.class)).isZero();
	}

	@Test
	void reentryPreservesExistingNamesSettingsAndPersonalExerciseIds() {
		var identity = VerifiedIdentity.email("First.Last+Tag@EXAMPLE.COM");
		var first = service.provision(identity, "원래 닉네임");
		com.overload.testfixture.CatalogFixtures.personalExercises(jdbc, first.id());
		var ids = exerciseIds(first);
		jdbc.update("UPDATE exercise_definitions SET name = '개인 편집' WHERE id = ? AND user_id = ?",
				ids.getFirst(), first.id());
		jdbc.update("UPDATE user_workout_settings SET rest_low_seconds = 77 WHERE user_id = ?", first.id());
		var again = service.provision(VerifiedIdentity.email("First.Last+Tag@example.com"), "새 닉네임");
		assertThat(again).isEqualTo(first);
		assertThat(exerciseIds(again)).containsExactlyElementsOf(ids);
		assertThat(jdbc.queryForObject("SELECT rest_low_seconds FROM user_workout_settings WHERE user_id = ?",
				Integer.class, first.id())).isEqualTo(77);
		assertThat(jdbc.queryForObject("SELECT name FROM exercise_definitions WHERE id = ?", String.class,
				ids.getFirst())).isEqualTo("개인 편집");
		assertCounts(1, 1, 20, 1);
	}

	@Test
	void reentryReturnsTheCurrentGenerationWithoutReseedingAnExistingAccount() {
		var userId = UUID.randomUUID();
		var exerciseId = UUID.randomUUID();
		jdbc.update("INSERT INTO users (id, data_generation, nickname) VALUES (?, 7, '기존 계정')", userId);
		jdbc.update("""
				INSERT INTO auth_identities (id, user_id, provider, issuer, subject)
				VALUES (?, ?, 'email', 'overload:email', 'existing@example.com')
				""", UUID.randomUUID(), userId);
		jdbc.update("INSERT INTO exercise_definitions (id, user_id, data_generation, name) VALUES (?, ?, 7, '개인 종목')",
				exerciseId, userId);
		jdbc.update("""
				INSERT INTO user_workout_settings
				(user_id, data_generation, rest_low_seconds, rest_medium_seconds, rest_high_seconds)
				VALUES (?, 7, 100, 80, 60)
				""", userId);
		var account = service.provision(VerifiedIdentity.email("existing@example.com"), "덮어쓰면 안 됨");
		assertThat(account).isEqualTo(new Account(userId, 7, "기존 계정"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_analysis_versions WHERE user_id=?",Integer.class,userId)).isZero();
		assertThat(exerciseIds(account)).containsExactly(exerciseId);
		assertThat(jdbc.queryForObject("SELECT rest_low_seconds FROM user_workout_settings WHERE user_id = ?",
				Integer.class, userId)).isEqualTo(100);
		assertCounts(1, 1, 1, 1);
	}

	@Test
	void neverMergesEmailAndGoogleOrEmailLocalPartAliases() {
		var identities = List.of(VerifiedIdentity.email("First.Last+Tag@example.com"),
				VerifiedIdentity.email("first.last+Tag@example.com"),
				VerifiedIdentity.email("FirstLast+Tag@example.com"),
				VerifiedIdentity.email("First.Last@example.com"),
				VerifiedIdentity.google(VerifiedIdentity.GOOGLE_ISSUER, "First.Last+Tag@example.com"));
		var accounts = identities.stream().map(identity -> service.provision(identity, null)).toList();
		assertThat(accounts.stream().map(Account::id)).doesNotHaveDuplicates();
		assertCounts(5, 5, 0, 5);
	}

	@Test
	void uniqueCredentialResolvesForcedConcurrentFirstProvisioningWithoutOrphans() throws Exception {
		jdbc.execute("""
				CREATE FUNCTION task007_gate_identity() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN PERFORM pg_advisory_xact_lock(70071); RETURN NEW; END $$
				""");
		jdbc.execute("""
				CREATE TRIGGER task007_gate BEFORE INSERT ON auth_identities
				FOR EACH ROW EXECUTE FUNCTION task007_gate_identity()
				""");
		var identity = VerifiedIdentity.google("accounts.google.com", "concurrent-subject");
		try (var gate = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
			 var statement = gate.createStatement();
			 var executor = Executors.newFixedThreadPool(4)) {
			statement.execute("SELECT pg_advisory_lock(70071)");
			var futures = new ArrayList<Future<Account>>();
			try {
				for (int index = 0; index < 4; index++) {
					futures.add(executor.submit(() -> service.provision(identity, "동시 가입")));
				}
				int blocked = 0;
				for (int attempt = 0; attempt < 200; attempt++) {
					blocked = jdbc.queryForObject("""
							SELECT count(*) FROM pg_stat_activity
							WHERE datname = current_database() AND wait_event = 'advisory'
							""", Integer.class);
					if (blocked == 4) {
						break;
					}
					Thread.sleep(20);
				}
				assertThat(blocked).as("all four transactions reached the identity INSERT before any committed")
						.isEqualTo(4);
			} finally {
				statement.execute("SELECT pg_advisory_unlock(70071)");
			}
			var winner = futures.getFirst().get(20, TimeUnit.SECONDS);
			for (var future : futures) {
				assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(winner);
			}
			assertSeed(winner);
			assertCounts(1, 1, 0, 1);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"users", "auth_identities", "exercise_definitions", "user_workout_settings", "deload_analysis_versions"})
	void sqlFailureAtEachStageRollsBackTheEntireAccountAndAllowsRetry(String table) {
		jdbc.execute("""
				CREATE FUNCTION task007_fail_insert() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN RAISE EXCEPTION 'task007 forced SQL failure' USING ERRCODE = '23514'; END $$
				""");
		jdbc.execute("CREATE TRIGGER task007_fail BEFORE INSERT ON " + table
				+ " FOR EACH ROW EXECUTE FUNCTION task007_fail_insert()");
		var identity = VerifiedIdentity.email("rollback@example.com");
		if (table.equals("exercise_definitions")) {
			// This former seed stage must no longer be touched, even when inserts would fail.
			assertSeed(service.provision(identity, null));assertCounts(1,1,0,1);
			jdbc.execute("DROP TRIGGER task007_fail ON " + table);return;
		}
		assertThatThrownBy(() -> service.provision(identity, null))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("task007 forced SQL failure");
		assertCounts(0, 0, 0, 0);
		jdbc.execute("DROP TRIGGER task007_fail ON " + table);
		var account = service.provision(identity, null);
		assertSeed(account);
		assertCounts(1, 1, 0, 1);
	}

	@Test
	void permitsDuplicateDisplayNamesButRejectsForeignOwnerAndGenerationReferences() {
		var first = service.provision(VerifiedIdentity.email("owner-a@example.com"), null);
		var second = service.provision(VerifiedIdentity.email("owner-b@example.com"), null);
		com.overload.testfixture.CatalogFixtures.personalExercises(jdbc, first.id());
		var firstExercise = exerciseIds(first).getFirst();
		jdbc.update("INSERT INTO exercise_definitions (id, user_id, data_generation, name) VALUES (?, ?, 0, ?)",
				UUID.randomUUID(), first.id(), "벤치프레스");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions WHERE user_id = ? AND name = ?",
				Integer.class, first.id(), "벤치프레스")).isEqualTo(2);
		// Test-only consumer of the production composite key; no future workout tables are introduced.
		jdbc.execute("""
				CREATE TABLE task007_owner_reference_probe (
				    user_id UUID, data_generation BIGINT, exercise_id UUID,
				    FOREIGN KEY (user_id, data_generation, exercise_id)
				        REFERENCES exercise_definitions (user_id, data_generation, id)
				)
				""");
		jdbc.update("INSERT INTO task007_owner_reference_probe VALUES (?, 0, ?)", first.id(), firstExercise);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO task007_owner_reference_probe VALUES (?, 0, ?)",
				second.id(), firstExercise)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO task007_owner_reference_probe VALUES (?, 1, ?)",
				first.id(), firstExercise)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO exercise_definitions (id, user_id, data_generation, name) VALUES (?, ?, 1, 'stale')",
				UUID.randomUUID(), first.id())).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("UPDATE users SET data_generation = 1 WHERE id = ?", first.id()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void databaseRejectsDuplicateCredentialsInvalidProvidersAndInvalidSettings() {
		var first = service.provision(VerifiedIdentity.email("first@example.com"), null);
		var second = service.provision(VerifiedIdentity.email("second@example.com"), null);
		for (var values : List.of(
				new String[]{"email", "overload:email", "first@example.com"},
				new String[]{"unsupported", "issuer", "subject"},
				new String[]{"email", "", "valid@example.com"},
				new String[]{"email", "other", "valid@example.com"},
				new String[]{"email", "overload:email", "\t "},
				new String[]{"email", null, "valid@example.com"},
				new String[]{"email", "overload:email", null})) {
			assertThatThrownBy(() -> jdbc.update("""
					INSERT INTO auth_identities (id, user_id, provider, issuer, subject) VALUES (?, ?, ?, ?, ?)
					""", UUID.randomUUID(), second.id(), values[0], values[1], values[2]))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO auth_identities (id, user_id, provider, issuer, subject)
				VALUES (?, ?, 'email', 'overload:email', 'unknown@example.com')
				""", UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
		for (var column : List.of("rest_low_seconds", "rest_medium_seconds", "rest_high_seconds")) {
			for (var invalid : new String[]{"0", "-1", "NULL"}) {
				assertThatThrownBy(() -> jdbc.update("UPDATE user_workout_settings SET " + column + " = "
						+ invalid + " WHERE user_id = ?", first.id())).isInstanceOf(DataIntegrityViolationException.class);
			}
		}
		assertThat(jdbc.queryForList("""
				SELECT data_type FROM information_schema.columns WHERE table_name = 'user_workout_settings'
				AND column_name IN ('rest_low_seconds', 'rest_medium_seconds', 'rest_high_seconds')
				""", String.class)).containsExactly("integer", "integer", "integer");
		assertCounts(2, 2, 0, 2);
	}

	private List<UUID> exerciseIds(Account account) {
		return jdbc.queryForList("""
				SELECT id FROM exercise_definitions WHERE user_id = ? AND data_generation = ? ORDER BY id
				""", UUID.class, account.id(), account.dataGeneration());
	}

	private void assertSeed(Account account) {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_analysis_versions WHERE user_id=? AND data_generation=? AND version=0 AND epoch=0 AND enabled AND reason='INITIAL' AND time_zone IS NULL", Integer.class, account.id(), account.dataGeneration())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT a.epoch_started_at=u.created_at FROM deload_analysis_versions a JOIN users u ON u.id=a.user_id WHERE a.user_id=? AND a.version=0",Boolean.class,account.id())).isTrue();
		assertThat(jdbc.queryForList("""
				SELECT name FROM exercise_definitions WHERE user_id = ? AND data_generation = ?
				""", String.class, account.id(), account.dataGeneration()))
				.isEmpty();
		var settings = jdbc.queryForObject("""
				SELECT rest_low_seconds, rest_medium_seconds, rest_high_seconds FROM user_workout_settings
				WHERE user_id = ? AND data_generation = ?
				""", (row, index) -> new WorkoutSettings(row.getInt(1), row.getInt(2), row.getInt(3)),
				account.id(), account.dataGeneration());
		assertThat(settings).isEqualTo(new WorkoutSettings(180, 120, 90));
		assertThat(settings.restSecondsFor(10)).isEqualTo(180);
		assertThat(settings.restSecondsFor(11)).isEqualTo(120);
		assertThat(settings.restSecondsFor(12)).isEqualTo(120);
		assertThat(settings.restSecondsFor(13)).isEqualTo(90);
	}

	private void assertCounts(int users, int identities, int exercises, int settings) {
		if(users==0)assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_analysis_versions",Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(users);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identities", Integer.class)).isEqualTo(identities);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions", Integer.class)).isEqualTo(exercises);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_workout_settings", Integer.class)).isEqualTo(settings);
	}

}
