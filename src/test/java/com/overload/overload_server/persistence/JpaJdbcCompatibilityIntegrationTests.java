package com.overload.overload_server.persistence;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.Account;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.overload_server.security.SessionMetadata;
import com.overload.overload_server.workoutsettings.UserWorkoutSettings;
import com.overload.overload_server.workoutsettings.UserWorkoutSettingsRepository;
import com.overload.testfixture.EmailOtpFixtures;
import com.overload.testfixture.JpaCompatibilityFixtures;
import com.overload.testfixture.MixedPersistenceAssertions;
import com.overload.testfixture.ReauthenticationFixtures;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.session.SessionRepository;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
@Testcontainers
class JpaJdbcCompatibilityIntegrationTests {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_jpa_compat_test").withUsername("overload_test")
			.withPassword(UUID.randomUUID().toString());
	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String HMAC = randomKey();
	static ConfigurableApplicationContext app;
	JdbcTemplate jdbc;
	TransactionTemplate jpa;
	TransactionTemplate primary;
	EntityManagerFactory factory;
	AccountProvisioningService accounts;
	Account account;

	@BeforeAll
	static void start() {
		EmailOtpFixtures.CLOCK.reset();
		app = startApplication();
	}

	static ConfigurableApplicationContext startApplication(String... extra) {
		var arguments = new ArrayList<>(List.of("--spring.profiles.active=local,integration",
				"--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
				"--spring.datasource.password=" + POSTGRES.getPassword(), "--server.address=127.0.0.1", "--server.port=0",
				"--GOOGLE_OIDC_ENABLED=false", "--GOOGLE_REAUTH_ENABLED=false", "--OTP_EMAIL_ENABLED=false",
				"--OTP_HMAC_KEY=" + HMAC, "--spring.session.jdbc.cleanup-cron=-", "--logging.level.root=WARN",
				"--spring.main.banner-mode=off"));
		arguments.addAll(List.of(extra));
		return new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class,
				ReauthenticationFixtures.class, JpaCompatibilityFixtures.class).web(WebApplicationType.SERVLET)
				.run(arguments.toArray(String[]::new));
	}

	@BeforeEach
	void prepare() {
		EmailOtpFixtures.CLOCK.reset();
		ReauthenticationFixtures.reset();
		JpaCompatibilityFixtures.sessionObserver = null;
		JpaCompatibilityFixtures.jpaObserver = null;
		jdbc = app.getBean(JdbcTemplate.class);
		factory = app.getBean(EntityManagerFactory.class);
		jpa = new TransactionTemplate(app.getBean("workoutTransactionManager", PlatformTransactionManager.class));
		primary = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
		accounts = app.getBean(AccountProvisioningService.class);
		account = accounts.provision(VerifiedIdentity.email(UUID.randomUUID() + "@example.test"), null);
	}

	@AfterEach
	void reset() {
		JpaCompatibilityFixtures.sessionObserver = null;
		JpaCompatibilityFixtures.jpaObserver = null;
		ReauthenticationFixtures.reset();
		jdbc.execute("DROP TRIGGER IF EXISTS compat_seed_fail ON user_workout_settings");
		jdbc.execute("DROP TRIGGER IF EXISTS compat_rotation_fail ON email_otp_challenges");
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		assertThat(TransactionSynchronizationManager.hasResource(factory)).isFalse();
	}

	@AfterAll
	static void stop() {
		if (app != null) app.close();
	}

	@Test
	void tx01ExplicitManagersShareOneDataSourceAndRepositoriesUseJpa() {
		MixedPersistenceAssertions.verify(app);
		var transaction = (TransactionTemplate) ReflectionTestUtils.getField(accounts, "transaction");
		assertThat(transaction.getTransactionManager()).isSameAs(primary.getTransactionManager());
		assertThat(transaction.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		assertThat(transaction.getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
		var observed = new ArrayList<Boolean>();
		JpaCompatibilityFixtures.jpaObserver = observed::add;
		assertThat(app.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(account.id(), 0)).isPresent();
		assertThat(observed).isNotEmpty().containsOnly(true);
		primary.executeWithoutResult(status -> assertThat(TransactionSynchronizationManager.hasResource(factory)).isFalse());
	}

	@Test
	void tx02JdbcSeedIsReadableThroughJpaWithoutChangingIdsSettingsOrSchema() {
		var before = rows();
		var history = jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank");
		var settings = app.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(account.id(), 0).orElseThrow();
		assertThat(settings.getUserId()).isEqualTo(account.id());
		assertThat(settings.getDataGeneration()).isZero();
		assertThat(settings.getCreatedAt()).isNotNull();
		assertThat(settings.getUpdatedAt()).isEqualTo(settings.getCreatedAt());
		assertThat(List.of(settings.getRestLowSeconds(), settings.getRestMediumSeconds(), settings.getRestHighSeconds())).containsExactly(180, 120, 90);
		assertThat(app.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(UUID.randomUUID(), 0)).isEmpty();
		assertThat(app.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(account.id(), 1)).isEmpty();
		assertThat(rows()).isEqualTo(before);
		app.close();
		app = startApplication();
		MixedPersistenceAssertions.verify(app);
		jdbc = app.getBean(JdbcTemplate.class);
		factory = app.getBean(EntityManagerFactory.class);
		assertThat(rows()).isEqualTo(before);
		assertThat(jdbc.queryForList("SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(history).hasSize(25);
	}

	@Test
	void tx02NonzeroLargeGenerationAndMicrosecondTimestampsRoundTripUnchanged() {
		var owner = UUID.randomUUID();
		long generation = 9007199254740993L;
		var instant = Instant.parse("2026-10-27T00:00:00.123456Z");
		jdbc.update("INSERT INTO users(id,data_generation) VALUES (?,?)", owner, generation);
		jdbc.update("INSERT INTO user_workout_settings VALUES (?,?,77,88,99,?,?)", owner, generation,
				java.sql.Timestamp.from(instant), java.sql.Timestamp.from(instant));
		var settings = app.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(owner, generation).orElseThrow();
		assertThat(settings.getUserId()).isEqualTo(owner);
		assertThat(settings.getDataGeneration()).isEqualTo(generation);
		assertThat(settings.getCreatedAt()).isEqualTo(instant);
		assertThat(settings.getUpdatedAt()).isEqualTo(instant);
		assertThat(app.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(owner, generation).orElseThrow().getRestLowSeconds()).isEqualTo(77);
	}

	@Test
	void tx01RepeatableReadReadOnlyUsesTheJpaConnectionWithoutRowLocks() {
		jpa.setReadOnly(true);
		jpa.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
		jpa.executeWithoutResult(status -> {
			assertSameConnection();
			assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
			assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
			assertThat(app.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(account.id(), 0)).isPresent();
		});
	}

	@Test
	void tx01PreservesBootJdbcExceptionTranslationSwitch() {
		try (var alternate = startApplication("--spring.dao.exceptiontranslation.enabled=false")) {
			assertThat(alternate.getBean(PlatformTransactionManager.class))
					.isSameAs(alternate.getBean("transactionManager"))
					.isExactlyInstanceOf(org.springframework.jdbc.datasource.DataSourceTransactionManager.class);
			assertThat(alternate.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(account.id(), 0)).isPresent();
		}
	}

	@Test
	void tx02ValidateRejectsMissingColumnWithoutRepairingSchema() {
		jdbc.execute("ALTER TABLE user_workout_settings RENAME COLUMN rest_medium_seconds TO compat_missing_rest");
		try {
			assertThatThrownBy(() -> {
				try (var unexpected = startApplication()) { throw new AssertionError("Missing column was accepted"); }
			}).hasRootCauseInstanceOf(org.hibernate.tool.schema.spi.SchemaManagementException.class);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name='user_workout_settings' AND column_name='rest_medium_seconds'", Integer.class)).isZero();
		} finally {
			jdbc.execute("ALTER TABLE user_workout_settings RENAME COLUMN compat_missing_rest TO rest_medium_seconds");
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void tx03JpaAndJdbcCommitOnTheSamePhysicalTransaction(boolean jdbcFirst) {
		jpa.executeWithoutResult(status -> {
			assertSameConnection();
			if (jdbcFirst) jdbc.update("UPDATE user_workout_settings SET rest_low_seconds=321 WHERE user_id=?", account.id());
			ReflectionTestUtils.setField(entityManager().find(UserWorkoutSettings.class, account.id()), "restMediumSeconds", 222);
			entityManager().flush();
			if (!jdbcFirst) jdbc.update("UPDATE user_workout_settings SET rest_low_seconds=321 WHERE user_id=?", account.id());
		});
		assertThat(medium()).isEqualTo(222);
		assertThat(low()).isEqualTo(321);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void tx03ApplicationFailureRollsBackBothWriteOrders(boolean jdbcFirst) {
		var before = rows();
		assertThatThrownBy(() -> jpa.executeWithoutResult(status -> {
			assertSameConnection();
			if (jdbcFirst) jdbc.update("UPDATE user_workout_settings SET rest_low_seconds=333 WHERE user_id=?", account.id());
			ReflectionTestUtils.setField(entityManager().find(UserWorkoutSettings.class, account.id()), "restMediumSeconds", 223);
			entityManager().flush();
			if (!jdbcFirst) jdbc.update("UPDATE user_workout_settings SET rest_low_seconds=333 WHERE user_id=?", account.id());
			throw new IllegalStateException("synthetic rollback");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(rows()).isEqualTo(before);
	}

	@Test
	void tx03CommitTimeJpaConstraintFailureAlsoRollsBackJdbc() {
		var before = rows();
		assertThatThrownBy(() -> jpa.executeWithoutResult(status -> {
			ReflectionTestUtils.setField(entityManager().find(UserWorkoutSettings.class, account.id()), "restMediumSeconds", -1);
			jdbc.update("UPDATE user_workout_settings SET rest_low_seconds=444 WHERE user_id=?", account.id());
			// No explicit flush: the real constraint fails during transaction completion.
		})).isInstanceOf(RuntimeException.class);
		assertThat(rows()).isEqualTo(before);
	}

	@Test
	void tx04ExplicitFlushAndRefreshReconcileJdbcAndNativeUpdates() {
		jpa.executeWithoutResult(status -> {
			var settings = entityManager().find(UserWorkoutSettings.class, account.id());
			var old = settings.getRestMediumSeconds();
			ReflectionTestUtils.setField(settings, "restMediumSeconds", 224);
			assertThat(medium()).isEqualTo(old);
			entityManager().flush();
			assertThat(medium()).isEqualTo(224);
			jdbc.update("UPDATE user_workout_settings SET rest_medium_seconds=225 WHERE user_id=?", account.id());
			assertThat(settings.getRestMediumSeconds()).isEqualTo(224);
			entityManager().refresh(settings);
			assertThat(settings.getRestMediumSeconds()).isEqualTo(225);
			entityManager().createNativeQuery("UPDATE user_workout_settings SET rest_medium_seconds=226 WHERE user_id=:id")
					.setParameter("id", account.id()).executeUpdate();
			assertThat(settings.getRestMediumSeconds()).isEqualTo(225);
			entityManager().clear();
			assertThat(entityManager().find(UserWorkoutSettings.class, account.id()).getRestMediumSeconds()).isEqualTo(226);
		});
		assertThat(medium()).isEqualTo(226);
	}

	@Test
	void tx04JdbcConstraintFailureRollsBackAndRecoveryUsesAFreshTransaction() {
		var before = rows();
		assertThatThrownBy(() -> jpa.executeWithoutResult(status -> {
			ReflectionTestUtils.setField(entityManager().find(UserWorkoutSettings.class, account.id()), "restMediumSeconds", 227);
			entityManager().flush();
			jdbc.update("INSERT INTO user_workout_settings(user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds) VALUES (?,0,1,1,1)", account.id());
		})).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(rows()).isEqualTo(before);
		jpa.executeWithoutResult(status -> assertThat(entityManager().find(UserWorkoutSettings.class, account.id()).getRestMediumSeconds()).isEqualTo(medium()));
	}

	@Test
	void tx05ProvisioningRequiresNewCommitsIndependentlyAndResumesOuterJdbc() {
		var email = UUID.randomUUID() + "@example.test";
		var created = primary.execute(status -> {
			var pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
			jdbc.update("UPDATE user_workout_settings SET rest_medium_seconds=228 WHERE user_id=?", account.id());
			var result = accounts.provision(VerifiedIdentity.email(email), null);
			assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(pid);
			assertThat(independentJdbc().queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, result.id())).isEqualTo(1);
			status.setRollbackOnly();
			return result;
		});
		assertThat(medium()).isNotEqualTo(228);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions WHERE user_id=?",Integer.class,created.id())).isZero();
		assertThat(app.getBean(UserWorkoutSettingsRepository.class).findByUserIdAndDataGeneration(created.id(), 0)).isPresent();
		assertThat(accounts.provision(VerifiedIdentity.email(email), "ignored")).isEqualTo(created);
	}

	@Test
	void tx05ProvisioningFailureRollsBackItsSeedWithoutPoisoningOuterJdbc() {
		var email = UUID.randomUUID() + "@example.test";
		var usersBefore = jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
		jdbc.execute("CREATE OR REPLACE FUNCTION compat_seed_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic seed fault' USING ERRCODE='23514'; END $$");
		jdbc.execute("CREATE TRIGGER compat_seed_fail BEFORE INSERT ON user_workout_settings FOR EACH ROW EXECUTE FUNCTION compat_seed_fail()");
		primary.executeWithoutResult(status -> {
			assertThatThrownBy(() -> accounts.provision(VerifiedIdentity.email(email), null)).isInstanceOf(DataIntegrityViolationException.class);
			jdbc.update("UPDATE user_workout_settings SET rest_low_seconds=789 WHERE user_id=?", account.id());
		});
		assertThat(low()).isEqualTo(789);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(usersBefore);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identities WHERE subject=?", Integer.class, email)).isZero();
	}

	@Test
	@SuppressWarnings("unchecked")
	void tx06SessionRequiresNewUsesAnotherConnectionAndSurvivesOuterRollback() {
		var sessions = (SessionRepository<org.springframework.session.Session>) app.getBean(SessionRepository.class);
		var observations = new ArrayList<JpaCompatibilityFixtures.SessionTransaction>();
		JpaCompatibilityFixtures.sessionObserver = observations::add;
		var outer = new TransactionTemplate(primary.getTransactionManager());
		outer.setReadOnly(true);
		var id = outer.execute(status -> {
			var pid = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
			var session = sessions.createSession();
			session.setAttribute("compatibility", "synthetic");
			sessions.save(session);
			assertThat(observations).isNotEmpty().allSatisfy(sample -> {
				assertThat(sample.backendPid()).isNotEqualTo(pid);
				assertThat(sample.jpaBound()).isFalse();
				assertThat(sample.readOnly()).isFalse();
				assertThat(sample.original().getTransactionManager()).isSameAs(primary.getTransactionManager());
				assertThat(sample.original().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			});
			assertThat(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)).isEqualTo(pid);
			status.setRollbackOnly();
			return session.getId();
		});
		assertThat(sessions.findById(id)).isNotNull();
		primary.executeWithoutResult(status -> { sessions.deleteById(id); status.setRollbackOnly(); });
		assertThat(sessions.findById(id)).isNull();
	}

	@Test
	@SuppressWarnings("unchecked")
	void tx08RealOtpRotationRollsBackTogetherAndRequestIdsCoverJdbcSessions() throws Exception {
		var observations = new CopyOnWriteArrayList<JpaCompatibilityFixtures.SessionTransaction>();
		JpaCompatibilityFixtures.sessionObserver = observations::add;
		try (var browser = new Browser()) {
			var email = UUID.randomUUID() + "@example.test";
			var csrf = browser.csrf();
			var issued = browser.post("/api/v1/auth/email-otp/request", Map.of("email", email), csrf);
			assertThat(issued.statusCode()).isEqualTo(202);
			var message = message(issued);
			assertThat(browser.post("/api/v1/auth/email-otp/verify", Map.of("email", email, "challengeId", message.issuanceId(), "code", message.code()), csrf).statusCode()).isEqualTo(204);
			var sessions = (SessionRepository<org.springframework.session.Session>) app.getBean(SessionRepository.class);
			var oldId = browser.cookie();
			var metadata = (SessionMetadata) sessions.findById(oldId).getAttribute(SessionMetadata.ATTRIBUTE);
			var owner = metadata.userId();
			assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions WHERE user_id=?",Integer.class,owner)).isZero();
			var proof = message(browser.post("/api/v1/auth/reauth/email/request", Map.of(), browser.csrf()));
			var body = Map.of("challengeId", proof.issuanceId(), "code", proof.code());
			jdbc.execute("CREATE OR REPLACE FUNCTION compat_rotation_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic rotation fault' USING ERRCODE='23514'; END $$");
			jdbc.execute("CREATE TRIGGER compat_rotation_fail BEFORE UPDATE ON email_otp_challenges FOR EACH ROW WHEN (NEW.state='CONSUMED') EXECUTE FUNCTION compat_rotation_fail()");
			assertThat(browser.post("/api/v1/auth/reauth/email/verify", body, browser.csrf()).statusCode()).isEqualTo(503);
			assertThat(jdbc.queryForObject("SELECT state FROM email_otp_challenges WHERE id=?", String.class, proof.issuanceId())).isEqualTo("ACTIVE");
			assertThat((SessionMetadata) sessions.findById(oldId).getAttribute(SessionMetadata.ATTRIBUTE)).isEqualTo(metadata);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE principal_name=?", Integer.class, owner.toString())).isEqualTo(1);
			jdbc.execute("DROP TRIGGER compat_rotation_fail ON email_otp_challenges");
			var rotation = browser.post("/api/v1/auth/reauth/email/verify", body, browser.csrf());
			assertThat(rotation.statusCode()).isEqualTo(204);
			assertThat(rotation.body()).isEmpty();
			assertThat(browser.cookie()).isNotEqualTo(oldId);
			assertThat(sessions.findById(oldId)).isNull();
			var next = (SessionMetadata) sessions.findById(browser.cookie()).getAttribute(SessionMetadata.ATTRIBUTE);
			assertThat(next.userId()).isEqualTo(owner);
			assertThat(next.absoluteExpiresAt()).isEqualTo(metadata.absoluteExpiresAt());
			assertThat(next.loginStartedAt()).isEqualTo(metadata.loginStartedAt());
			assertThat(jdbc.queryForObject("SELECT state FROM email_otp_challenges WHERE id=?", String.class, proof.issuanceId())).isEqualTo("CONSUMED");
			var response = browser.get("/api/v1/auth/session");
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
			var requestId = response.headers().firstValue("X-Request-ID").orElseThrow();
			assertThat(observations).anyMatch(value -> requestId.equals(value.requestId()));
			assertThat(observations).noneMatch(JpaCompatibilityFixtures.SessionTransaction::jpaBound);
			assertThat(browser.post("/api/v1/auth/logout", Map.of(), browser.csrf()).statusCode()).isEqualTo(204);
			assertThat(browser.get("/api/v1/auth/session").statusCode()).isEqualTo(401);
		}
		assertThat(MDC.get("requestId")).isNull();
	}

	private EntityManager entityManager() { return Objects.requireNonNull(EntityManagerFactoryUtils.getTransactionalEntityManager(factory)); }
	private void assertSameConnection() {
		var hibernate = entityManager().unwrap(Session.class).doReturningWork(connection -> {
			try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid(), txid_current()")) {
				result.next(); return List.of(result.getLong(1), result.getLong(2));
			}
		});
		var spring = jdbc.queryForObject("SELECT pg_backend_pid(), txid_current()", (row, index) -> List.of(row.getLong(1), row.getLong(2)));
		assertThat(spring).isEqualTo(hibernate);
	}
	private Object rows() { return jdbc.queryForList("SELECT * FROM user_workout_settings WHERE user_id=?", account.id()); }
	private int medium() { return jdbc.queryForObject("SELECT rest_medium_seconds FROM user_workout_settings WHERE user_id=?", Integer.class, account.id()); }
	private int low() { return jdbc.queryForObject("SELECT rest_low_seconds FROM user_workout_settings WHERE user_id=?", Integer.class, account.id()); }
	private JdbcTemplate independentJdbc() { return new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())); }
	private static String randomKey() { var bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getEncoder().encodeToString(bytes); }
	private static com.overload.overload_server.auth.email.OtpEmailSender.Message message(HttpResponse<String> response) {
		assertThat(response.statusCode()).isEqualTo(202);
		return app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(JSON.readTree(response.body()).get("challengeId").asString()));
	}

	static final class Browser implements AutoCloseable {
		final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
		String cookie() { return cookies.getCookieStore().getCookies().stream().filter(value -> value.getName().equals("SESSION")).findFirst().orElseThrow().getValue(); }
		String csrf() throws Exception { return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString(); }
		HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
		HttpResponse<String> post(String path, Object body, String csrf) throws Exception { return send("POST", path, body, csrf); }
		HttpResponse<String> send(String method, String path, Object body, String csrf) throws Exception {
			var base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
			var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20)).header("X-Request-ID", "untrusted-client-value");
			if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
			if (body != null) request.header("Content-Type", "application/json");
			var response = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
			UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow());
			return response;
		}
		@Override public void close() { client.close(); }
	}
}
