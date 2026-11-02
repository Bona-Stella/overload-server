package com.overload.overload_server.database;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.AccountSeed;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.overload_server.exercise.ExerciseDefinitionRepository;
import com.overload.overload_server.workoutsettings.UserWorkoutSettingsRepository;
import com.overload.testfixture.MixedPersistenceAssertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Applied Flyway history still needs an upgrade/data-preservation regression after runtime removal. */
@Tag("postgres")
@Testcontainers
class V9MigrationCompatibilityTests {

	@Container
	final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_write_v8_upgrade").withUsername("overload_test").withPassword(UUID.randomUUID().toString());

	@Test void v8UpgradePreservesLegacyDataChecksumsAndJdbcProvisioning() {
		var old = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("8").load();
		old.migrate();
		var checksums = Arrays.stream(old.info().applied()).map(value -> value.getChecksum()).toList();
		var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
		var owner = UUID.randomUUID(); long generation = 9_007_199_254_740_993L;
		var first = UUID.randomUUID(); var second = UUID.randomUUID();
		var identity = VerifiedIdentity.email("v8-preserved@example.test");
		jdbc.update("INSERT INTO users(id,data_generation,nickname,created_at,updated_at) VALUES (?,?,'legacy','2026-10-01T00:00:00.123456Z','2026-10-02T00:00:00.654321Z')", owner, generation);
		jdbc.update("INSERT INTO auth_identities(id,user_id,provider,issuer,subject) VALUES (?,?,'email','overload:email','v8-preserved@example.test')", UUID.randomUUID(), owner);
		for (var id : java.util.List.of(first, second)) {
			jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,?,'  同名 벤치 Ω  ','2026-10-01T00:00:00.123456Z','2026-10-02T00:00:00.654321Z')", id, owner, generation);
		}
		jdbc.update("INSERT INTO user_workout_settings(user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds,created_at,updated_at) VALUES (?,?,17,29,43,'2026-10-01T00:00:00.123456Z','2026-10-02T00:00:00.654321Z')", owner, generation);
		var users = jdbc.queryForList("SELECT * FROM users ORDER BY id");
		var identities = jdbc.queryForList("SELECT * FROM auth_identities ORDER BY id");
		var exercises = jdbc.queryForList("SELECT id,user_id,data_generation,name,created_at,updated_at FROM exercise_definitions ORDER BY id");
		var settings = jdbc.queryForList("SELECT user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds,created_at,updated_at FROM user_workout_settings");
		var v9 = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).target("9").load();
		v9.migrate();
		assertThat(v9.info().current().getVersion().toString()).isEqualTo("9");
		assertThat(jdbc.queryForList("SELECT version FROM exercise_definitions WHERE user_id=?", Long.class, owner)).containsOnly(0L);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions WHERE user_id=? AND deleted_at IS NOT NULL", Integer.class, owner)).isZero();
		try (var app = new SpringApplicationBuilder(OverloadServerApplication.class).web(WebApplicationType.NONE)
				.run("--spring.profiles.active=integration", "--spring.datasource.url=" + postgres.getJdbcUrl(),
						"--spring.datasource.username=" + postgres.getUsername(), "--spring.datasource.password=" + postgres.getPassword(),
						"--GOOGLE_OIDC_ENABLED=false", "--OTP_EMAIL_ENABLED=false", "--logging.level.root=WARN", "--spring.main.banner-mode=off")) {
			MixedPersistenceAssertions.verify(app);
			var flyway = app.getBean(Flyway.class);
			assertThat(flyway.info().current().getVersion().toString()).isEqualTo("22");
			assertThat(Arrays.stream(flyway.info().applied()).limit(8).map(value -> value.getChecksum()).toList()).isEqualTo(checksums);
			assertThat(jdbc.queryForList("SELECT * FROM users ORDER BY id")).isEqualTo(users);
			assertThat(jdbc.queryForList("SELECT * FROM auth_identities ORDER BY id")).isEqualTo(identities);
			assertThat(jdbc.queryForList("SELECT id,user_id,data_generation,name,created_at,updated_at FROM exercise_definitions ORDER BY id")).isEqualTo(exercises);
			assertThat(jdbc.queryForList("SELECT user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds,created_at,updated_at FROM user_workout_settings")).isEqualTo(settings);
			var mapped = app.getBean(ExerciseDefinitionRepository.class).findAllByUserIdAndDataGenerationOrderById(owner, generation);
			assertThat(mapped).hasSize(2).allSatisfy(value -> {
				assertThat(value.getName()).isEqualTo("  同名 벤치 Ω  ");
				assertThat(value.getVersion()).isZero(); assertThat(value.getDeletedAt()).isNull();
			});
			assertThat(app.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(owner, generation).orElseThrow().getRestLowSeconds()).isEqualTo(17);
			var provisioning = app.getBean(AccountProvisioningService.class);
			assertThat(provisioning.provision(identity, null).id()).isEqualTo(owner);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions WHERE user_id=?", Integer.class, owner)).isEqualTo(2);
			var fresh = provisioning.provision(VerifiedIdentity.email("v9-fresh@example.test"), null);
			assertThat(jdbc.queryForList("SELECT name FROM exercise_definitions WHERE user_id=?", String.class, fresh.id()))
					.isEmpty();
			assertThat(jdbc.queryForList("SELECT version FROM exercise_definitions WHERE user_id=?", Long.class, fresh.id())).isEmpty();
			var freshSettings = app.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(fresh.id(), 0).orElseThrow();
			assertThat(java.util.List.of(freshSettings.getRestLowSeconds(), freshSettings.getRestMediumSeconds(), freshSettings.getRestHighSeconds())).containsExactly(180, 120, 90);
			assertThat(flyway.migrate().migrationsExecuted).isZero();
		}
	}
}
