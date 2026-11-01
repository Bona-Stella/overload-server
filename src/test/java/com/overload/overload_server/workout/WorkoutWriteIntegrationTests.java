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
import org.junit.jupiter.params.provider.Arguments;
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

import org.springframework.http.ResponseEntity;
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
		app = new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class, WorkoutReadFixtures.class, PersonalDomainHarness.class)
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

	@Test void createsDistinctSameNameExercisesAndReplaysWithoutChangingExistingRows() throws Exception {
		var before = snapshot(owner.id());
		UUID id = UUID.randomUUID(); String name = "  同名 벤치 Ω  ";
		var response = send("create", id, name);
		assertThat(response.statusCode()).isEqualTo(201); headers(response);
		var body = JSON.readTree(response.body());
		assertThat(body.size()).isEqualTo(4);
		assertThat(body.get("dataGeneration").isString()).isTrue();
		assertThat(body.get("userId").asString()).isEqualTo(owner.id().toString());
		assertThat(body.get("id").asString()).isEqualTo(id.toString());
		assertThat(body.get("name").asString()).isEqualTo(name);
		var row = row(id);
		assertThat(send("create", id, name).statusCode()).isEqualTo(200);
		assertThat(row(id)).isEqualTo(row);
		error(send("create", id, "different"), 409, "EXERCISE_ID_CONFLICT");
		assertThat(send("create", UUID.randomUUID(), name).statusCode()).isEqualTo(201);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions WHERE user_id=? AND name=?", Integer.class, owner.id(), name)).isEqualTo(2);
		assertThat(snapshot(owner.id()).get("settings")).isEqualTo(before.get("settings"));
		assertThat(JSON.readTree(browser.get("/api/v1/exercises").body()).get("exercises").size()).isEqualTo(22);
	}

	@Test void renameKeepsUuidCreatedAtAndOtherRowsAndSameValueDoesNotWriteAgain() throws Exception {
		var old = row(exercise); var before = snapshot(owner.id());
		var response = send("rename", exercise, "  새 이름 Ω  ");
		assertThat(response.statusCode()).isEqualTo(200); headers(response);
		var current = row(exercise);
		assertThat(current.get("created_at")).isEqualTo(old.get("created_at"));
		assertThat(current.get("id")).isEqualTo(old.get("id"));
		assertThat(current.get("version")).isEqualTo(1L);
		assertThat(send("rename", exercise, "  새 이름 Ω  ").statusCode()).isEqualTo(200);
		assertThat(row(exercise)).isEqualTo(current);
		assertThat(snapshot(owner.id()).get("settings")).isEqualTo(before.get("settings"));
		var get = browser.get("/api/v1/exercises"); headers(get);
		var values = JSON.readTree(get.body()); assertThat(values.size()).isEqualTo(3);
		assertThat(values.get("exercises").valueStream().filter(value -> value.get("id").asString().equals(exercise.toString())).findFirst().orElseThrow().get("name").asString()).isEqualTo("  새 이름 Ω  ");
	}

	@Test void restUpdatesAllValuesAtomicallyAndUsesExactExistingGetContract() throws Exception {
		var before = snapshot(owner.id());
		String body = rest("0", 1, 29, Integer.MAX_VALUE);
		var response = browser.send("PATCH", path("settings", exercise), body, csrf);
		assertThat(response.statusCode()).isEqualTo(200); headers(response);
		assertThat(JSON.readTree(response.body()).size()).isEqualTo(5);
		assertThat(JSON.readTree(browser.get("/api/v1/workout-settings").body())).isEqualTo(JSON.readTree(response.body()));
		var after = snapshot(owner.id());
		assertThat(browser.send("PATCH", path("settings", exercise), body, csrf).statusCode()).isEqualTo(200);
		assertThat(snapshot(owner.id())).isEqualTo(after);
		assertThat(after.get("exercises")).isEqualTo(before.get("exercises"));
		assertThat(jdbc.queryForObject("SELECT version FROM user_workout_settings WHERE user_id=?", Long.class, owner.id())).isEqualTo(1L);
	}

	@Test void renameToAnExistingNameKeepsBothExerciseIds() throws Exception {
		var other = jdbc.queryForMap("SELECT id,name FROM exercise_definitions WHERE user_id=? AND id<>? LIMIT 1",owner.id(),exercise);
		assertThat(send("rename",exercise,(String)other.get("name")).statusCode()).isEqualTo(200);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions WHERE user_id=? AND name=?",Integer.class,owner.id(),other.get("name"))).isEqualTo(2);
		assertThat(row((UUID)other.get("id")).get("name")).isEqualTo(other.get("name"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions WHERE user_id=?",Integer.class,owner.id())).isEqualTo(20);
	}

	@ParameterizedTest @ValueSource(strings = {"create", "rename", "settings"})
	void anonymousExpiredAndCsrfFailuresDoNotWrite(String kind) throws Exception {
		var before = snapshot(owner.id()); String body = payload(kind, UUID.randomUUID(), "name");
		try (var anonymous = new Browser()) { error(anonymous.send(method(kind), path(kind, exercise), body, null), 401, "AUTHENTICATION_REQUIRED"); }
		error(browser.send(method(kind), path(kind, exercise), body, null), 403, "CSRF_INVALID");
		error(browser.send(method(kind), path(kind, exercise), body, "invalid"), 403, "CSRF_INVALID");
		EmailOtpFixtures.CLOCK.advance(Duration.ofDays(91));
		error(browser.send(method(kind), path(kind, exercise), body, csrf), 401, "AUTHENTICATION_REQUIRED");
		assertThat(snapshot(owner.id())).isEqualTo(before);
	}

	@ParameterizedTest @ValueSource(strings = {"create", "rename", "settings"})
	void authenticatedSessionCannotRecreateAMissingOwner(String kind) throws Exception {
		jdbc.update("DELETE FROM exercise_definitions WHERE user_id=?",owner.id());
		jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?",owner.id());
		jdbc.update("DELETE FROM auth_identities WHERE user_id=?",owner.id());
		jdbc.update("DELETE FROM users WHERE id=?",owner.id());
		error(send(kind,exercise,"missing owner"),401,"AUTHENTICATION_REQUIRED");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?",Integer.class,owner.id())).isZero();
	}

	@Test void foreignMissingAndDeletedExerciseIdsCannotBeRenamedOrOverwritten() throws Exception {
		var foreign = app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email(UUID.randomUUID()+"@example.test"), null);
		com.overload.testfixture.CatalogFixtures.personalExercises(jdbc,foreign.id());
		UUID id = jdbc.queryForObject("SELECT id FROM exercise_definitions WHERE user_id=? LIMIT 1", UUID.class, foreign.id());
		var before = snapshot(foreign.id());
		error(send("rename", id, "attack"), 404, "EXERCISE_NOT_FOUND");
		error(send("create", id, "attack"), 409, "EXERCISE_ID_CONFLICT");
		assertThat(snapshot(foreign.id())).isEqualTo(before);
		error(send("rename", UUID.randomUUID(), "missing"), 404, "EXERCISE_NOT_FOUND");
		jdbc.update("UPDATE exercise_definitions SET deleted_at=current_timestamp WHERE id=?", exercise);
		var deleted = row(exercise);
		error(send("rename", exercise, "deleted"), 404, "EXERCISE_NOT_FOUND");
		error(send("create", exercise, "deleted"), 409, "EXERCISE_ID_CONFLICT");
		assertThat(row(exercise)).isEqualTo(deleted);
	}

	@ParameterizedTest @ValueSource(strings = {"create", "rename", "settings"})
	void staleGenerationIsRejectedAndLargeGenerationRemainsExact(String kind) throws Exception {
		long generation = 9007199254740993L;
		jdbc.update("DELETE FROM exercise_definitions WHERE user_id=?", owner.id());
		jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?", owner.id());
		jdbc.update("UPDATE users SET data_generation=? WHERE id=?", generation, owner.id());
		jdbc.update("INSERT INTO user_workout_settings(user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds) VALUES (?,?,180,120,90)", owner.id(), generation);
		var before = snapshot(owner.id());
		error(send(kind, exercise, "stale"), 409, "DATA_GENERATION_MISMATCH");
		assertThat(snapshot(owner.id())).isEqualTo(before);
		String current = payload(kind, exercise, "new generation").replace("\"0\"", "\""+generation+"\"");
		if (kind.equals("rename")) error(browser.send(method(kind), path(kind, exercise), current, csrf), 404, "EXERCISE_NOT_FOUND");
		else {
			var result = browser.send(method(kind), path(kind, exercise), current, csrf);
			assertThat(result.statusCode()).isEqualTo(kind.equals("create") ? 201 : 200);
			assertThat(JSON.readTree(result.body()).get("dataGeneration").asString()).isEqualTo(Long.toString(generation));
		}
	}

	static Stream<Arguments> invalidBodies() {
		String valid = "{\"id\":\"00000000-0000-4000-8000-000000000123\",\"dataGeneration\":\"0\",\"name\":\"name\"}";
		var bodies = new ArrayList<Arguments>();
		for (String value : List.of("null", "0", "true", "\"\"", "\"   \\t\"", "\"\\u0000\"", "\"\\uD800\"")) bodies.add(Arguments.of("create", valid.replace("\"name\":\"name\"", "\"name\":"+value)));
		for (String value : List.of("0", "null", "\"-1\"", "\"01\"", "\"9223372036854775808\"")) bodies.add(Arguments.of("rename", "{\"dataGeneration\":"+value+",\"name\":\"name\"}"));
		for (String value : List.of("0", "-1", "1.5", "1.0", "2147483648", "\"30\"", "true", "null")) bodies.add(Arguments.of("settings", "{\"dataGeneration\":\"0\",\"restLowSeconds\":"+value+",\"restMediumSeconds\":120,\"restHighSeconds\":90}"));
		for (String value : List.of("{}", "[]", "null", "{bad", "{\"dataGeneration\":\"0\",\"name\":\"name\",\"userId\":\"spoof\"}")) bodies.add(Arguments.of("rename", value));
		bodies.add(Arguments.of("create", valid.replace("00000000-0000-4000-8000-000000000123", "1-1-1-1-1")));
		return bodies.stream();
	}
	@ParameterizedTest @MethodSource("invalidBodies")
	void invalidJsonTypesValuesAndUnknownOwnerFieldsLeaveDomainUnchanged(String kind, String body) throws Exception {
		var before = snapshot(owner.id());
		error(browser.send(method(kind), path(kind, exercise), body, csrf), 400, "INVALID_REQUEST");
		assertThat(snapshot(owner.id())).isEqualTo(before);
	}

	@ParameterizedTest @ValueSource(strings = {"create", "rename", "settings"})
	void queryIsNotAnAlternateOwnerOrGenerationInput(String kind) throws Exception {
		var before = snapshot(owner.id());
		error(browser.send(method(kind), path(kind, exercise)+"?userId=spoof", payload(kind, UUID.randomUUID(), "name"), csrf), 400, "INVALID_REQUEST");
		assertThat(snapshot(owner.id())).isEqualTo(before);
	}

	@ParameterizedTest @ValueSource(strings = {"create", "rename", "settings"})
	void concurrentRequestsDoNotLoseUpdatesOrCreateDuplicateRows(String kind) throws Exception {
		UUID id = kind.equals("create") ? UUID.randomUUID() : exercise;
		var barrier = new CyclicBarrier(2);
		WorkoutReadFixtures.observer = event -> {
			String sql = event.sql().toLowerCase(Locale.ROOT);
			if (!event.before() && sql.startsWith("select") && sql.contains(kind.equals("settings") ? "from user_workout_settings" : "from exercise_definitions")) {
				try { barrier.await(8, TimeUnit.SECONDS); } catch (Exception failure) { throw new IllegalStateException(failure); }
			}
		};
		List<HttpResponse<String>> responses;
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> send(kind, id, "first"));
			var second = executor.submit(() -> kind.equals("settings")
					? browser.send("PATCH", path(kind,id), rest("0", 17, 29, 43), csrf)
					: send(kind, id, kind.equals("create") ? "first" : "second"));
			responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
		} finally { WorkoutReadFixtures.observer = null; }
		assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList()).containsExactly(kind.equals("create") ? 201 : 200, 409);
		var conflict = responses.stream().filter(response -> response.statusCode()==409).findFirst().orElseThrow();
		error(conflict,409,kind.equals("create") ? "EXERCISE_ID_CONFLICT" : "WRITE_CONFLICT");
		if (kind.equals("create")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions WHERE id=?",Integer.class,id)).isEqualTo(1);
			assertThat(send(kind,id,"first").statusCode()).isEqualTo(200);
		} else assertThat(jdbc.queryForObject(kind.equals("settings") ? "SELECT version FROM user_workout_settings WHERE user_id=?" : "SELECT version FROM exercise_definitions WHERE id=?",Long.class,kind.equals("settings") ? owner.id() : id)).isEqualTo(1L);
	}

	@ParameterizedTest @ValueSource(strings = {"create", "rename", "settings"})
	void actualDatabaseFailureAfterDmlRollsBackEntireWrite(String kind) throws Exception {
		var before = snapshot(owner.id()); var once = new AtomicBoolean();
		WorkoutReadFixtures.observer = event -> {
			if (!event.before() && domainDml(event.sql()) && once.compareAndSet(false,true)) {
				try (var statement = event.connection().createStatement()) { statement.execute("SELECT 1/0"); }
				catch (SQLException failure) { throw new IllegalStateException("Synthetic database failure",failure); }
			}
		};
		try { error(send(kind,kind.equals("create") ? UUID.randomUUID() : exercise,"rollback"),500,"WORKOUT_DATA_WRITE_FAILED"); }
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
		try { error(send("rename",exercise,"disconnected"),503,"WORKOUT_DATA_UNAVAILABLE"); }
		finally { WorkoutReadFixtures.observer = null; }
		assertThat(snapshot(owner.id())).isEqualTo(before);
		assertThat(send("rename",exercise,"recovered").statusCode()).isEqualTo(200);
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
		try { assertThat(send("rename",exercise,"same transaction").statusCode()).isEqualTo(200); }
		finally { WorkoutReadFixtures.observer = null; }
		assertThat(checked).isTrue(); assertThat(identities).hasSize(2); assertThat(new HashSet<>(identities)).hasSize(1);
	}

	@Test void missingSettingsFailsWithoutRecreatingDefaults() throws Exception {
		jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?",owner.id());
		error(send("settings",exercise,"unused"),500,"WORKOUT_SETTINGS_MISSING");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_workout_settings WHERE user_id=?",Integer.class,owner.id())).isZero();
	}

	HttpResponse<String> send(String kind, UUID id, String name) throws Exception { return browser.send(method(kind),path(kind,id),payload(kind,id,name),csrf); }
	static String method(String kind) { return kind.equals("create") ? "POST" : "PATCH"; }
	static String path(String kind, UUID id) { return kind.equals("settings") ? "/api/v1/workout-settings" : "/__personal_domain_test/exercises"+(kind.equals("rename") ? "/"+id : ""); }
	static String payload(String kind, UUID id, String name) {
		return kind.equals("settings") ? rest("0",31,47,59) : JSON.writeValueAsString(kind.equals("create")
				? Map.of("id",id,"dataGeneration","0","name",name) : Map.of("dataGeneration","0","name",name));
	}
	static String rest(String generation, int low, int medium, int high) { return JSON.writeValueAsString(Map.of("dataGeneration",generation,"restLowSeconds",low,"restMediumSeconds",medium,"restHighSeconds",high)); }
	static boolean domainDml(String sql) { String value=sql.toLowerCase(Locale.ROOT); return (value.startsWith("update")||value.startsWith("insert")) && (value.contains("exercise_definitions")||value.contains("user_workout_settings")); }
	static JdbcTemplate independent() { return new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())); }
	Map<String,Object> row(UUID id) { return jdbc.queryForMap("SELECT * FROM exercise_definitions WHERE id=?",id); }
	static Map<String,Object> snapshot(UUID user) {
		return Map.of("exercises",jdbc.queryForList("SELECT to_jsonb(e)::text FROM exercise_definitions e WHERE user_id=? ORDER BY id",String.class,user),
				"settings",jdbc.queryForList("SELECT to_jsonb(s)::text FROM user_workout_settings s WHERE user_id=?",String.class,user),
				"user",jdbc.queryForList("SELECT to_jsonb(u)::text FROM users u WHERE id=?",String.class,user));
	}
	static void error(HttpResponse<String> response, int status, String code) { assertThat(response.statusCode()).isEqualTo(status); headers(response); assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree(JSON.writeValueAsString(Map.of("code",code)))); }
	static void headers(HttpResponse<String> response) { WorkoutReadIntegrationTests.headers(response); }

	// Test-only aliases preserve validation/transaction regression coverage for retained internal CRUD.
    // Production URLs are independently asserted to return 403 below.
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods=false)
    @org.springframework.web.bind.annotation.RestController
    static class PersonalDomainHarness extends WorkoutWriteController {
        PersonalDomainHarness(WorkoutWriteService service) { super(service); }
        @Override @org.springframework.web.bind.annotation.PostMapping("/__personal_domain_test/exercises")
        ResponseEntity<ExerciseWriteResponse> create(@org.springframework.security.core.annotation.AuthenticationPrincipal com.overload.overload_server.security.SessionPrincipal p,
                @org.springframework.web.bind.annotation.RequestBody WorkoutWriteRequests.CreateExercise body, jakarta.servlet.http.HttpServletRequest request) { return super.create(p,body,request); }
        @Override @org.springframework.web.bind.annotation.PatchMapping("/__personal_domain_test/exercises/{id}")
        ResponseEntity<ExerciseWriteResponse> rename(@org.springframework.security.core.annotation.AuthenticationPrincipal com.overload.overload_server.security.SessionPrincipal p,
                @org.springframework.web.bind.annotation.PathVariable UUID id, @org.springframework.web.bind.annotation.RequestBody WorkoutWriteRequests.RenameExercise body, jakarta.servlet.http.HttpServletRequest request) { return super.rename(p,id,body,request); }
        @Override @org.springframework.web.bind.annotation.PatchMapping("/__personal_domain_test/settings")
        ResponseEntity<WorkoutSettingsResponse> settings(@org.springframework.security.core.annotation.AuthenticationPrincipal com.overload.overload_server.security.SessionPrincipal p,
                @org.springframework.web.bind.annotation.RequestBody WorkoutWriteRequests.ChangeRest body, jakarta.servlet.http.HttpServletRequest request) { return super.settings(p,body,request); }
    }

    @Test void productionPersonalWriteUrlsDenyAuthenticatedRequestsBeforeValidationAndNeverWrite() throws Exception {
        var before=snapshot(owner.id());
        for (String body:List.of(payload("create",UUID.randomUUID(),"test"),"{bad","{}"))
            error(browser.send("POST","/api/v1/exercises",body,csrf),403,"ACCESS_DENIED");
        for (UUID id:List.of(exercise,UUID.randomUUID()))
            error(browser.send("PATCH","/api/v1/exercises/"+id,payload("rename",id,"test"),csrf),403,"ACCESS_DENIED");
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
