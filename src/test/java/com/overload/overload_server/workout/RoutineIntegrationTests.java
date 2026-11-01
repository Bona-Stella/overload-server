package com.overload.overload_server.workout;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.*;
import com.overload.testfixture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@Testcontainers
class RoutineIntegrationTests {
	static final JsonMapper JSON = JsonMapper.builder().build();
	@Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_routine_isolated_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
	static ConfigurableApplicationContext app;
	static JdbcTemplate jdbc;
	Account owner; Browser browser; String csrf;
	UUID routine, exercise, first, second, cardio, otherCardio;

	@BeforeAll static void start() {
		assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432, 5433);
		assertThat(POSTGRES.getDatabaseName()).isEqualTo("overload_routine_isolated_test");
		var key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
		app = new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class, WorkoutReadFixtures.class)
				.web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
						"--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
						"--spring.datasource.password=" + POSTGRES.getPassword(), "--server.address=127.0.0.1", "--server.port=0",
						"--OTP_HMAC_KEY=" + Base64.getEncoder().encodeToString(key), "--OTP_EMAIL_ENABLED=false", "--GOOGLE_OIDC_ENABLED=false",
						"--GOOGLE_REAUTH_ENABLED=false", "--spring.session.jdbc.cleanup-cron=-", "--logging.level.root=WARN", "--spring.main.banner-mode=off");
		jdbc = app.getBean(JdbcTemplate.class); MixedPersistenceAssertions.verify(app);
	}
	@BeforeEach void prepare() throws Exception {
		WorkoutReadFixtures.observer = null; EmailOtpFixtures.CLOCK.reset(); app.getBean(EmailOtpFixtures.FakeSender.class).reset();
		jdbc.update("DELETE FROM email_otp_rate_limits");
		String email = UUID.randomUUID() + "@example.test";
		owner = app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email(email), null);
		exercise = CatalogFixtures.exercise(jdbc);
		routine = UUID.randomUUID(); first = UUID.randomUUID(); second = UUID.randomUUID(); cardio = UUID.randomUUID(); otherCardio = UUID.randomUUID();
		browser = new Browser(); browser.login(email); csrf = browser.csrf();
	}
	@AfterEach void cleanup() { WorkoutReadFixtures.observer = null; if (browser != null) browser.close(); }
	@AfterAll static void stop() { if (app != null) app.close(); }

	@Test void emptyAccountHasNoRoutinesAndMixedCreateAllowsDuplicateNamesAndRepeatedExercise() throws Exception {
		assertThat(node(browser.get("/api/v1/routines")).get("routines").size()).isZero();
		var oldExercise = jdbc.queryForMap("SELECT * FROM standard_exercise WHERE id=?", exercise);
		var response = create(); assertThat(response.statusCode()).isEqualTo(201); headers(response);
		assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo(path());
		var result = node(response); assertThat(result.get("name").asString()).isEqualTo("  同名 루틴 Ω  ");
		assertThat(result.get("version").asString()).isEqualTo("0");
		assertThat(result.get("dataGeneration").isString()).isTrue();
		assertThat(result.get("weightPlacements").size()).isEqualTo(2);
		assertThat(result.get("cardioPlacements").size()).isEqualTo(2);
		assertThat(result.get("weightPlacements").get(0).get("initialWeightKg").asString()).isEqualTo("60");
		assertThat(result.get("weightPlacements").get(1).get("approvedWeightKg").asString()).isEqualTo("0");
		assertThat(events()).isEmpty();
		error(browser.send("POST", "/api/v1/routines", json(createBody()), csrf), 409, "ROUTINE_ID_CONFLICT");
		var duplicateName = createBody(); duplicateName.put("id", UUID.randomUUID());
		duplicateName.put("weightPlacements", List.of(newWeight(UUID.randomUUID(), "1")));
		duplicateName.put("cardioPlacements", List.of());
		assertThat(browser.send("POST", "/api/v1/routines", json(duplicateName), csrf).statusCode()).isEqualTo(201);
		assertThat(node(browser.get("/api/v1/routines")).get("routines").size()).isEqualTo(2);
		assertThat(jdbc.queryForMap("SELECT * FROM standard_exercise WHERE id=?", exercise)).isEqualTo(oldExercise);
		assertThat(browser.get("/api/v1/exercises").statusCode()).isEqualTo(200);
		assertThat(browser.get("/api/v1/workout-settings").statusCode()).isEqualTo(200);
	}
	@Test void childChangesSwapsAndApprovalIncrementParentExactlyOnceAndNoopDoesNotWrite() throws Exception {
		create(); var input = updateBody(0);
		input.put("weightPlacements", List.of(weight(second, "EXISTING", 4), weight(first, "EXISTING", 3)));
		input.put("cardioPlacements", List.of(cardio(otherCardio, "EXISTING"), cardio(cardio, "EXISTING")));
		input.put("weightApprovals", List.of(approval(first, "62.5")));
		var result = node(put(input)); assertThat(result.get("version").asString()).isEqualTo("1");
		assertThat(weightRow(first).get("initial_weight").toString()).isEqualTo("60.000000");
		assertThat(weightRow(first).get("approved_weight").toString()).isEqualTo("62.500000");
		assertThat(weightRow(second).get("approved_weight").toString()).isEqualTo("0.000000");
		assertThat(events()).hasSize(1); var fact = events().getFirst();
		assertThat(fact).contains("ROUTINE_EDITOR", "62.500000", "60.000000");
		assertThat(result.get("weightPlacements").get(0).get("id").asString()).isEqualTo(second.toString());
		input.put("expectedVersion", "1"); var before = snapshot();
		var dml = new AtomicInteger(); WorkoutReadFixtures.observer = event -> {
			if (event.before() && domainDml(event.sql())) dml.incrementAndGet();
		};
		try { assertThat(node(put(input)).get("version").asString()).isEqualTo("1"); }
		finally { WorkoutReadFixtures.observer = null; }
		assertThat(dml).hasValue(0); assertThat(snapshot()).isEqualTo(before);
		input.put("expectedVersion", "0"); error(put(input), 409, "ROUTINE_VERSION_CONFLICT");
		assertThat(snapshot()).isEqualTo(before);
	}
	@Test void explicitRestorePreservesWeightLineageAndCardioIdsWithoutNewApprovalFacts() throws Exception {
		create(); var input = updateBody(0); input.put("weightApprovals", List.of(approval(first, "65"))); put(input);
		var event = events(); var initial = weightRow(first).get("initial_weight");
		input = updateBody(1); input.put("weightPlacements", List.of(weight(second, "EXISTING", 3)));
		input.put("cardioPlacements", List.of(cardio(otherCardio, "EXISTING"))); put(input);
		assertThat(weightRow(first).get("deleted_at")).isNotNull();
		var removed = node(browser.get(path() + "/removed-placements"));
		assertThat(removed.get("weightPlacements").get(0).get("id").asString()).isEqualTo(first.toString());
		assertThat(removed.get("cardioPlacements").get(0).get("id").asString()).isEqualTo(cardio.toString());
		input = updateBody(2); input.put("weightPlacements", List.of(weight(first, "RESTORE", 3), weight(second, "EXISTING", 3)));
		input.put("cardioPlacements", List.of(cardio(cardio, "RESTORE"), cardio(otherCardio, "EXISTING")));
		assertThat(node(put(input)).get("version").asString()).isEqualTo("3");
		assertThat(weightRow(first)).containsEntry("initial_weight", initial).containsEntry("deleted_at", null);
		assertThat(weightRow(first).get("approved_weight").toString()).isEqualTo("65.000000");
		assertThat(events()).isEqualTo(event);
		assertThat(node(browser.get(path() + "/removed-placements")).get("weightPlacements").size()).isZero();
	}
	@Test void newBaselineUsesNewIdAndInitialValuesCannotOverwriteExistingOrRestoredPlacements() throws Exception {
		create(); var input = updateBody(0); var forbidden = weight(first, "EXISTING", 3); forbidden.put("initialWeightKg", "1");
		input.put("weightPlacements", List.of(forbidden)); var before = snapshot();
		error(put(input), 400, "INVALID_REQUEST"); assertThat(snapshot()).isEqualTo(before);
		input = updateBody(0); var newId = UUID.randomUUID(); var fresh = newWeight(newId, "0.000001"); fresh.put("mode", "NEW");
		input.put("weightPlacements", List.of(fresh)); put(input);
		assertThat(weightRow(first).get("deleted_at")).isNotNull();
		assertThat(weightRow(newId).get("initial_weight").toString()).isEqualTo("0.000001");
		assertThat(events()).isEmpty();
		input = updateBody(1); input.put("weightPlacements", List.of(weight(first, "RESTORE", 3), weight(newId, "EXISTING", 3)));
		var restored = weight(first, "RESTORE", 3); restored.put("initialWeightKg", "10"); input.put("weightPlacements", List.of(restored));
		error(put(input), 400, "INVALID_REQUEST");
	}
	@Test void routineDeletePreservesPlacementAndEventFactsAndCannotBeUndoneByOldWrites() throws Exception {
		create(); var input = updateBody(0); input.put("weightApprovals", List.of(approval(first, "65"))); put(input);
		var rows = jdbc.queryForList("SELECT to_jsonb(p)::text FROM routine_weight_placements p WHERE routine_id=? ORDER BY id", String.class, routine);
		var event = events();
		assertThat(browser.send("DELETE", path(), json(Map.of("dataGeneration", "0", "expectedVersion", "1")), csrf).statusCode()).isEqualTo(204);
		var before = snapshot();
		assertThat(browser.send("DELETE", path(), json(Map.of("dataGeneration", "0", "expectedVersion", "0")), csrf).statusCode()).isEqualTo(204);
		error(browser.get(path()), 404, "ROUTINE_NOT_FOUND"); error(put(updateBody(2)), 404, "ROUTINE_NOT_FOUND");
		error(create(), 409, "ROUTINE_ID_CONFLICT");
		assertThat(snapshot()).isEqualTo(before); assertThat(events()).isEqualTo(event);
		assertThat(jdbc.queryForList("SELECT to_jsonb(p)::text FROM routine_weight_placements p WHERE routine_id=? ORDER BY id", String.class, routine)).isEqualTo(rows);
	}
	@Test void approvalFailureRollsBackPreviouslyFlushedNameOrderPlanAndWeights() throws Exception {
		create(); var before = snapshot(); var input = updateBody(0); input.put("name", "must roll back");
		input.put("weightPlacements", List.of(weight(second, "EXISTING", 5), weight(first, "EXISTING", 3)));
		input.put("weightApprovals", List.of(approval(first, "62.5")));
		var observed = new AtomicBoolean();
		WorkoutReadFixtures.observer = event -> {
			if (event.before() && event.sql().toLowerCase(Locale.ROOT).startsWith("insert into progress_change_events")) {
				observed.set(true); throw new org.springframework.dao.DataAccessResourceFailureException("isolated injected failure");
			}
		};
		try { error(put(input), 503, "WORKOUT_DATA_UNAVAILABLE"); } finally { WorkoutReadFixtures.observer = null; }
		assertThat(observed).isTrue(); assertThat(snapshot()).isEqualTo(before);
	}
	@ParameterizedTest @ValueSource(strings = {"PUT", "DELETE"})
	void concurrentRequestsWaitOnRoutineAndOnlyOneExpectedVersionCanChangeIt(String competingMethod) throws Exception {
		create(); var barrier = new CyclicBarrier(2); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
		var firstLocked = new AtomicBoolean();
		WorkoutReadFixtures.observer = event -> {
			String sql = event.sql().toLowerCase(Locale.ROOT);
			if (sql.startsWith("select") && sql.contains("from routines ") && sql.contains("for ")) {
				try {
					if (event.before()) barrier.await(10, TimeUnit.SECONDS);
					else if (firstLocked.compareAndSet(false, true)) { locked.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout"); }
				} catch (Exception failure) { throw new IllegalStateException(failure); }
			}
		};
		List<HttpResponse<String>> responses;
		try (var executor = Executors.newFixedThreadPool(2)) {
			var input = updateBody(0); input.put("weightApprovals", List.of(approval(first, "65")));
			var one = executor.submit(() -> put(input));
			var two = executor.submit(() -> competingMethod.equals("PUT") ? put(updateBodyWithCount(0, 4))
					: browser.send("DELETE", path(), json(Map.of("dataGeneration", "0", "expectedVersion", "0")), csrf));
			try {
				assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
				var independent = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting;
				do { waiting = independent.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%from routines %')", Boolean.class); if (!waiting) Thread.sleep(20); }
				while (!waiting && System.nanoTime() < deadline);
				assertThat(waiting).isTrue();
			} finally { release.countDown(); }
			responses = List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS));
		} finally { release.countDown(); WorkoutReadFixtures.observer = null; }
		assertThat(jdbc.queryForObject("SELECT version FROM routines WHERE id=?", Long.class, routine)).isEqualTo(1);
		if (competingMethod.equals("PUT")) assertThat(responses.stream().map(HttpResponse::statusCode).sorted().toList()).containsExactly(200, 409);
		else assertThat(responses.stream().map(HttpResponse::statusCode).toList()).satisfiesAnyOf(
				codes -> assertThat(codes).isEqualTo(List.of(200, 409)), codes -> assertThat(codes).isEqualTo(List.of(404, 204)));
		assertThat(events().size()).isLessThanOrEqualTo(1);
	}
	@Test void maxPlannedCountsAndWeightPrecisionAreStoredWithoutMaterializingSets() throws Exception {
		var body = createBody(); var weight = newWeight(first, "99999999999999.999999");
		weight.put("setCount", Integer.MAX_VALUE); weight.put("minReps", Integer.MAX_VALUE); weight.put("maxReps", Integer.MAX_VALUE);
		body.put("weightPlacements", List.of(weight)); body.put("cardioPlacements", List.of());
		org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(8), () -> assertThat(browser.send("POST", "/api/v1/routines", json(body), csrf).statusCode()).isEqualTo(201));
		assertThat(weightRow(first).get("set_count")).isEqualTo(Integer.MAX_VALUE);
		assertThat(node(browser.get(path())).get("weightPlacements").get(0).get("initialWeightKg").asString()).isEqualTo("99999999999999.999999");
	}
	@ParameterizedTest @ValueSource(strings = {"weightPlacements", "cardioPlacements", "weightApprovals", "name", "expectedVersion", "dataGeneration"})
	void missingFullPutFieldIsRejectedWithoutImplicitDeletion(String missing) throws Exception {
		create(); var input = updateBody(0); input.remove(missing); var before = snapshot();
		error(put(input), 400, "INVALID_REQUEST"); assertThat(snapshot()).isEqualTo(before);
	}
	@Test void foreignResourcesStaleGenerationDeletedExerciseAndInactiveRestoreAreRejected() throws Exception {
		create(); var before = snapshot(); var input = updateBody(0);
		input.put("dataGeneration", "1"); error(put(input), 409, "DATA_GENERATION_MISMATCH");
		input = updateBody(0); input.put("weightPlacements", List.of(weight(first, "RESTORE", 3))); error(put(input), 409, "PLACEMENT_STATE_CONFLICT");
		input.put("weightPlacements", List.of(weight(UUID.randomUUID(), "EXISTING", 3))); error(put(input), 404, "PLACEMENT_NOT_FOUND");
		var foreign = app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email(UUID.randomUUID() + "@example.test"), null);
		var foreignExercise = UUID.randomUUID();
		var foreignWeight = newWeight(UUID.randomUUID(), "1"); foreignWeight.put("standardExerciseId", foreignExercise);
		input = updateBody(0); foreignWeight.put("mode", "NEW"); input.put("weightPlacements", List.of(foreignWeight));
		error(put(input), 404, "EXERCISE_NOT_FOUND"); assertThat(snapshot()).isEqualTo(before);
		try (var other = new Browser()) {
			other.login(jdbc.queryForObject("SELECT subject FROM auth_identities WHERE user_id=?", String.class, foreign.id()));
			error(other.get(path()), 404, "ROUTINE_NOT_FOUND");
			error(other.send("PUT", path(), json(updateBody(0)), other.csrf()), 404, "ROUTINE_NOT_FOUND");
			error(other.send("DELETE", path(), json(Map.of("dataGeneration", "0", "expectedVersion", "0")), other.csrf()), 404, "ROUTINE_NOT_FOUND");
		}
		jdbc.update("UPDATE standard_exercise SET is_active=false WHERE id=?", exercise);
		assertThat(put(updateBody(0)).statusCode()).isEqualTo(200);
		var inactive = newWeight(UUID.randomUUID(), "1"); inactive.put("mode", "NEW");
		input = updateBody(0); input.put("weightPlacements", List.of(inactive));
		error(put(input), 404, "EXERCISE_NOT_FOUND");
	}
	@Test void largeGenerationIsExactAndOldGenerationCannotWrite() throws Exception {
		jdbc.update("DELETE FROM exercise_definitions WHERE user_id=?", owner.id()); jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?", owner.id());
		long generation = 9007199254740993L; jdbc.update("UPDATE users SET data_generation=? WHERE id=?", generation, owner.id());
		error(create(), 409, "DATA_GENERATION_MISMATCH");
		var body = createBody(); body.put("dataGeneration", Long.toString(generation));
		assertThat(node(browser.send("POST", "/api/v1/routines", json(body), csrf)).get("dataGeneration").asString()).isEqualTo(Long.toString(generation));
		error(put(updateBody(0)), 409, "DATA_GENERATION_MISMATCH");
		assertThat(node(browser.get(path())).get("dataGeneration").asString()).isEqualTo(Long.toString(generation));
	}
	@Test void authenticationCsrfQueriesBodiesAndProtocolErrorsKeepExistingBoundaries() throws Exception {
		create();
		try (var anonymous = new Browser()) {
			for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
				String url = method.equals("POST") ? "/api/v1/routines" : path();
				error(anonymous.send(method, url, method.equals("GET") ? null : "{}", null), 401, "AUTHENTICATION_REQUIRED");
			}
		}
		for (String method : List.of("POST", "PUT", "DELETE")) error(browser.send(method, method.equals("POST") ? "/api/v1/routines" : path(), "{}", null), 403, "CSRF_INVALID");
		error(browser.get(path() + "?userId=spoof"), 400, "INVALID_REQUEST");
		error(browser.send("GET", path(), "{}", null), 400, "INVALID_REQUEST");
		error(browser.get("/api/v1/routines/1-1-1-1-1"), 400, "INVALID_REQUEST");
		assertThat(browser.send("PATCH", path(), "{}", csrf).statusCode()).isEqualTo(405);
		var input = updateBody(0); input.put("userId", owner.id()); error(put(input), 400, "INVALID_REQUEST");
	}
	@Test void versionOverflowRollsBackAllChildrenAndApprovalFacts() throws Exception {
		create(); jdbc.update("UPDATE routines SET version=? WHERE id=?", Long.MAX_VALUE, routine); var before = snapshot();
		var input = updateBody(Long.MAX_VALUE); input.put("weightApprovals", List.of(approval(first, "65")));
		error(put(input), 409, "WRITE_CONFLICT"); assertThat(snapshot()).isEqualTo(before);
	}
	@Test void simultaneousPostWithSameFixedIdsCreatesOneAggregateOnly() throws Exception {
		var barrier = new CyclicBarrier(2);
		WorkoutReadFixtures.observer = event -> {
			if (!event.before() && event.sql().equals("SELECT EXISTS(SELECT 1 FROM routines WHERE id=?)")) {
				try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception failure) { throw new IllegalStateException(failure); }
			}
		};
		try (var executor = Executors.newFixedThreadPool(2)) {
			var one = executor.submit(this::create); var two = executor.submit(this::create);
			assertThat(List.of(one.get(15, TimeUnit.SECONDS).statusCode(), two.get(15, TimeUnit.SECONDS).statusCode()).stream().sorted().toList()).containsExactly(201, 409);
		} finally { WorkoutReadFixtures.observer = null; }
		assertThat(jdbc.queryForObject("SELECT count(*) FROM routines WHERE id=?", Integer.class, routine)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_weight_placements WHERE routine_id=?", Integer.class, routine)).isEqualTo(2);
		assertThat(events()).isEmpty();
	}
	@Test void readUsesOneSnapshotEvenWhenAnotherTransactionChangesRootAndChildrenAfterOwnerRead() throws Exception {
		create(); var changed = new AtomicBoolean();
		WorkoutReadFixtures.observer = event -> {
			if (!event.before() && event.sql().equals("SELECT data_generation FROM users WHERE id=?") && changed.compareAndSet(false, true)) {
				var independent = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
				var transaction = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(independent.getDataSource()));
				transaction.executeWithoutResult(status -> {
					independent.update("UPDATE routines SET name='new snapshot',version=version+1 WHERE id=?", routine);
					independent.update("UPDATE routine_weight_placements SET set_count=4 WHERE id=?", first);
				});
			}
		};
		JsonNode result;
		try { result = node(browser.get(path())); } finally { WorkoutReadFixtures.observer = null; }
		assertThat(changed).isTrue(); assertThat(result.get("name").asString()).isEqualTo("  同名 루틴 Ω  ");
		assertThat(result.get("version").asString()).isEqualTo("0");
		assertThat(result.get("weightPlacements").get(0).get("setCount").intValue()).isEqualTo(3);
		result = node(browser.get(path())); assertThat(result.get("name").asString()).isEqualTo("new snapshot");
		assertThat(result.get("weightPlacements").get(0).get("setCount").intValue()).isEqualTo(4);
	}
	@Test void wrongLifecycleModesCrossRoutineIdsAndDirectApprovedWeightCannotMutateLineage() throws Exception {
		create(); var original = routine; routine = UUID.randomUUID(); var foreignRoutine = createBody();
		var foreignPlacement = UUID.randomUUID(); foreignRoutine.put("weightPlacements", List.of(newWeight(foreignPlacement, "1"))); foreignRoutine.put("cardioPlacements", List.of());
		assertThat(browser.send("POST", "/api/v1/routines", json(foreignRoutine), csrf).statusCode()).isEqualTo(201); routine = original;
		var input = updateBody(0); input.put("weightPlacements", List.of(weight(foreignPlacement, "EXISTING", 3)));
		error(put(input), 404, "PLACEMENT_NOT_FOUND");
		input = updateBody(0); var direct = weight(first, "EXISTING", 3); direct.put("approvedWeightKg", "65"); input.put("weightPlacements", List.of(direct));
		error(put(input), 400, "INVALID_REQUEST");
		input = updateBody(0); input.put("weightPlacements", List.of(weight(second, "EXISTING", 3))); put(input);
		input = updateBody(1); error(put(input), 409, "PLACEMENT_STATE_CONFLICT");
		var reused = newWeight(first, "65"); reused.put("mode", "NEW"); input.put("weightPlacements", List.of(reused)); error(put(input), 409, "PLACEMENT_ID_CONFLICT");
		assertThat(weightRow(first).get("approved_weight").toString()).isEqualTo("60.000000"); assertThat(events()).isEmpty();
	}
	@Test void equipmentChangesKeepPlacementAndWeightsAndOnlyActualChangesAdvanceRoutineVersion() throws Exception {
		create(); var input=updateBody(0); input.put("weightApprovals",List.of(approval(first,"65"))); put(input);
		UUID manufacturer=CatalogFixtures.manufacturer(jdbc);var facts=events();
		var weight=weight(first,"EXISTING",3);weight.put("manufacturerId",manufacturer);weight.put("loadType","PLATE");
		input=updateBody(1);input.put("weightPlacements",List.of(weight,weight(second,"EXISTING",3)));
		var updated=node(put(input));assertThat(updated.get("version").asString()).isEqualTo("2");
		var row=weightRow(first);assertThat(row).containsEntry("id",first).containsEntry("standard_exercise_id",exercise).containsEntry("manufacturer_id",manufacturer).containsEntry("load_type","PLATE");
		assertThat(row.get("initial_weight").toString()).isEqualTo("60.000000");assertThat(row.get("approved_weight").toString()).isEqualTo("65.000000");assertThat(events()).isEqualTo(facts);
		input.put("expectedVersion","2");var before=snapshot();assertThat(node(put(input)).get("version").asString()).isEqualTo("2");assertThat(snapshot()).isEqualTo(before);
		jdbc.update("UPDATE manufacturer SET is_active=false WHERE id=?",manufacturer);
		assertThat(node(put(input)).get("version").asString()).isEqualTo("2");
		weight.put("manufacturerId",null);weight.put("loadType","BODYWEIGHT");
		assertThat(node(put(input)).get("version").asString()).isEqualTo("3");assertThat(events()).isEqualTo(facts);
		assertThat(weightRow(first).get("approved_weight").toString()).isEqualTo("65.000000");
	}
	@Test void personalDefinitionCannotIdentifyNewRoutineAndInvalidEquipmentRollsBackWholeEdit() throws Exception {
		create();var before=snapshot();var input=updateBody(0);var weight=weight(first,"EXISTING",3);weight.put("manufacturerId",UUID.randomUUID());
		input.put("name","must roll back");input.put("weightPlacements",List.of(weight));error(put(input),400,"INVALID_REQUEST");assertThat(snapshot()).isEqualTo(before);
		UUID inactive=CatalogFixtures.manufacturer(jdbc);jdbc.update("UPDATE manufacturer SET is_active=false WHERE id=?",inactive);weight.put("manufacturerId",inactive);
		error(put(input),400,"INVALID_REQUEST");assertThat(snapshot()).isEqualTo(before);
		CatalogFixtures.personalExercises(jdbc,owner.id());UUID personal=jdbc.queryForObject("SELECT id FROM exercise_definitions WHERE user_id=? LIMIT 1",UUID.class,owner.id());
		var fresh=newWeight(UUID.randomUUID(),"1");fresh.put("mode","NEW");fresh.put("standardExerciseId",personal);input.put("weightPlacements",List.of(fresh));
		error(put(input),404,"EXERCISE_NOT_FOUND");assertThat(snapshot()).isEqualTo(before);
	}
	@Test void sharedStandardExerciseDoesNotShareRoutineOwnershipOrApprovedWeights() throws Exception {
		create();String email=UUID.randomUUID()+"@example.test";UUID otherRoutine=UUID.randomUUID(),otherPlacement=UUID.randomUUID();
		try(var other=new Browser()) {
			other.login(email);var body=createBody();body.put("id",otherRoutine);body.put("weightPlacements",List.of(newWeight(otherPlacement,"10")));body.put("cardioPlacements",List.of());
			var result=node(other.send("POST","/api/v1/routines",json(body),other.csrf()));
			assertThat(result.get("weightPlacements").get(0).get("standardExerciseId").asString()).isEqualTo(exercise.toString());
			error(other.get(path()),404,"ROUTINE_NOT_FOUND");
		}
		var input=updateBody(0);input.put("weightApprovals",List.of(approval(first,"65")));put(input);
		assertThat(weightRow(otherPlacement).get("approved_weight").toString()).isEqualTo("10.000000");
		assertThat(jdbc.queryForObject("SELECT version FROM routines WHERE id=?",Long.class,otherRoutine)).isZero();
	}
	@Test void approvalFactsKeepNamesAndValuesAfterLaterRoutineAndDefinitionEdits() throws Exception {
		create(); var oldName = jdbc.queryForObject("SELECT name FROM standard_exercise WHERE id=?", String.class, exercise);
		var input = updateBody(0); input.put("weightApprovals", List.of(approval(first, "65"))); put(input); var facts = events();
		assertThat(facts.getFirst()).contains(oldName, "ROUTINE_EDITOR");
		jdbc.update("UPDATE standard_exercise SET name='later name' WHERE id=?", exercise);
		input = updateBody(1); input.put("name", "later routine"); put(input);
		assertThat(events()).isEqualTo(facts);
		assertThat(weightRow(first).get("initial_weight").toString()).isEqualTo("60.000000");
	}

	Map<String, Object> createBody() { return new LinkedHashMap<>(Map.of("id", routine, "dataGeneration", "0", "name", "  同名 루틴 Ω  ", "weightPlacements", List.of(newWeight(first, "60"), newWeight(second, "0")), "cardioPlacements", List.of(cardio(cardio, null), cardio(otherCardio, null)))); }
	Map<String, Object> updateBody(long version) { return new LinkedHashMap<>(Map.of("dataGeneration", "0", "expectedVersion", Long.toString(version), "name", "  同名 루틴 Ω  ", "weightPlacements", List.of(weight(first, "EXISTING", 3), weight(second, "EXISTING", 3)), "cardioPlacements", List.of(cardio(cardio, "EXISTING"), cardio(otherCardio, "EXISTING")), "weightApprovals", List.of())); }
	Map<String, Object> updateBodyWithCount(long version, int count) { var value = updateBody(version); value.put("weightPlacements", List.of(weight(first, "EXISTING", count), weight(second, "EXISTING", 3))); return value; }
	Map<String, Object> newWeight(UUID id, String initial) { return equipment(new LinkedHashMap<>(Map.of("id", id, "standardExerciseId", exercise, "setCount", 3, "minReps", 6, "maxReps", 10, "initialWeightKg", initial))); }
	static Map<String, Object> weight(UUID id, String mode, int count) { return equipment(new LinkedHashMap<>(Map.of("mode", mode, "id", id, "setCount", count, "minReps", 6, "maxReps", 10))); }
	static Map<String,Object> equipment(Map<String,Object> value) { value.put("manufacturerId", null); value.put("loadType", null); return value; }
	static Map<String, Object> cardio(UUID id, String mode) {
		var plan = new LinkedHashMap<String, Object>(); plan.put("kind", "TREADMILL"); plan.put("name", "러닝머신"); plan.put("targetSeconds", 600);
		plan.put("targetDistanceKm", "0.001"); plan.put("intensity", Map.of("mode", "SPEED", "value", "6.5")); plan.put("inclinePercent", "0"); plan.put("resistance", null);
		var result = new LinkedHashMap<String, Object>(); result.put("id", id); result.put("plan", plan); if (mode != null) result.put("mode", mode); return result;
	}
	static Map<String, Object> approval(UUID id, String weight) { return Map.of("placementId", id, "nextApprovedWeightKg", weight, "occurredAt", "2026-10-31T10:00:00.123Z"); }
	String path() { return "/api/v1/routines/" + routine; }
	HttpResponse<String> create() throws Exception { return browser.send("POST", "/api/v1/routines", json(createBody()), csrf); }
	HttpResponse<String> put(Map<String, Object> body) throws Exception { return browser.send("PUT", path(), json(body), csrf); }
	static String json(Object value) { return JSON.writeValueAsString(value); }
	static JsonNode node(HttpResponse<String> response) { headers(response); assertThat(response.statusCode()).isIn(200, 201); return JSON.readTree(response.body()); }
	static void headers(HttpResponse<String> response) { WorkoutReadIntegrationTests.headers(response); }
	static void error(HttpResponse<String> response, int status, String code) { assertThat(response.statusCode()).isEqualTo(status); headers(response); assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree(json(Map.of("code", code)))); }
	Map<String, Object> weightRow(UUID id) { return jdbc.queryForMap("SELECT * FROM routine_weight_placements WHERE id=?", id); }
	List<String> events() { return jdbc.queryForList("SELECT to_jsonb(e)::text FROM progress_change_events e WHERE routine_id=? ORDER BY id", String.class, routine); }
	Map<String, List<String>> snapshot() {
		var result = new LinkedHashMap<String, List<String>>();
		for (String table : List.of("routines", "routine_weight_placements", "routine_cardio_placements", "progress_change_events")) result.put(table, jdbc.queryForList("SELECT to_jsonb(t)::text FROM " + table + " t WHERE user_id=? ORDER BY id", String.class, owner.id()));
		return result;
	}
	static boolean domainDml(String sql) { String value = sql.toLowerCase(Locale.ROOT); return (value.startsWith("update") || value.startsWith("insert") || value.startsWith("delete")) && (value.contains("routines") || value.contains("routine_weight_placements") || value.contains("routine_cardio_placements") || value.contains("progress_change_events")); }

	static final class Browser implements AutoCloseable {
		final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
		void login(String email) throws Exception {
			String token = csrf(); var issued = send("POST", "/api/v1/auth/email-otp/request", json(Map.of("email", email)), token);
			assertThat(issued.statusCode()).isEqualTo(202);
			var message = app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString()));
			assertThat(send("POST", "/api/v1/auth/email-otp/verify", json(Map.of("email", email, "challengeId", message.issuanceId(), "code", message.code())), token).statusCode()).isEqualTo(204);
		}
		String csrf() throws Exception { return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString(); }
		HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
		HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
			var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort() + path)).timeout(Duration.ofSeconds(20));
			if (token != null) request.header("X-CSRF-TOKEN", token); if (body != null) request.header("Content-Type", "application/json");
			return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		}
		@Override public void close() { client.close(); }
	}
}
