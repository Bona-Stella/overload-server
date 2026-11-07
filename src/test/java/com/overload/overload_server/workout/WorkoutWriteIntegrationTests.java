package com.overload.overload_server.workout;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.Account;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.testfixture.EmailOtpFixtures;
import com.overload.testfixture.MixedPersistenceAssertions;
import com.overload.testfixture.WorkoutReadFixtures;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@Testcontainers
class WorkoutWriteIntegrationTests {
	static final String SETTINGS = "/api/v1/workout-settings";

	static final JsonMapper JSON = JsonMapper.builder().build();
	@Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_online_write_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
	static ConfigurableApplicationContext app;
	static JdbcTemplate jdbc;
	Account owner;
	Browser browser;
	UUID exercise;
	String csrf;

	@BeforeAll static void start() {
		assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432, 5433);
		var key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
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
		String email = UUID.randomUUID() + "@example.test";
		owner = app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email(email), null);
		com.overload.testfixture.CatalogFixtures.personalExercises(jdbc, owner.id());
		exercise = jdbc.queryForObject("SELECT id FROM exercise_definitions WHERE user_id=? ORDER BY id LIMIT 1", UUID.class, owner.id());
		browser = new Browser(); browser.login(email); csrf = browser.csrf();
	}

	@AfterEach void cleanup() { WorkoutReadFixtures.observer = null; if (browser != null) browser.close(); }
	@AfterAll static void stop() { if (app != null) app.close(); }

	@Test void restUpdatesAllValuesAtomicallyAndUsesExactExistingGetContract() throws Exception {
		var before = snapshot(owner.id());
		String body = rest("0", 1, 29, Integer.MAX_VALUE);
		var response = browser.send("PATCH", SETTINGS, body, csrf);
		assertThat(response.statusCode()).isEqualTo(200); headers(response);
		assertThat(JSON.readTree(response.body()).size()).isEqualTo(5);
		assertThat(JSON.readTree(browser.get("/api/v1/workout-settings").body())).isEqualTo(JSON.readTree(response.body()));
		var after = snapshot(owner.id());
		assertThat(browser.send("PATCH", SETTINGS, body, csrf).statusCode()).isEqualTo(200);
		assertThat(snapshot(owner.id())).isEqualTo(after);
		assertThat(after.get("exercises")).isEqualTo(before.get("exercises"));
		assertThat(jdbc.queryForObject("SELECT version FROM user_workout_settings WHERE user_id=?", Long.class, owner.id())).isEqualTo(1L);
	}

	@Test
	void anonymousExpiredAndCsrfFailuresDoNotWrite() throws Exception {
		var before = snapshot(owner.id()); String body = rest("0",31,47,59);
		try (var anonymous = new Browser()) { error(anonymous.send("PATCH", SETTINGS, body, null), 401, "AUTHENTICATION_REQUIRED"); }
		error(browser.send("PATCH", SETTINGS, body, null), 403, "CSRF_INVALID");
		error(browser.send("PATCH", SETTINGS, body, "invalid"), 403, "CSRF_INVALID");
		EmailOtpFixtures.CLOCK.advance(Duration.ofDays(91));
		error(browser.send("PATCH", SETTINGS, body, csrf), 401, "AUTHENTICATION_REQUIRED");
		assertThat(snapshot(owner.id())).isEqualTo(before);
	}

	@Test
	void authenticatedSessionCannotRecreateAMissingOwner() throws Exception {
		jdbc.update("DELETE FROM exercise_definitions WHERE user_id=?",owner.id());
		jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?",owner.id());
		jdbc.update("DELETE FROM auth_identities WHERE user_id=?",owner.id());
		jdbc.update("DELETE FROM deload_analysis_versions WHERE user_id=?",owner.id());
		jdbc.update("DELETE FROM users WHERE id=?",owner.id());
		error(changeSettings(),401,"AUTHENTICATION_REQUIRED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?",Integer.class,owner.id())).isZero();
	}

	@Test
	void staleGenerationIsRejectedAndLargeGenerationRemainsExact() throws Exception {
		long generation = 9007199254740993L;
		jdbc.update("DELETE FROM exercise_definitions WHERE user_id=?", owner.id());
		jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?", owner.id());
		jdbc.update("DELETE FROM deload_analysis_versions WHERE user_id=?", owner.id()); jdbc.update("UPDATE users SET data_generation=? WHERE id=?", generation, owner.id());
		jdbc.update("INSERT INTO user_workout_settings(user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds) VALUES (?,?,180,120,90)", owner.id(), generation);
		var before = snapshot(owner.id());
		error(changeSettings(), 409, "DATA_GENERATION_MISMATCH");
		assertThat(snapshot(owner.id())).isEqualTo(before);
		String current = rest("0",31,47,59).replace("\"0\"", "\""+generation+"\"");
		var result = browser.send("PATCH", SETTINGS, current, csrf);
		assertThat(result.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(result.body()).get("dataGeneration").asString()).isEqualTo(Long.toString(generation));
	}

	static Stream<String> invalidBodies() {
		String valid = rest("0", 31, 47, 59);
		var bodies = new ArrayList<String>();
		for (String value : List.of("0", "-1", "1.5", "1.0", "2147483648", "\"30\"", "true", "null")) bodies.add(valid.replace("\"restLowSeconds\":31", "\"restLowSeconds\":" + value));
		for (String value : List.of("0", "null", "\"-1\"", "\"01\"", "\"9223372036854775808\"")) bodies.add(valid.replace("\"dataGeneration\":\"0\"", "\"dataGeneration\":" + value));
		for (String value : List.of("{}", "[]", "null", "{bad", valid.replace("}", ",\"userId\":\"spoof\"}"))) bodies.add(value);
		return bodies.stream();
	}
	@ParameterizedTest @MethodSource("invalidBodies")
	void invalidJsonTypesValuesAndUnknownOwnerFieldsLeaveDomainUnchanged(String body) throws Exception {
		var before = snapshot(owner.id());
		error(browser.send("PATCH", SETTINGS, body, csrf), 400, "INVALID_REQUEST");
		assertThat(snapshot(owner.id())).isEqualTo(before);
	}

	@Test
	void queryIsNotAnAlternateOwnerOrGenerationInput() throws Exception {
		var before = snapshot(owner.id());
		error(browser.send("PATCH", SETTINGS+"?userId=spoof", rest("0",31,47,59), csrf), 400, "INVALID_REQUEST");
		assertThat(snapshot(owner.id())).isEqualTo(before);
	}

	@Test
	void concurrentSettingsUpdatesRejectLostUpdates() throws Exception {
		var barrier = new CyclicBarrier(2);
		WorkoutReadFixtures.observer = event -> {
			String sql = event.sql().toLowerCase(Locale.ROOT);
			if (!event.before() && sql.startsWith("select") && sql.contains("from user_workout_settings")) {
				try { barrier.await(8, TimeUnit.SECONDS); } catch (Exception failure) { throw new IllegalStateException(failure); }
			}
		};
		List<HttpResponse<String>> responses;
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> changeSettings());
			var second = executor.submit(() -> browser.send("PATCH", SETTINGS, rest("0", 17, 29, 43), csrf));
			responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
		} finally { WorkoutReadFixtures.observer = null; }
		assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList()).containsExactly(200, 409);
		var conflict = responses.stream().filter(response -> response.statusCode()==409).findFirst().orElseThrow();
		error(conflict,409,"WRITE_CONFLICT");
		assertThat(jdbc.queryForObject("SELECT version FROM user_workout_settings WHERE user_id=?",Long.class,owner.id())).isEqualTo(1L);
	}

	@Test
	void actualDatabaseFailureAfterDmlRollsBackEntireWrite() throws Exception {
		var before = snapshot(owner.id()); var once = new AtomicBoolean();
		WorkoutReadFixtures.observer = event -> {
			if (!event.before() && domainDml(event.sql()) && once.compareAndSet(false,true)) {
				try (var statement = event.connection().createStatement()) { statement.execute("SELECT 1/0"); }
				catch (SQLException failure) { throw new IllegalStateException("Synthetic database failure",failure); }
			}
		};
		try { error(changeSettings(),500,"WORKOUT_DATA_WRITE_FAILED"); }
		finally { WorkoutReadFixtures.observer = null; }
		assertThat(once).isTrue(); assertThat(snapshot(owner.id())).isEqualTo(before);
	}

	@Test void connectionLossAfterDmlIs503AndDoesNotCommit() throws Exception {
		var before = snapshot(owner.id()); var once = new AtomicBoolean();
		WorkoutReadFixtures.observer = event -> {
			if (!event.before() && domainDml(event.sql()) && once.compareAndSet(false,true)) {
				try (var statement = event.connection().createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid()")) {
					result.next(); independent().queryForObject("SELECT pg_terminate_backend(?)",Boolean.class,result.getInt(1));
					try (var probe = event.connection().createStatement()) { probe.execute("SELECT 1"); }
				} catch (SQLException failure) { throw new IllegalStateException("Synthetic connection loss", failure); }
			}
		};
		try { error(changeSettings(),503,"WORKOUT_DATA_UNAVAILABLE"); }
		finally { WorkoutReadFixtures.observer = null; }
		assertThat(snapshot(owner.id())).isEqualTo(before);
		assertThat(changeSettings().statusCode()).isEqualTo(200);
	}

	@Test void ownerLockPreventsGenerationMutationAndJdbcJpaShareOneTransaction() throws Exception {
		var identities = new ArrayList<String>(); var checked = new AtomicBoolean();
		WorkoutReadFixtures.observer = event -> {
			if (event.before() || !(event.sql().contains("FOR SHARE") || domainDml(event.sql()))) return;
			try (var statement = event.connection().createStatement(); var rs = statement.executeQuery("SELECT pg_backend_pid(),txid_current(),current_setting('transaction_read_only')")) {
				rs.next(); assertThat(rs.getString(3)).isEqualTo("off"); identities.add(rs.getInt(1)+":"+rs.getLong(2));
			} catch (SQLException failure) { throw new IllegalStateException(failure); }
			if (event.sql().contains("FOR SHARE") && checked.compareAndSet(false,true)) {
				try (var connection = Objects.requireNonNull(independent().getDataSource()).getConnection(); var statement = connection.createStatement()) {
					statement.execute("SET lock_timeout='150ms'");
					assertThatThrownBy(() -> statement.executeUpdate("UPDATE users SET data_generation=data_generation WHERE id='"+owner.id()+"'"))
							.isInstanceOf(SQLException.class).satisfies(failure -> assertThat(((SQLException)failure).getSQLState()).isEqualTo("55P03"));
				} catch (SQLException failure) { throw new IllegalStateException(failure); }
			}
		};
		try { assertThat(changeSettings().statusCode()).isEqualTo(200); }
		finally { WorkoutReadFixtures.observer = null; }
		assertThat(checked).isTrue(); assertThat(identities).hasSize(2); assertThat(new HashSet<>(identities)).hasSize(1);
	}

	@Test void missingSettingsFailsWithoutRecreatingDefaults() throws Exception {
		jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?",owner.id());
		error(changeSettings(),500,"WORKOUT_SETTINGS_MISSING");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_workout_settings WHERE user_id=?",Integer.class,owner.id())).isZero();
	}

	HttpResponse<String> changeSettings() throws Exception { return browser.send("PATCH", SETTINGS, rest("0",31,47,59), csrf); }
	static String rest(String generation, int low, int medium, int high) { return JSON.writeValueAsString(Map.of("dataGeneration",generation,"restLowSeconds",low,"restMediumSeconds",medium,"restHighSeconds",high)); }
	static boolean domainDml(String sql) { String value=sql.toLowerCase(Locale.ROOT); return (value.startsWith("update")||value.startsWith("insert")) && value.contains("user_workout_settings"); }
	static JdbcTemplate independent() { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())); }
	static Map<String,Object> snapshot(UUID user) {
		return Map.of("exercises",jdbc.queryForList("SELECT to_jsonb(e)::text FROM exercise_definitions e WHERE user_id=? ORDER BY id",String.class,user),
				"settings",jdbc.queryForList("SELECT to_jsonb(s)::text FROM user_workout_settings s WHERE user_id=?",String.class,user),
				"user",jdbc.queryForList("SELECT to_jsonb(u)::text FROM users u WHERE id=?",String.class,user));
	}
	static void error(HttpResponse<String> response, int status, String code) { assertThat(response.statusCode()).isEqualTo(status); headers(response); assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree(JSON.writeValueAsString(Map.of("code",code)))); }
	static void headers(HttpResponse<String> response) { WorkoutReadIntegrationTests.headers(response); }

	@ParameterizedTest @ValueSource(strings = {"POST", "PATCH"})
	void productionPersonalWriteUrlsKeepAuthenticationAndCsrfBoundaries(String method) throws Exception {
		String path = "/api/v1/exercises" + (method.equals("PATCH") ? "/" + exercise : "");
		var before = snapshot(owner.id());
		try (var anonymous = new Browser()) { error(anonymous.send(method,path,"{}",null),401,"AUTHENTICATION_REQUIRED"); }
		error(browser.send(method,path,"{}",null),403,"CSRF_INVALID");
		error(browser.send(method,path,"{}","invalid"),403,"CSRF_INVALID");
		EmailOtpFixtures.CLOCK.advance(Duration.ofDays(91));
		error(browser.send(method,path,"{}",csrf),401,"AUTHENTICATION_REQUIRED");
		assertThat(snapshot(owner.id())).isEqualTo(before);
	}

    @Test void productionPersonalWriteUrlsDenyAuthenticatedRequestsBeforeValidationAndNeverWrite() throws Exception {
        var before=snapshot(owner.id());
        for (String body:List.of(JSON.writeValueAsString(Map.of("id",UUID.randomUUID(),"dataGeneration","0","name","test")),"{bad","{}"))
            error(browser.send("POST","/api/v1/exercises",body,csrf),403,"ACCESS_DENIED");
        for (UUID id:List.of(exercise,UUID.randomUUID()))
            error(browser.send("PATCH","/api/v1/exercises/"+id,"{\"dataGeneration\":\"0\",\"name\":\"test\"}",csrf),403,"ACCESS_DENIED");
        assertThat(snapshot(owner.id())).isEqualTo(before);
    }

    static final class Browser implements AutoCloseable {
		final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
		void login(String email) throws Exception {
			String token=csrf(); var issued=send("POST","/api/v1/auth/email-otp/request",JSON.writeValueAsString(Map.of("email",email)),token);
			assertThat(issued.statusCode()).isEqualTo(202);
			var message=app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString()));
			assertThat(send("POST","/api/v1/auth/email-otp/verify",JSON.writeValueAsString(Map.of("email",email,"challengeId",message.issuanceId(),"code",message.code())),token).statusCode()).isEqualTo(204);
		}
		String csrf() throws Exception { return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString(); }
		HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null); }
		HttpResponse<String> send(String method,String path,String body,String token) throws Exception {
			var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort()+path))
					.timeout(Duration.ofSeconds(15)).header("X-Request-ID","untrusted-request-id");
			if(token!=null) request.header("X-CSRF-TOKEN",token);
			if(body!=null) request.header("Content-Type","application/json");
			return client.send(request.method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
		}
		@Override public void close() { client.close(); }
	}
}
