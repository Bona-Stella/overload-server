package com.overload.overload_server.workout;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.Account;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.testfixture.EmailOtpFixtures;
import com.overload.testfixture.MixedPersistenceAssertions;
import com.overload.testfixture.WorkoutReadFixtures;
import com.overload.testfixture.WeeklyPresetFixtures;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres")
@Testcontainers
class WorkoutReadIntegrationTests {

	static final String EXERCISES = "/api/v1/exercises";
	static final String SETTINGS = "/api/v1/workout-settings";
	static final JsonMapper JSON = JsonMapper.builder().build();
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_workout_read_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
	static ConfigurableApplicationContext app;
	static JdbcTemplate jdbc;
	Account owner;
	String email;
	Browser browser;

	@BeforeAll static void start() {
		var key = new byte[32]; new SecureRandom().nextBytes(key);
		app = new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class, WorkoutReadFixtures.class)
				.web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
						"--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
						"--spring.datasource.password=" + POSTGRES.getPassword(), "--server.address=127.0.0.1", "--server.port=0",
						"--OTP_HMAC_KEY=" + Base64.getEncoder().encodeToString(key), "--OTP_EMAIL_ENABLED=false",
						"--GOOGLE_OIDC_ENABLED=false", "--GOOGLE_REAUTH_ENABLED=false", "--spring.session.jdbc.cleanup-cron=-",
						"--logging.level.root=WARN", "--spring.main.banner-mode=off");
		jdbc = app.getBean(JdbcTemplate.class);
		MixedPersistenceAssertions.verify(app);
	}

	@BeforeEach void prepare() throws Exception {
		WorkoutReadFixtures.observer = null;
		EmailOtpFixtures.CLOCK.reset(); app.getBean(EmailOtpFixtures.FakeSender.class).reset();
		jdbc.update("DELETE FROM email_otp_rate_limits");
		email = UUID.randomUUID() + "@example.test";
		owner = app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email(email), null);
		browser = new Browser(); browser.login(email);
	}

	@AfterEach void stopBrowser() {
		WorkoutReadFixtures.observer = null;
		if (browser != null) browser.close();
	}
	@AfterAll static void stop() { if (app != null) app.close(); }

	@ParameterizedTest @ValueSource(strings = {SETTINGS})
	void anonymousExpiredAndMissingOwnersAreUnauthorized(String path) throws Exception {
		try (var anonymous = new Browser()) { error(anonymous.get(path), 401, "AUTHENTICATION_REQUIRED"); }
		var before = snapshot();
		// A valid authenticated session can outlive an externally removed user. Never reprovision from GET.
		try (var missing = new Browser()) {
			missing.login(UUID.randomUUID() + "@example.test");
			var id = UUID.fromString(JSON.readTree(missing.get("/api/v1/auth/session").body()).get("userId").asString());
			jdbc.update("DELETE FROM exercise_definitions WHERE user_id=?", id);
			jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?", id);
			jdbc.update("DELETE FROM auth_identities WHERE user_id=?", id);
			WeeklyPresetFixtures.clear(jdbc, id);
			jdbc.update("DELETE FROM deload_analysis_versions WHERE user_id=?", id); jdbc.update("DELETE FROM users WHERE id=?", id);
			error(missing.get(path), 401, "AUTHENTICATION_REQUIRED");
			assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?", Integer.class, id)).isZero();
		}
		assertThat(snapshot()).isEqualTo(before);
		EmailOtpFixtures.CLOCK.advance(Duration.ofDays(91));
		error(browser.get(path), 401, "AUTHENTICATION_REQUIRED");
	}

	@Test void editedSettingsArePreserved(TestReporter reporter) throws Exception {
		jdbc.update("UPDATE user_workout_settings SET rest_low_seconds=17,rest_medium_seconds=29,rest_high_seconds=43 WHERE user_id=?", owner.id());
		var original = snapshot();
		var settingsResponse = browser.get(SETTINGS);
		reporter.publishEntry("actualSettingsResponse", settingsResponse.body());
		var settings = success(settingsResponse);
		assertThat(settings.size()).isEqualTo(5);
		assertThat(settings.get("userId").asString()).isEqualTo(owner.id().toString());
		assertThat(settings.get("dataGeneration").asString()).isEqualTo("0");
		assertThat(settings.get("restLowSeconds").asInt()).isEqualTo(17);
		assertThat(settings.get("restMediumSeconds").asInt()).isEqualTo(29);
		assertThat(settings.get("restHighSeconds").asInt()).isEqualTo(43);
		assertThat(snapshot()).isEqualTo(original);
	}

	@Test void twoAccountsSettingsAreIsolated() throws Exception {
		try (var other = new Browser()) {
			other.login(UUID.randomUUID() + "@example.test");
			var otherId = UUID.fromString(JSON.readTree(other.get("/api/v1/auth/session").body()).get("userId").asString());
			jdbc.update("UPDATE user_workout_settings SET rest_low_seconds=99 WHERE user_id=?", otherId);
			assertThat(success(browser.get(SETTINGS)).get("userId").asString()).isEqualTo(owner.id().toString());
			assertThat(success(other.get(SETTINGS)).get("userId").asString()).isEqualTo(otherId.toString());
			assertThat(success(browser.get(SETTINGS)).get("restLowSeconds").asInt()).isEqualTo(180);
			assertThat(success(other.get(SETTINGS)).get("restLowSeconds").asInt()).isEqualTo(99);
		}
	}

	@ParameterizedTest @ValueSource(strings = {SETTINGS})
	void arbitraryQueriesAndGetBodiesAreRejectedWithoutWorkoutSql(String path) throws Exception {
		var observed = new CopyOnWriteArrayList<String>();
		WorkoutReadFixtures.observer = event -> { if (event.before()) observed.add(event.sql()); };
		for (var query : List.of("userId=" + UUID.randomUUID(), "dataGeneration=12", "generation=0", "sort=name", "limit=1", "unknown=", "userId=a&userId=b", "flag")) {
			error(browser.get(path + "?" + query), 400, "INVALID_REQUEST");
		}
		error(browser.send("GET", path, "{malformed", null), 400, "INVALID_REQUEST");
		assertThat(observed).noneMatch(WorkoutReadIntegrationTests::workoutSql);
	}

	@Test void missingSettingsNeverRepairTheSeed() throws Exception {
		jdbc.update("DELETE FROM exercise_definitions WHERE user_id=?", owner.id());
		jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?", owner.id());
		var before = snapshot();
		error(browser.get(SETTINGS), 500, "WORKOUT_SETTINGS_MISSING");
		assertThat(snapshot()).isEqualTo(before);
	}

	@ParameterizedTest @ValueSource(longs = {9007199254740993L, Long.MAX_VALUE})
	void bigGenerationIsAnExactJsonString(long generation) throws Exception {
		replaceGeneration(generation);
		var original = snapshot();
		for (var path : List.of(SETTINGS)) {
			var value = success(browser.get(path)).get("dataGeneration");
			assertThat(value.isString()).isTrue();
			assertThat(value.asString()).isEqualTo(Long.toString(generation));
		}
		assertThat(snapshot()).isEqualTo(original);
	}

	@ParameterizedTest @ValueSource(strings = {SETTINGS})
	void generationAndDataShareOneReadOnlyRepeatableReadSnapshot(String path) throws Exception {
		var executions = new CopyOnWriteArrayList<String>();
		var samples = new CopyOnWriteArrayList<List<Long>>();
		var requestIds = new CopyOnWriteArrayList<String>();
		var changed = new AtomicBoolean();
		WorkoutReadFixtures.observer = event -> {
			if (event.before()) executions.add(event.sql());
			if (event.sql().toLowerCase(Locale.ROOT).contains("spring_session")) {
				assertThat(TransactionSynchronizationManager.hasResource(app.getBean(EntityManagerFactory.class))).isFalse();
			}
			if (!event.before() && workoutSql(event.sql())) {
				assertThat(TransactionSynchronizationManager.hasResource(app.getBean(EntityManagerFactory.class))).isTrue();
				requestIds.add(org.slf4j.MDC.get("requestId"));
				assertThat(event.connection()).satisfies(connection -> {
					assertThat(connection.getAutoCommit()).isFalse();
					assertThat(connection.isReadOnly()).isTrue();
					assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
				});
				samples.add(transactionIdentity(event.connection()));
				if (event.sql().startsWith("SELECT data_generation FROM users") && changed.compareAndSet(false, true)) {
					replaceGeneration(1); // Independent PostgreSQL connection commits between the two actual SELECTs.
				}
			}
		};
		var response = browser.get(path);
		var result = success(response);
		WorkoutReadFixtures.observer = null;
		assertThat(changed).isTrue();
		assertThat(result.get("dataGeneration").asString()).isEqualTo("0");
		assertThat(result.get("restLowSeconds").asInt()).isEqualTo(180);
		assertThat(samples).hasSize(2).containsOnly(samples.getFirst());
		assertThat(requestIds).hasSize(2).containsOnly(response.headers().firstValue("X-Request-ID").orElseThrow());
		assertThat(executions.stream().filter(WorkoutReadIntegrationTests::workoutSql).toList()).hasSize(2)
				.allMatch(sql -> sql.toLowerCase(Locale.ROOT).startsWith("select "))
				.noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("for update") || sql.toLowerCase(Locale.ROOT).contains("for share"));
		assertThat(executions).noneMatch(sql -> sql.toLowerCase(Locale.ROOT).matches("(?s).*(insert into|update|delete from) (users|exercise_definitions|user_workout_settings).*"));
		var current = success(browser.get(path));
		assertThat(current.get("dataGeneration").asString()).isEqualTo("1");
		assertThat(current.get("restLowSeconds").asInt()).isEqualTo(77);
	}

	@ParameterizedTest @ValueSource(strings = {SETTINGS})
	void securityExceptionsStillReachExistingSecurityHandlers(String path) throws Exception {
		WorkoutReadFixtures.observer = event -> { if (event.before() && workoutSql(event.sql())) throw new AccessDeniedException("synthetic private detail"); };
		error(browser.get(path), 403, "ACCESS_DENIED");
		WorkoutReadFixtures.observer = event -> { if (event.before() && workoutSql(event.sql())) throw new AuthenticationCredentialsNotFoundException("synthetic private detail"); };
		error(browser.get(path), 401, "AUTHENTICATION_REQUIRED");
	}

	@Test void csrfAndExistingOtpAdviceKeepTheirCodeOnlyResponses() throws Exception {
		error(browser.send("POST", EXERCISES, "{}", null), 403, "CSRF_INVALID");
		error(browser.send("POST", "/api/v1/auth/email-otp/request", "{bad", browser.csrf()), 400, "INVALID_REQUEST");
		// The existing auth Advice deliberately maps its database failures differently.
		try (var anonymous = new Browser()) {
			var csrf = anonymous.csrf();
			WorkoutReadFixtures.observer = event -> { if (event.before() && event.sql().contains("email_otp_limits")) throw new DataIntegrityViolationException("synthetic private SQL"); };
			error(anonymous.send("POST", "/api/v1/auth/email-otp/request", JSON.writeValueAsString(Map.of("email", email)), csrf), 503, "OTP_UNAVAILABLE");
		}
	}

	@Test void actualSqlMappingFailureIs500WithoutSqlLeakage() throws Exception {
		jdbc.execute("ALTER TABLE user_workout_settings RENAME COLUMN rest_low_seconds TO compat_hidden_rest");
		try { error(browser.get(SETTINGS), 500, "WORKOUT_DATA_READ_FAILED"); }
		finally { jdbc.execute("ALTER TABLE user_workout_settings RENAME COLUMN compat_hidden_rest TO rest_low_seconds"); }
		assertThat(success(browser.get(SETTINGS)).get("restLowSeconds").asInt()).isEqualTo(180);
	}

	@ParameterizedTest @ValueSource(strings = {SETTINGS})
	void actualLostWorkoutConnectionIs503AndNextRequestRecovers(String path) throws Exception {
		var killed = new AtomicBoolean();
		WorkoutReadFixtures.observer = event -> {
			if (!event.before() && event.sql().startsWith("SELECT data_generation FROM users") && killed.compareAndSet(false, true)) {
				var pid = transactionIdentity(event.connection()).getFirst();
				assertThat(independentJdbc().queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid.intValue())).isTrue();
			}
		};
		error(browser.get(path), 503, "WORKOUT_DATA_UNAVAILABLE");
		WorkoutReadFixtures.observer = null;
		assertThat(killed).isTrue();
		success(browser.get(path));
	}

	static Stream<RuntimeException> failures() {
		return Stream.of(new IllegalArgumentException("synthetic secret SQL"), new DataIntegrityViolationException("synthetic secret SQL"),
				new CannotCreateTransactionException("synthetic secret SQL", new SQLException("private", "08001")),
				new DataAccessResourceFailureException("synthetic secret SQL"));
	}

	@ParameterizedTest @MethodSource("failures")
	void knownAvailabilityFailuresAndUnexpectedFailuresStayDistinct(RuntimeException failure) throws Exception {
		WorkoutReadFixtures.observer = event -> { if (event.before() && workoutSql(event.sql())) throw failure; };
		boolean unavailable = failure instanceof CannotCreateTransactionException || failure instanceof DataAccessResourceFailureException;
		error(browser.get(SETTINGS), unavailable ? 503 : 500, unavailable ? "WORKOUT_DATA_UNAVAILABLE" : "WORKOUT_DATA_READ_FAILED");
	}

	@Test void unsupportedMethodsRetain405RatherThanBecoming500() throws Exception {
		// The settings GET/PATCH contract keeps MVC's unsupported-method boundary.
		var token = browser.csrf();
		var result = browser.send("PUT", SETTINGS, "{}", token);
		assertThat(result.statusCode()).isEqualTo(405);
		headers(result);
		error(browser.send("POST", EXERCISES, "{}", token), 403, "ACCESS_DENIED");
	}

	@Test void retiredPersonalGetHasNoHandlerAndKeepsAuthenticationBoundary() throws Exception {
		try (var anonymous = new Browser()) { error(anonymous.get(EXERCISES),401,"AUTHENTICATION_REQUIRED"); }
		assertThat(browser.get(EXERCISES).statusCode()).isEqualTo(404);
		assertThat(app.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
				.getHandlerMethods().keySet().stream().flatMap(mapping -> mapping.getPatternValues().stream()))
				.doesNotContain(EXERCISES).contains(SETTINGS);
	}

	Object snapshot() {
		return List.of(jdbc.queryForList("SELECT * FROM users WHERE id=?", owner.id()),
				jdbc.queryForList("SELECT * FROM exercise_definitions WHERE user_id=? ORDER BY id", owner.id()),
				jdbc.queryForList("SELECT * FROM user_workout_settings WHERE user_id=?", owner.id()));
	}

	void replaceGeneration(long generation) {
		try (var connection = independentJdbc().getDataSource().getConnection()) {
			connection.setAutoCommit(false);
			try {
				var writer = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
				writer.update("DELETE FROM exercise_definitions WHERE user_id=?", owner.id());
				writer.update("DELETE FROM user_workout_settings WHERE user_id=?", owner.id());
				WeeklyPresetFixtures.clear(writer, owner.id());
				writer.update("DELETE FROM deload_analysis_versions WHERE user_id=?", owner.id()); writer.update("UPDATE users SET data_generation=? WHERE id=?", generation, owner.id());
				writer.update("INSERT INTO user_workout_settings(user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds) VALUES (?,?,77,88,99)", owner.id(), generation);
				connection.commit();
			} catch (Throwable failure) { connection.rollback(); throw failure; }
		} catch (SQLException exception) { throw new IllegalStateException(exception); }
	}

	static JdbcTemplate independentJdbc() { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())); }
	static List<Long> transactionIdentity(Connection connection) {
		try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid(), txid_current(), current_setting('transaction_read_only'), current_setting('transaction_isolation')")) {
			result.next();
			assertThat(result.getString(3)).isEqualTo("on");
			assertThat(result.getString(4)).isEqualTo("repeatable read");
			return List.of(result.getLong(1), result.getLong(2));
		} catch (SQLException exception) { throw new IllegalStateException(exception); }
	}
	static boolean workoutSql(String sql) {
		return sql.contains("FROM users") || sql.contains("exercise_definitions") || sql.contains("user_workout_settings");
	}
	static JsonNode success(HttpResponse<String> response) {
		assertThat(response.statusCode()).isEqualTo(200);
		headers(response);
		return JSON.readTree(response.body());
	}
	static void error(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		headers(response);
		assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree(JSON.writeValueAsString(Map.of("code", code))));
	}
	static void headers(HttpResponse<String> response) {
		assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
		assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
		assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
		var id = response.headers().firstValue("X-Request-ID").orElseThrow();
		assertThat(UUID.fromString(id).version()).isEqualTo(4);
		assertThat(id).isNotEqualTo("untrusted-request-id");
	}

	static final class Browser implements AutoCloseable {
		final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
		void login(String address) throws Exception {
			var token = csrf();
			var issued = send("POST", "/api/v1/auth/email-otp/request", JSON.writeValueAsString(Map.of("email", address)), token);
			assertThat(issued.statusCode()).isEqualTo(202);
			var message = app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString()));
			assertThat(send("POST", "/api/v1/auth/email-otp/verify", JSON.writeValueAsString(Map.of("email", address, "challengeId", message.issuanceId(), "code", message.code())), token).statusCode()).isEqualTo(204);
		}
		String csrf() throws Exception { return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString(); }
		HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
		HttpResponse<String> send(String method, String path, String body, String csrf) throws Exception {
			var base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
			var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(15)).header("X-Request-ID", "untrusted-request-id");
			if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
			if (body != null) request.header("Content-Type", "application/json");
			return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		}
		@Override public void close() { client.close(); }
	}
}
