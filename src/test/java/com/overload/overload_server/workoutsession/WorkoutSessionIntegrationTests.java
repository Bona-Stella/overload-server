package com.overload.overload_server.workoutsession;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;

@Tag("postgres")
@Testcontainers
class WorkoutSessionIntegrationTests {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String BASE = "/api/v1/workout-sessions";
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_session_isolated_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext app;
    static JdbcTemplate jdbc;
    Account owner; Browser browser; String csrf, email;
    UUID routine, exercise, placement, cardio, session, device;

    @BeforeAll static void startApp() {
        assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432, 5433);
        assertThat(POSTGRES.getDatabaseName()).isEqualTo("overload_session_isolated_test");
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
        email = UUID.randomUUID() + "@example.test";
        owner = app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email(email), null);
        exercise = CatalogFixtures.exercise(jdbc);
        routine = UUID.randomUUID(); placement = UUID.randomUUID(); cardio = UUID.randomUUID(); session = UUID.randomUUID(); device = UUID.randomUUID();
        browser = new Browser(); browser.login(email); csrf = browser.csrf();
        assertThat(browser.send("POST", "/api/v1/routines", json(routineBody()), csrf).statusCode()).isEqualTo(201);
    }
    @AfterEach void cleanup() { WorkoutReadFixtures.observer = null; if (browser != null) browser.close(); }
    @AfterAll static void stop() { if (app != null) app.close(); }

    @Test void startCopiesImmutablePlanDistinctIdsAndOriginalClientDate() throws Exception {
        assertThat(node(browser.get(BASE + "/active")).get("session").isNull()).isTrue();
        var response = start(startBody()); assertThat(response.statusCode()).isEqualTo(201); headers(response);
        assertThat(response.headers().firstValue("Location").orElseThrow()).isEqualTo(path());
        var result = node(response); var plan = result.get("initialPlan");
        assertThat(result.get("startedAt").asString()).isEqualTo("2026-10-31T16:00:00.123Z");
        assertThat(result.get("workoutDate").asString()).isEqualTo("2026-11-01");
        assertThat(result.get("writerEpoch").asString()).isEqualTo("0");
        assertThat(result.get("localRevision").asString()).isEqualTo("0");
        var weight = plan.get("weightExercises").get(0);
        assertThat(weight.get("sessionExerciseId").asString()).isNotEqualTo(placement.toString()).isNotEqualTo(exercise.toString());
        assertThat(weight.get("sourcePlacementId").asString()).isEqualTo(placement.toString());
        assertThat(weight.get("approvedWeightKg").asString()).isEqualTo("60");
        assertThat(plan.get("cardioActivities").get(0).get("sessionActivityId").asString()).isNotEqualTo(cardio.toString());
        assertThat(plan.get("cardioActivities").get(0).get("plan").get("targetDistanceKm").isNull()).isTrue();
        var before = row();
        assertThat(node(browser.get(BASE + "/active")).get("session")).isEqualTo(result);
        assertThat(node(browser.get(path()))).isEqualTo(result);
        assertThat(start(startBody()).statusCode()).isEqualTo(200);
        assertThat(row()).isEqualTo(before);
    }

    @Test void closedListIsOwnerGenerationBoundedStableAndReadOnly() throws Exception {
        assertThat(node(browser.get(BASE+"/closed")).get("sessions").size()).isZero();
        var state=initialState();var first=session;
        assertThat(node(browser.get(BASE+"/closed")).get("sessions").size()).isZero();
        assertThat(finish(1,state).statusCode()).isEqualTo(200);
        var before=row();session=UUID.randomUUID();state=initialState();
        assertThat(finish(1,state).statusCode()).isEqualTo(200);
        assertThat(browser.send("DELETE",path(),json(Map.of("dataGeneration","0","expectedVersion",row().get("version").toString())),csrf).statusCode()).isEqualTo(200);
        var second=row();
        var pageResponse=browser.get(BASE+"/closed?limit=1");headers(pageResponse);var page=node(pageResponse);
        assertThat(page.get("sessions").size()).isEqualTo(1);assertThat(page.get("dataGeneration").asString()).isEqualTo("0");
        var next=page.get("next");assertThat(next.isNull()).isFalse();
        String cursor="?limit=1&beforeClosedAt="+next.get("beforeClosedAt").asString()+"&beforeId="+next.get("beforeId").asString();
        var last=node(browser.get(BASE+"/closed"+cursor));assertThat(last.get("next").isNull()).isTrue();
        var ids=Set.of(page.get("sessions").get(0).get("id").asString(),last.get("sessions").get(0).get("id").asString());
        assertThat(ids).containsExactlyInAnyOrder(first.toString(),session.toString());
        assertThat(node(browser.get(BASE+"/closed?limit=1"))).isEqualTo(page);
        var all=node(browser.get(BASE+"/closed")).get("sessions");
        assertThat(java.util.stream.StreamSupport.stream(all.spliterator(),false).anyMatch(s->s.get("id").asString().equals(session.toString())&&!s.get("deletedAt").isNull())).isTrue();
        assertThat(row()).isEqualTo(second);var latest=session;session=first;assertThat(row()).isEqualTo(before);session=latest;
        try(var other=new Browser()) {other.login(UUID.randomUUID()+"@example.test");assertThat(node(other.get(BASE+"/closed")).get("sessions").size()).isZero();}
        try(var anonymous=new Browser()) {error(anonymous.get(BASE+"/closed"),401,"AUTHENTICATION_REQUIRED");}
        for(String invalid:List.of("?limit=0","?limit=51","?limit=01","?limit=2&limit=3","?beforeId="+session,"?unknown=1"))error(browser.get(BASE+"/closed"+invalid),400,"INVALID_REQUEST");
        error(browser.send("GET",BASE+"/closed","{}",null),400,"INVALID_REQUEST");
        // Disposable fixture reset only: preserve the current-generation FK instead of bypassing it.
        jdbc.update("DELETE FROM record_change_events WHERE session_id IN (SELECT id FROM workout_sessions WHERE user_id=?)",owner.id());
        for(String table:List.of("session_exercises","workout_sessions","weekly_schedule_versions","routine_plan_history","routine_weight_placements","routine_cardio_placements","routines","exercise_definitions","user_workout_settings"))jdbc.update("DELETE FROM "+table+" WHERE user_id=?",owner.id());
        jdbc.update("UPDATE users SET data_generation=1 WHERE id=?",owner.id());
        var reset=node(browser.get(BASE+"/closed"));assertThat(reset.get("dataGeneration").asString()).isEqualTo("1");assertThat(reset.get("sessions").size()).isZero();
    }

    @Test void routineRenameApprovalAndDeletionNeverRewriteStartedPlanOrPreventStartRetry() throws Exception {
        var original = node(start(startBody())).get("initialPlan");
        var update = updateRoutine(); update.put("name", "changed");
        update.put("weightApprovals", List.of(Map.of("placementId", placement, "nextApprovedWeightKg", "65", "occurredAt", "2026-10-31T10:00:00Z")));
        assertThat(browser.send("PUT", routinePath(), json(update), csrf).statusCode()).isEqualTo(200);
        jdbc.update("UPDATE standard_exercise SET name='renamed' WHERE id=?", exercise);
        assertThat(browser.send("DELETE", routinePath(), json(Map.of("dataGeneration", "0", "expectedVersion", "1")), csrf).statusCode()).isEqualTo(204);
        assertThat(node(browser.get(path())).get("initialPlan")).isEqualTo(original);
        assertThat(node(start(startBody())).get("initialPlan")).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM progress_change_events WHERE routine_id=?", Integer.class, routine)).isEqualTo(1);
    }

    @Test void takeoverIsExplicitEpochCheckedRetryableAndNeverExtendsSyncTime() throws Exception {
        node(start(startBody())); var before = row(); UUID next = UUID.randomUUID();
        try (var other = new Browser()) {
            EmailOtpFixtures.CLOCK.advance(Duration.ofSeconds(61));
            other.login(email); String token = other.csrf();
            assertThat(node(other.get(path())).get("editorDeviceId").asString()).isEqualTo(device.toString());
            assertThat(row()).isEqualTo(before);
            var request = takeoverBody(next, "0");
            var taken = node(other.send("POST", path() + "/takeover", json(request), token));
            assertThat(taken.get("writerEpoch").asString()).isEqualTo("1");
            assertThat(taken.get("editorDeviceId").asString()).isEqualTo(next.toString());
            assertThat(taken.get("lastWorkoutSyncedAt").asString()).isEqualTo(node(browser.get(path())).get("lastWorkoutSyncedAt").asString());
            assertThat(row().get("last_workout_synced_at")).isEqualTo(before.get("last_workout_synced_at"));
            var accepted = row();
            assertThat(node(start(startBody())).get("editorDeviceId").asString()).isEqualTo(next.toString());
            assertThat(row()).isEqualTo(accepted);
            assertThat(other.send("POST", path() + "/takeover", json(request), token).statusCode()).isEqualTo(200);
            assertThat(row()).isEqualTo(accepted);
            error(browser.send("POST", path() + "/takeover", json(takeoverBody(device, "0")), csrf), 409, "EDITOR_OWNERSHIP_LOST");
            assertThat(row()).isEqualTo(accepted);
            assertThat(node(browser.send("POST", path() + "/takeover", json(takeoverBody(device, "1")), csrf)).get("writerEpoch").asString()).isEqualTo("2");
            error(other.send("POST", path() + "/takeover", json(request), token), 409, "EDITOR_OWNERSHIP_LOST");
        }
    }

    @Test void ownershipGenerationCsrfAndProtocolBoundaries() throws Exception {
        node(start(startBody())); var before = row();
        try (var anonymous = new Browser()) { error(anonymous.get(path()), 401, "AUTHENTICATION_REQUIRED"); }
        error(browser.send("POST", path() + "/takeover", json(takeoverBody(device, "0")), null), 403, "CSRF_INVALID");
        var stale = takeoverBody(device, "0"); stale.put("dataGeneration", "1");
        error(browser.send("POST", path() + "/takeover", json(stale), csrf), 409, "DATA_GENERATION_MISMATCH");
        try (var foreign = new Browser()) {
            foreign.login(UUID.randomUUID() + "@example.test"); var token = foreign.csrf();
            error(foreign.get(path()), 404, "SESSION_NOT_FOUND");
            error(foreign.send("POST", path() + "/takeover", json(takeoverBody(device, "0")), token), 404, "SESSION_NOT_FOUND");
            error(foreign.send("POST", BASE, json(startBody()), token), 409, "SESSION_ID_CONFLICT");
            var foreignStart = startBody(); foreignStart.put("id", UUID.randomUUID());
            error(foreign.send("POST", BASE, json(foreignStart), token), 404, "ROUTINE_NOT_FOUND");
        }
        error(browser.get(path() + "?editorDeviceId=" + device), 400, "INVALID_REQUEST");
        error(browser.send("GET", path(), "{}", null), 400, "INVALID_REQUEST");
        error(browser.get(BASE + "/bad-id"), 400, "INVALID_REQUEST");
        assertThat(browser.send("PUT", path(), "{}", csrf).statusCode()).isEqualTo(405);
        assertThat(row()).isEqualTo(before);
    }

    @Test void fixedIdCannotChangeStartFactsAndSecondActiveIsRejected() throws Exception {
        node(start(startBody())); var before = row();
        var changed = startBody(); changed.put("editorDeviceId", UUID.randomUUID());
        error(start(changed), 409, "SESSION_ID_CONFLICT");
        changed = startBody(); changed.put("id", UUID.randomUUID());
        error(start(changed), 409, "ACTIVE_SESSION_EXISTS");
        assertThat(row()).isEqualTo(before);
    }

    @Test void databaseProtectsInitialPlanOwnerGenerationAndOneActiveAccount() throws Exception {
        node(start(startBody()));
        assertThatThrownBy(() -> jdbc.update("UPDATE workout_sessions SET initial_plan_snapshot=initial_plan_snapshot || '{\"routineName\":\"tampered\"}'::jsonb WHERE id=?", session)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE workout_sessions SET started_at=started_at+interval '1 second' WHERE id=?", session)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET data_generation=1 WHERE id=?", owner.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO workout_sessions SELECT ?,user_id,data_generation,source_routine_id,status,version,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,writer_epoch,local_revision,initial_plan_snapshot,last_workout_synced_at,created_at,updated_at,applied_plan_snapshot,last_snapshot_hash,last_snapshot_saved_at,ended_at,closed_at,completion_assessment_snapshot,deleted_at FROM workout_sessions WHERE id=?", UUID.randomUUID(), session)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE workout_sessions SET writer_epoch=-1 WHERE id=?", session)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE workout_sessions SET local_revision=-1 WHERE id=?", session)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void concurrentStartsReachTheDatabaseUniqueConstraint() throws Exception {
        var barrier = new CyclicBarrier(2);
        WorkoutReadFixtures.observer = event -> {
            if (event.before() && event.sql().toLowerCase(Locale.ROOT).startsWith("insert into workout_sessions")) await(barrier);
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var second = startBody(); second.put("id", UUID.randomUUID());
            var one = executor.submit(() -> start(startBody())); var two = executor.submit(() -> start(second));
            assertThat(List.of(one.get(15, TimeUnit.SECONDS).statusCode(), two.get(15, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(201, 409);
        } finally { WorkoutReadFixtures.observer = null; }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_sessions WHERE user_id=?", Integer.class, owner.id())).isEqualTo(1);
    }

    @Test void competingTakeoversChangeEpochOnce() throws Exception {
        node(start(startBody())); var barrier = new CyclicBarrier(2);
        WorkoutReadFixtures.observer = event -> {
            String sql = event.sql().toLowerCase(Locale.ROOT);
            if (event.before() && sql.contains("from workout_sessions ") && sql.contains("for ")) await(barrier);
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> browser.send("POST", path() + "/takeover", json(takeoverBody(UUID.randomUUID(), "0")), csrf));
            var two = executor.submit(() -> browser.send("POST", path() + "/takeover", json(takeoverBody(UUID.randomUUID(), "0")), csrf));
            assertThat(List.of(one.get(15, TimeUnit.SECONDS).statusCode(), two.get(15, TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
        } finally { WorkoutReadFixtures.observer = null; }
        assertThat(row().get("writer_epoch")).isEqualTo(1L);
    }

    @ParameterizedTest @ValueSource(strings = {"PUT", "DELETE"})
    void startLocksCurrentRoutineUntilInitialPlanIsCommitted(String method) throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var observed = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> {
            String sql = event.sql().toLowerCase(Locale.ROOT);
            if (!event.before() && sql.contains("from routines ") && sql.contains("for share") && observed.compareAndSet(false, true)) {
                locked.countDown(); await(release);
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var starting = executor.submit(() -> start(startBody()));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var editing = executor.submit(() -> editRoutine(method));
            try { assertLockWait(method.equals("DELETE") ? "users" : "routines"); } finally { release.countDown(); }
            var result = node(starting.get(15, TimeUnit.SECONDS));
            assertThat(editing.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(method.equals("PUT") ? 200 : 204);
            assertThat(result.get("initialPlan").get("routineVersion").asString()).isEqualTo("0");
            assertThat(result.get("initialPlan").get("weightExercises").get(0).get("setCount").asInt()).isEqualTo(3);
        } finally { release.countDown(); WorkoutReadFixtures.observer = null; }
    }

    @ParameterizedTest @ValueSource(strings = {"PUT", "DELETE"})
    void concurrentSourceChangeCannotProduceAMixedInitialPlan(String method) throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var observed = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> {
            String sql = event.sql().toLowerCase(Locale.ROOT);
            if (!event.before() && sql.contains("from routines ") && sql.contains("for no key update") && observed.compareAndSet(false, true)) {
                locked.countDown(); await(release);
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var editing = executor.submit(() -> editRoutine(method));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var starting = executor.submit(() -> start(startBody()));
            try { assertLockWait(method.equals("DELETE") ? "users" : "routines"); } finally { release.countDown(); }
            assertThat(editing.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(method.equals("PUT") ? 200 : 204);
            assertThat(starting.get(15, TimeUnit.SECONDS).statusCode()).isIn(404, 409);
        } finally { release.countDown(); WorkoutReadFixtures.observer = null; }
        if (method.equals("PUT")) {
            var plan = node(start(startBody())).get("initialPlan");
            assertThat(plan.get("routineVersion").asString()).isEqualTo("1");
            assertThat(plan.get("weightExercises").get(0).get("setCount").asInt()).isEqualTo(4);
        } else error(start(startBody()), 404, "ROUTINE_NOT_FOUND");
    }

    @Test void insertFailureRollsBackWithoutChangingSourceOrProvisioningAnything() throws Exception {
        var source = jdbc.queryForMap("SELECT * FROM routines WHERE id=?", routine);
        WorkoutReadFixtures.observer = event -> {
            if (event.before() && event.sql().toLowerCase(Locale.ROOT).startsWith("insert into workout_sessions"))
                throw new org.springframework.dao.DataAccessResourceFailureException("isolated failure");
        };
        try { error(start(startBody()), 503, "WORKOUT_DATA_UNAVAILABLE"); } finally { WorkoutReadFixtures.observer = null; }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_sessions WHERE user_id=?", Integer.class, owner.id())).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM routines WHERE id=?", routine)).isEqualTo(source);
        assertThat(start(startBody()).statusCode()).isEqualTo(201);
    }

    @Test void counterOverflowRollsBackTakeoverAndLargeGenerationIsExact() throws Exception {
        node(start(startBody())); jdbc.update("UPDATE workout_sessions SET writer_epoch=?,version=? WHERE id=?", Long.MAX_VALUE, Long.MAX_VALUE, session);
        var before = row();
        error(browser.send("POST", path() + "/takeover", json(takeoverBody(UUID.randomUUID(), Long.toString(Long.MAX_VALUE))), csrf), 409, "WRITE_CONFLICT");
        assertThat(row()).isEqualTo(before);
        // Isolated reset fixture only, not a product reset endpoint or developer database mutation.
        jdbc.update("DELETE FROM session_exercises WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM workout_sessions WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM weekly_schedule_versions WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM routine_plan_history WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM routine_weight_placements WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM routine_cardio_placements WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM routines WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM exercise_definitions WHERE user_id=?", owner.id());
        jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?", owner.id());
        jdbc.update("UPDATE users SET data_generation=9007199254740993 WHERE id=?", owner.id());
        error(start(startBody()), 409, "DATA_GENERATION_MISMATCH");
        assertThat(node(browser.get(BASE + "/active")).get("dataGeneration").asString()).isEqualTo("9007199254740993");
        error(browser.get(path()), 404, "SESSION_NOT_FOUND");
        String generation = "9007199254740993";
        exercise = UUID.randomUUID(); routine = UUID.randomUUID(); placement = UUID.randomUUID(); cardio = UUID.randomUUID(); session = UUID.randomUUID();
        exercise = CatalogFixtures.exercise(jdbc);
        var source = routineBody(); source.put("dataGeneration", generation);
        assertThat(browser.send("POST", "/api/v1/routines", json(source), csrf).statusCode()).isEqualTo(201);
        var input = startBody(); input.put("dataGeneration", generation);
        assertThat(node(start(input)).get("dataGeneration").asString()).isEqualTo(generation);
        var second = new LinkedHashMap<>(input); second.put("id", UUID.randomUUID());
        error(start(second), 409, "ACTIVE_SESSION_EXISTS");
    }

    @Test void startSharesOneRepeatableReadJpaJdbcTransactionWithoutMaterializingPlannedSets() throws Exception {
        var update = updateRoutine();
        update.put("weightPlacements", List.of(equipment(Map.of("mode", "EXISTING", "id", placement, "setCount", Integer.MAX_VALUE,
                "minReps", Integer.MAX_VALUE, "maxReps", Integer.MAX_VALUE))));
        assertThat(browser.send("PUT", routinePath(), json(update), csrf).statusCode()).isEqualTo(200);
        var backends = new HashSet<Integer>(); var transactions = new HashSet<Long>();
        WorkoutReadFixtures.observer = event -> {
            String sql = event.sql().toLowerCase(Locale.ROOT);
            if (event.before() && (sql.contains("from users") || sql.contains("from routines ") || sql.startsWith("insert into workout_sessions"))) {
                try (var statement = event.connection().createStatement(); var result = statement.executeQuery("SELECT pg_backend_pid(),txid_current(),current_setting('transaction_isolation')")) {
                    result.next(); backends.add(result.getInt(1)); transactions.add(result.getLong(2));
                    assertThat(result.getString(3)).isEqualTo("repeatable read");
                    assertThat(event.connection().isReadOnly()).isFalse();
                } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            }
        };
        JsonNode plan;
        try { plan = node(start(startBody())).get("initialPlan"); } finally { WorkoutReadFixtures.observer = null; }
        assertThat(backends).hasSize(1); assertThat(transactions).hasSize(1);
        assertThat(plan.get("weightExercises").size()).isEqualTo(1);
        assertThat(plan.get("weightExercises").get(0).get("setCount").asInt()).isEqualTo(Integer.MAX_VALUE);
        assertThat(row().get("initial_plan_snapshot").toString().length()).isLessThan(2000);
    }

    @Test void failedTakeoverFlushRollsBackDeviceEpochAndCountersTogether() throws Exception {
        node(start(startBody())); var before = row();
        WorkoutReadFixtures.observer = event -> {
            if (event.before() && event.sql().toLowerCase(Locale.ROOT).startsWith("update workout_sessions"))
                throw new org.springframework.dao.DataAccessResourceFailureException("isolated takeover failure");
        };
        try { error(browser.send("POST", path() + "/takeover", json(takeoverBody(UUID.randomUUID(), "0")), csrf), 503, "WORKOUT_DATA_UNAVAILABLE"); }
        finally { WorkoutReadFixtures.observer = null; }
        assertThat(row()).isEqualTo(before);
    }

    @Test void generationResetWaitsForStartOwnerLockAndOldRequestCannotCrossTheBoundary() throws Exception {
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var observed = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> {
            String sql = event.sql().toLowerCase(Locale.ROOT);
            if (!event.before() && sql.contains("from routines ") && sql.contains("for share") && observed.compareAndSet(false, true)) {
                locked.countDown(); await(release);
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var starting = executor.submit(() -> start(startBody()));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var reset = executor.submit(() -> {
                // Separate test-only reset transaction, entirely inside this disposable database.
                var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var isolated = new JdbcTemplate(source);
                new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.support.JdbcTransactionManager(source))
                        .executeWithoutResult(status -> {
                            isolated.queryForObject("SELECT data_generation FROM users WHERE id=? FOR UPDATE", Long.class, owner.id());
                            isolated.update("DELETE FROM session_exercises WHERE user_id=?", owner.id());
                            for (String table : List.of("workout_sessions", "weekly_schedule_versions", "routine_plan_history", "routine_weight_placements", "routine_cardio_placements", "routines", "exercise_definitions", "user_workout_settings"))
                                isolated.update("DELETE FROM " + table + " WHERE user_id=?", owner.id());
                            isolated.update("UPDATE users SET data_generation=1 WHERE id=?", owner.id());
                        });
            });
            try { assertLockWait("users"); } finally { release.countDown(); }
            assertThat(starting.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            reset.get(15, TimeUnit.SECONDS);
        } finally { release.countDown(); WorkoutReadFixtures.observer = null; }
        error(start(startBody()), 409, "DATA_GENERATION_MISMATCH");
        assertThat(node(browser.get(BASE + "/active")).get("session").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_sessions WHERE user_id=?", Integer.class, owner.id())).isZero();
    }

    @Test void snapshotStoresFactsAndInitialPlanSurvivesCorrectionsAndNoOpRevisions() throws Exception {
        var base = initialState(); var initial = node(browser.get(path())).get("initialPlan");
        var set = set(base, UUID.randomUUID(), 4, "60", 9, new Context("INITIAL_APPROVED", null));
        var result = completedCardio(base, UUID.randomUUID());
        var value = state(base, List.of(set), List.of(result), List.of(), List.of(), List.of());
        EmailOtpFixtures.CLOCK.advance(Duration.ofMinutes(1));
        var ack = node(save(1, value)); assertThat(ack.get("duplicate").asBoolean()).isFalse();
        assertThat(ack.get("acceptedLocalRevision").asString()).isEqualTo("1");
        var saved = row(); assertThat(saved.get("last_workout_synced_at")).isNotEqualTo(saved.get("created_at"));
        EmailOtpFixtures.CLOCK.advance(Duration.ofMinutes(1));
        assertThat(node(save(1, value)).get("duplicate").asBoolean()).isTrue(); assertThat(row()).isEqualTo(saved);
        var reordered = new State(value.sessionExercises().reversed(), value.appliedPlan(), value.performedSets(), value.cardioResults(), value.deletedPerformedSetIds(), value.deletedCardioResultIds(), value.approvalRequests());
        assertThat(node(save(1, reordered)).get("duplicate").asBoolean()).isTrue(); assertThat(row()).isEqualTo(saved);
        assertThat(node(save(2, value)).get("duplicate").asBoolean()).isFalse();
        assertThat(row().get("last_workout_synced_at")).isEqualTo(saved.get("last_workout_synced_at"));
        assertThat(row().get("last_snapshot_saved_at")).isNotEqualTo(saved.get("last_snapshot_saved_at"));
        var detail = node(browser.get(path())); assertThat(detail.get("initialPlan")).isEqualTo(initial);
        assertThat(detail.get("snapshot").get("state").get("performedSets").get(0).get("ordinal").asInt()).isEqualTo(4);
        assertThat(detail.get("snapshot").get("recordedContexts").get(0).get("approvedWeightKgAtRecording").asString()).isEqualTo("60");
        var changed = new SetValue(set.id(), set.sessionExerciseId(), 4, "61.25", 11, set.completedAt(), set.weightContext(),null,null);
        var corrected = new Cardio(result.id(), result.sessionActivityId(), result.status(), result.startedAt(), result.occurredAt(), new CardioValue(60000L, 55000L, true, "0.5", 125));
        node(save(3, state(base, List.of(changed), List.of(corrected), List.of(), List.of(), List.of())));
        assertThat(count("record_change_events")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT before_value->>'weightKg' FROM record_change_events WHERE record_id=?", String.class, set.id())).isEqualTo("60");
        assertThat(node(browser.get(path())).get("initialPlan")).isEqualTo(initial);
    }

    @Test void snapshotMissingFactsIsRejectedAndExplicitDeletionCannotResurrect() throws Exception {
        var base = initialState(); var set = set(base, UUID.randomUUID(), 1, "60", 8, new Context("INITIAL_APPROVED", null));
        var cardioResult = completedCardio(base, UUID.randomUUID());
        var full = state(base, List.of(set), List.of(cardioResult), List.of(), List.of(), List.of()); node(save(1, full));
        var before = row(); error(save(2, base), 422, "SNAPSHOT_INCOMPLETE"); assertThat(row()).isEqualTo(before);
        var removedPlan = new Plan(2, List.of(), base.appliedPlan().cardioActivities());
        var stillPerformed = new State(base.sessionExercises(), removedPlan, List.of(set), List.of(cardioResult), List.of(), List.of(), List.of());
        error(save(2, stillPerformed), 422, "PERFORMED_EXERCISE_IN_USE");
        var deleted = new State(base.sessionExercises(), removedPlan, List.of(), List.of(), List.of(set.id()), List.of(cardioResult.id()), List.of());
        node(save(2, deleted)); assertThat(count("record_change_events")).isEqualTo(2);
        var tombstones = node(browser.get(path())).get("snapshot").get("state");
        assertThat(tombstones.get("deletedPerformedSetIds").get(0).asString()).isEqualTo(set.id().toString());
        error(save(3, full), 422, "RECORD_DELETED");
        var replacement = set(base, UUID.randomUUID(), 1, "60", 10, set.weightContext());
        node(save(3, state(base, List.of(replacement), List.of(), List.of(set.id()), List.of(cardioResult.id()), List.of())));
        assertThat(count("performed_sets")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM performed_sets WHERE session_id=? AND deleted_at IS NULL", Integer.class, session)).isEqualTo(1);
    }

    @Test void snapshotCannotChangeCompletionOrdinalOrApprovalContextAndRejectsClockContradictions() throws Exception {
        var base = initialState(); var set = set(base, UUID.randomUUID(), 1, "60", 8, new Context("INITIAL_APPROVED", null));
        node(save(1, state(base, List.of(set), List.of(), List.of(), List.of(), List.of())));
        for (var changed : List.of(new SetValue(set.id(), set.sessionExerciseId(), 2, "60", 8, set.completedAt(), set.weightContext(),null,null),
                new SetValue(set.id(), set.sessionExerciseId(), 1, "60", 8, set.completedAt().plusSeconds(1), set.weightContext(),null,null),
                new SetValue(set.id(), set.sessionExerciseId(), 1, "60", 8, set.completedAt(), new Context("TODAY_ONLY", null),null,null))) {
            error(save(2, state(base, List.of(changed), List.of(), List.of(), List.of(), List.of())), 422, "IMMUTABLE_RECORD_CONTEXT");
        }
        var early = new SetValue(UUID.randomUUID(), set.sessionExerciseId(), 2, "60", 8, java.time.Instant.parse("2026-10-31T15:59:59Z"), set.weightContext(),null,null);
        error(save(2, state(base, List.of(set, early), List.of(), List.of(), List.of(), List.of())), 422, "CLOCK_CONTEXT_INVALID");
        var c = completedCardio(base, UUID.randomUUID());
        var impossible = new Cardio(c.id(), c.sessionActivityId(), c.status(), c.occurredAt(), c.startedAt(), c.result());
        error(save(2, state(base, List.of(set), List.of(impossible), List.of(), List.of(), List.of())), 422, "CLOCK_CONTEXT_INVALID");
        assertThat(count("performed_sets")).isEqualTo(1); assertThat(count("record_change_events")).isZero();
    }

    @Test void sequentialApprovalsInOneUploadAndAfterLostAckPreserveEveryIntent() throws Exception {
        var base = initialState(); var first = approval(base, 1, null, 0, "60", "62.5");
        var second = approval(base, 2, first.id(), 0, "62.5", "65");
        var set = set(base, UUID.randomUUID(), 1, "65", 10, new Context("APPROVAL_REQUEST", second.id()));
        var one = state(base, List.of(set), List.of(), List.of(), List.of(), List.of(first, second));
        var result = node(save(1, one));
        assertThat(result.get("approvalResults").get(0).get("status").asString()).isEqualTo("APPLIED");
        assertThat(result.get("approvalResults").get(1).get("status").asString()).isEqualTo("APPLIED");
        assertThat(routineVersion()).isEqualTo(1); assertThat(eventCount()).isEqualTo(2);
        assertThat(node(save(1, one)).get("duplicate").asBoolean()).isTrue(); assertThat(eventCount()).isEqualTo(2);
        // The client did not see the ACK: original expected version still follows our own durable edge.
        var third = approval(base, 3, second.id(), 0, "65", "67.5");
        var next = state(base, List.of(set), List.of(), List.of(), List.of(), List.of(first, second, third));
        result = node(save(2, next)); assertThat(result.get("approvalResults").get(2).get("status").asString()).isEqualTo("APPLIED");
        assertThat(routineVersion()).isEqualTo(2); assertThat(eventCount()).isEqualTo(3);
        var detail = node(browser.get(path())).get("snapshot");
        assertThat(detail.get("state").get("approvalRequests").get(0).get("sequence").isString()).isTrue();
        assertThat(detail.get("recordedContexts").get(0).get("approvedWeightKgAtRecording").asString()).isEqualTo("65");
        // Omitting request inputs is not deletion or reapplication.
        node(save(3, state(base, List.of(set), List.of(), List.of(), List.of(), List.of())));
        assertThat(count("session_weight_approval_requests")).isEqualTo(3); assertThat(eventCount()).isEqualTo(3);
        var beforeGrowth=row();
        var growth=node(browser.get("/api/v1/statistics/growth/exercises/"+exercise+"/placements/"+placement+"?period=all&timeZone=UTC"));
        assertThat(growth.get("points").size()).isEqualTo(3);
        for(var point:growth.get("points")){assertThat(point.get("source").asString()).isEqualTo("WORKOUT");assertThat(point.get("sessionId").asString()).isEqualTo(session.toString());}
        assertThat(row()).isEqualTo(beforeGrowth);assertThat(eventCount()).isEqualTo(3);
    }

    @Test void roundTripApprovalsStillIncrementParentAndNoOpApprovalDoesNotCreateAnEvent() throws Exception {
        var base = initialState(); var first = approval(base, 1, null, 0, "60", "62.5");
        var second = approval(base, 2, first.id(), 0, "62.5", "60");
        node(save(1, state(base, List.of(), List.of(), List.of(), List.of(), List.of(first, second))));
        assertThat(routineVersion()).isEqualTo(1); assertThat(eventCount()).isEqualTo(2);
        var syncedAt = row().get("last_workout_synced_at"); EmailOtpFixtures.CLOCK.advance(Duration.ofMinutes(1));
        var third = approval(base, 3, second.id(), 1, "60", "60");
        node(save(2, state(base, List.of(), List.of(), List.of(), List.of(), List.of(third))));
        assertThat(routineVersion()).isEqualTo(1); assertThat(eventCount()).isEqualTo(2);
        assertThat(row().get("last_workout_synced_at")).isEqualTo(syncedAt);
    }

    @Test void externalRoutineChangeLeavesApprovalsPendingButCommitsFactsAndKeepIsQueryableAfterLostResponse() throws Exception {
        var base = initialState(); assertThat(editRoutine("PUT").statusCode()).isEqualTo(200);
        var first = approval(base, 1, null, 0, "60", "62.5"); var second = approval(base, 2, first.id(), 0, "62.5", "65");
        var set = set(base, UUID.randomUUID(), 1, "65", 8, new Context("APPROVAL_REQUEST", second.id()));
        var result = node(save(1, state(base, List.of(set), List.of(completedCardio(base, UUID.randomUUID())), List.of(), List.of(), List.of(first, second))));
        assertThat(result.get("approvalResults").get(0).get("reason").asString()).isEqualTo("ROUTINE_CHANGED");
        assertThat(result.get("approvalResults").get(1).get("status").asString()).isEqualTo("PENDING");
        assertThat(count("performed_sets")).isEqualTo(1); assertThat(count("cardio_results")).isEqualTo(1); assertThat(eventCount()).isZero();
        var before = row().get("last_workout_synced_at");
        var resolution = resolutionBody(base, List.of(first.id(), second.id()), "KEEP_CURRENT", null);
        node(resolve(resolution)); var terminal = row();
        var detail = node(browser.get(path())).get("snapshot");
        assertThat(detail.get("approvalResults").get(0).get("status").asString()).isEqualTo("NOT_APPLIED");
        assertThat(detail.get("approvalResults").get(0).get("reason").asString()).isEqualTo("KEPT_CURRENT");
        assertThat(detail.get("approvalResults").get(0).get("resolvedAt").asString()).isEqualTo("2026-10-31T16:05:00Z");
        node(resolve(resolution)); assertThat(row()).isEqualTo(terminal);
        assertThat(row().get("last_workout_synced_at")).isEqualTo(before);
        assertThat(detail.get("recordedContexts").get(0).get("approvalStateAtRecording").asString()).isEqualTo("PENDING");
        assertThat(detail.get("recordedContexts").get(0).get("approvedWeightKgAtRecording").isNull()).isTrue();
    }

    @Test void reapprovalIsANewCurrentEventAndNeverRetroactivelyRewritesPerformance() throws Exception {
        var base = initialState(); assertThat(editRoutine("PUT").statusCode()).isEqualTo(200);
        var first = approval(base, 1, null, 0, "60", "65");
        var set = set(base, UUID.randomUUID(), 1, "65", 8, new Context("APPROVAL_REQUEST", first.id()));
        node(save(1, state(base, List.of(set), List.of(), List.of(), List.of(), List.of(first))));
        UUID newId = UUID.randomUUID(); var next = Map.<String,Object>of("id", newId, "sequence", "2", "expectedRoutineVersion", "1", "previousApprovedWeightKg", "60", "nextApprovedWeightKg", "65");
        var request = resolutionBody(base, List.of(first.id()), "REAPPROVE", next); node(resolve(request));
        var terminal = row(); node(resolve(request)); assertThat(row()).isEqualTo(terminal);
        assertThat(eventCount()).isEqualTo(1); assertThat(routineVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM progress_change_events WHERE approval_request_id=?", java.sql.Timestamp.class, newId).toInstant()).isEqualTo(java.time.Instant.parse("2026-10-31T16:05:00Z"));
        var detail = node(browser.get(path())).get("snapshot");
        assertThat(detail.get("approvalResults").get(0).get("resolvedByRequestId").asString()).isEqualTo(newId.toString());
        assertThat(detail.get("recordedContexts").get(0).get("approvalStateAtRecording").asString()).isEqualTo("PENDING");
        assertThat(detail.get("state").get("performedSets").get(0).get("weightContext").get("requestId").asString()).isEqualTo(first.id().toString());
    }

    @Test void newSessionExerciseWithoutRoutinePlacementPreservesPerformanceAndNeverChangesRoutineApproval() throws Exception {
        var base = initialState(); UUID added = UUID.randomUUID();
        var items = new ArrayList<>(base.sessionExercises()); items.add(new Exercise(added, "WEIGHT", exercise));
        var weights = new ArrayList<>(base.appliedPlan().weightExercises()); weights.add(new WeightPlan(added, 1, 6, 10,null,null));
        base = new State(items, new Plan(2, weights, base.appliedPlan().cardioActivities()), List.of(), List.of(), List.of(), List.of(), List.of());
        var request = new Approval(UUID.randomUUID(), added, 1, null, 0, "60", "65", java.time.Instant.parse("2026-10-31T16:01:00Z"));
        var set = new SetValue(UUID.randomUUID(), added, 1, "65", 8, java.time.Instant.parse("2026-10-31T16:02:00Z"), new Context("APPROVAL_REQUEST", request.id()),null,null);
        var result = node(save(1, state(base, List.of(set), List.of(), List.of(), List.of(), List.of(request))));
        assertThat(result.get("approvalResults").get(0).get("reason").asString()).isEqualTo("NO_SOURCE_PLACEMENT");
        assertThat(count("performed_sets")).isEqualTo(1); assertThat(routineVersion()).isZero(); assertThat(eventCount()).isZero();
    }

    @Test void deletedSourceStillAllowsPerformanceWhileApprovalRemainsPending() throws Exception {
        var base = initialState(); assertThat(editRoutine("DELETE").statusCode()).isEqualTo(204);
        var request = approval(base, 1, null, 0, "60", "65");
        var set = set(base, UUID.randomUUID(), 1, "65", 8, new Context("APPROVAL_REQUEST", request.id()));
        var result = node(save(1, state(base, List.of(set), List.of(), List.of(), List.of(), List.of(request))));
        assertThat(result.get("approvalResults").get(0).get("reason").asString()).isEqualTo("SOURCE_REMOVED");
        assertThat(count("performed_sets")).isEqualTo(1); assertThat(eventCount()).isZero();
        var next = Map.<String,Object>of("id", UUID.randomUUID(), "sequence", "2", "expectedRoutineVersion", "1", "previousApprovedWeightKg", "60", "nextApprovedWeightKg", "65");
        error(resolve(resolutionBody(base, List.of(request.id()), "REAPPROVE", next)), 409, "SOURCE_NOT_APPROVABLE");
        node(resolve(resolutionBody(base, List.of(request.id()), "KEEP_CURRENT", null)));
    }

    @Test void revisionReversalDifferentPayloadAndApprovalIdentityAreRejectedAtomically() throws Exception {
        var base = initialState(); var request = approval(base, 1, null, 0, "60", "65");
        var value = state(base, List.of(), List.of(), List.of(), List.of(), List.of(request)); node(save(2, value)); var saved = row();
        error(save(1, value), 409, "REVISION_STALE"); error(save(2, base), 409, "REVISION_PAYLOAD_MISMATCH");
        var changed = new Approval(request.id(), request.sessionExerciseId(), 1, null, 0, "60", "70", request.occurredAt());
        error(save(3, state(base, List.of(), List.of(), List.of(), List.of(), List.of(changed))), 409, "APPROVAL_REQUEST_ID_CONFLICT");
        var gap = approval(base, 3, request.id(), 1, "65", "70");
        error(save(3, state(base, List.of(), List.of(), List.of(), List.of(), List.of(gap))), 422, "APPROVAL_SEQUENCE_GAP");
        assertThat(row()).isEqualTo(saved); assertThat(eventCount()).isEqualTo(1);
    }

    @Test void snapshotEnforcesAuthenticationCsrfOwnershipGenerationAndCurrentEditor() throws Exception {
        var base = initialState(); var body = saveBody(1, base); var before = row();
        try (var anonymous = new Browser()) { error(anonymous.send("PUT", path()+"/snapshot", json(body), null), 401, "AUTHENTICATION_REQUIRED"); }
        error(browser.send("PUT", path()+"/snapshot", json(body), null), 403, "CSRF_INVALID");
        body.put("dataGeneration", "1"); error(browser.send("PUT", path()+"/snapshot", json(body), csrf), 409, "DATA_GENERATION_MISMATCH"); body.put("dataGeneration", "0");
        body.put("editorDeviceId", UUID.randomUUID()); error(browser.send("PUT", path()+"/snapshot", json(body), csrf), 409, "EDITOR_OWNERSHIP_LOST");
        try (var foreign = new Browser()) {
            foreign.login(UUID.randomUUID()+"@example.test");
            error(foreign.send("PUT", path()+"/snapshot", json(saveBody(1, base)), foreign.csrf()), 404, "SESSION_NOT_FOUND");
        }
        assertThat(row()).isEqualTo(before);
        var taken = node(browser.send("POST", path()+"/takeover", json(takeoverBody(UUID.randomUUID(), "0")), csrf));
        error(save(1, base), 409, "EDITOR_OWNERSHIP_LOST");
        assertThat(taken.get("snapshot").get("state").get("appliedPlan")).isEqualTo(JSON.valueToTree(base.appliedPlan()));
    }

    @ParameterizedTest @ValueSource(strings={"insert into progress_change_events", "insert into performed_sets", "insert into record_change_events", "update workout_sessions"})
    void actualPostgresFailureRollsBackApprovalPerformanceAuditAndRevisionTogether(String failingSql) throws Exception {
        var base = initialState();
        var existing = set(base, UUID.randomUUID(), 1, "60", 8, new Context("INITIAL_APPROVED", null));
        node(save(1, state(base, List.of(existing), List.of(), List.of(), List.of(), List.of())));
        var corrected = new SetValue(existing.id(), existing.sessionExerciseId(), 1, "61", 10, existing.completedAt(), existing.weightContext(),null,null);
        var original = row(); long version = routineVersion();
        var request = approval(base, 1, null, 0, "60", "65");
        var set = set(base, UUID.randomUUID(), 2, "65", 8, new Context("APPROVAL_REQUEST", request.id()));
        var value = state(base, List.of(corrected, set), List.of(completedCardio(base, UUID.randomUUID())), List.of(), List.of(), List.of(request));
        var once = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> {
            if (event.before() && event.sql().toLowerCase(Locale.ROOT).startsWith(failingSql) && once.compareAndSet(false, true)) {
                try (var statement = event.connection().createStatement()) { statement.execute("SELECT 1/0"); }
                catch (java.sql.SQLException e) { throw new org.springframework.dao.DataAccessResourceFailureException("isolated SQL failure", e); }
            }
        };
        try { error(save(2, value), 503, "WORKOUT_DATA_UNAVAILABLE"); }
        finally { WorkoutReadFixtures.observer = null; }
        assertThat(row()).isEqualTo(original); assertThat(routineVersion()).isEqualTo(version);
        assertThat(count("performed_sets")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT weight FROM performed_sets WHERE id=?", java.math.BigDecimal.class, existing.id())).isEqualByComparingTo("60");
        assertThat(jdbc.queryForObject("SELECT reps FROM performed_sets WHERE id=?", Integer.class, existing.id())).isEqualTo(8);
        for (String table : List.of("cardio_results", "session_weight_approval_requests", "record_change_events")) assertThat(count(table)).isZero();
        assertThat(eventCount()).isZero();
        node(save(2, value)); assertThat(count("record_change_events")).isEqualTo(1);
    }

    @Test void concurrentDifferentSnapshotsAtSameRevisionSerializeAndOnlyOneWins() throws Exception {
        var base = initialState(); var barrier = new CyclicBarrier(2);
        WorkoutReadFixtures.observer = event -> { if(event.before() && event.sql().contains("from workout_sessions ") && event.sql().contains("for ")) await(barrier); };
        var first = state(base, List.of(set(base, UUID.randomUUID(), 1, "60", 8, new Context("INITIAL_APPROVED", null))), List.of(), List.of(), List.of(), List.of());
        var second = state(base, List.of(set(base, UUID.randomUUID(), 1, "60", 9, new Context("INITIAL_APPROVED", null))), List.of(), List.of(), List.of(), List.of());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> save(1, first)); var b = executor.submit(() -> save(1, second));
            assertThat(List.of(a.get(15, TimeUnit.SECONDS).statusCode(), b.get(15, TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200, 409);
        } finally { WorkoutReadFixtures.observer = null; }
        assertThat(count("performed_sets")).isEqualTo(1); assertThat(row().get("local_revision")).isEqualTo(1L);
    }

    @Test void takeoverWaitsForSnapshotAndOldEpochCannotOverwriteIt() throws Exception {
        var base = initialState(); var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var once = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> { if (!event.before() && event.sql().contains("from workout_sessions ") && event.sql().contains("for ") && once.compareAndSet(false,true)) { locked.countDown(); await(release); } };
        var value = state(base, List.of(set(base, UUID.randomUUID(), 1, "60", 8, new Context("INITIAL_APPROVED", null))), List.of(), List.of(), List.of(), List.of());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var saving = executor.submit(() -> save(1, value)); assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var takeover = executor.submit(() -> browser.send("POST", path()+"/takeover", json(takeoverBody(UUID.randomUUID(), "0")), csrf));
            try { assertLockWait("workout_sessions"); } finally { release.countDown(); }
            assertThat(saving.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200); assertThat(takeover.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally { release.countDown(); WorkoutReadFixtures.observer = null; }
        error(save(2, base), 409, "EDITOR_OWNERSHIP_LOST"); assertThat(count("performed_sets")).isEqualTo(1);
        assertThat(row().get("local_revision")).isEqualTo(0L); assertThat(row().get("last_snapshot_hash")).isNull();
    }

    @Test void approvalLocksRoutineBeforeSessionAndConcurrentEditorSeesVersionConflict() throws Exception {
        var base = initialState(); var locked = new CountDownLatch(1); var release = new CountDownLatch(1); var once = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> { if(!event.before() && event.sql().contains("from routines ") && event.sql().contains("for no key update") && once.compareAndSet(false,true)) { locked.countDown(); await(release); } };
        var request = approval(base, 1, null, 0, "60", "65");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var saving = executor.submit(() -> save(1, state(base,List.of(),List.of(),List.of(),List.of(),List.of(request)))); assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var editing = executor.submit(() -> editRoutine("PUT"));
            try { assertLockWait("routines"); } finally { release.countDown(); }
            assertThat(saving.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(200); error(editing.get(15,TimeUnit.SECONDS),409,"ROUTINE_VERSION_CONFLICT");
        } finally { release.countDown(); WorkoutReadFixtures.observer=null; }
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test void identicalConcurrentSnapshotsReturnOneCommitAndOneDuplicateAck() throws Exception {
        var base=initialState();var barrier=new CyclicBarrier(2);
        var value=state(base,List.of(set(base,UUID.randomUUID(),1,"60",8,new Context("INITIAL_APPROVED",null))),List.of(),List.of(),List.of(),List.of());
        WorkoutReadFixtures.observer=event->{if(event.before()&&event.sql().contains("from workout_sessions ")&&event.sql().contains("for "))await(barrier);};
        try(var executor=Executors.newFixedThreadPool(2)){
            var one=executor.submit(()->save(1,value));var two=executor.submit(()->save(1,value));
            assertThat(List.of(node(one.get(15,TimeUnit.SECONDS)).get("duplicate").asBoolean(),node(two.get(15,TimeUnit.SECONDS)).get("duplicate").asBoolean())).containsExactlyInAnyOrder(false,true);
        }finally{WorkoutReadFixtures.observer=null;}
        assertThat(row().get("version")).isEqualTo(1L);assertThat(count("performed_sets")).isEqualTo(1);
    }

    @Test void outsideApprovalAfterOwnLostAckCreatesPendingWithoutErasingAnyEventOrFact() throws Exception {
        var base=initialState();var first=approval(base,1,null,0,"60","62.5");
        node(save(1,state(base,List.of(),List.of(),List.of(),List.of(),List.of(first))));
        var external=updateRoutine();external.put("expectedVersion","1");
        external.put("weightApprovals",List.of(Map.of("placementId",placement,"nextApprovedWeightKg","70","occurredAt","2026-10-31T16:01:01Z")));
        node(browser.send("PUT",routinePath(),json(external),csrf));
        var second=approval(base,2,first.id(),0,"62.5","65");var set=set(base,UUID.randomUUID(),1,"65",9,new Context("APPROVAL_REQUEST",second.id()));
        var ack=node(save(2,state(base,List.of(set),List.of(),List.of(),List.of(),List.of(first,second))));
        assertThat(ack.get("approvalResults").get(1).get("status").asString()).isEqualTo("PENDING");
        assertThat(count("performed_sets")).isEqualTo(1);assertThat(eventCount()).isEqualTo(2);assertThat(routineVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT approved_weight FROM routine_weight_placements WHERE id=?",java.math.BigDecimal.class,placement)).isEqualByComparingTo("70");
        var before=row();var changed=approval(base,2,first.id(),2,"70","75");
        error(save(3,state(base,List.of(set),List.of(),List.of(),List.of(),List.of(changed))),409,"APPROVAL_SEQUENCE_CONFLICT");assertThat(row()).isEqualTo(before);
    }

    @Test void resolutionValidatesCsrfOwnershipGenerationDeviceAndExactPendingSet() throws Exception {
        var base=initialState();assertThat(editRoutine("PUT").statusCode()).isEqualTo(200);
        var first=approval(base,1,null,0,"60","65");var second=approval(base,2,first.id(),0,"65","67.5");
        node(save(1,state(base,List.of(),List.of(),List.of(),List.of(),List.of(first,second))));
        var body=resolutionBody(base,List.of(first.id(),second.id()),"KEEP_CURRENT",null);var before=row();
        error(browser.send("POST",path()+"/approval-resolutions",json(body),null),403,"CSRF_INVALID");
        try(var foreign=new Browser()){
            foreign.login(UUID.randomUUID()+"@example.test");
            error(foreign.send("POST",path()+"/approval-resolutions",json(body),foreign.csrf()),404,"SESSION_NOT_FOUND");
        }
        body.put("dataGeneration","1");error(resolve(body),409,"DATA_GENERATION_MISMATCH");body.put("dataGeneration","0");
        body.put("editorDeviceId",UUID.randomUUID());error(resolve(body),409,"EDITOR_OWNERSHIP_LOST");body.put("editorDeviceId",device);
        body.put("pendingRequestIds",List.of(first.id()));error(resolve(body),409,"RESOLUTION_CONFLICT");body.put("pendingRequestIds",List.of(first.id(),second.id()));
        body.put("expectedLocalRevision","0");error(resolve(body),409,"RESOLUTION_CONFLICT");assertThat(row()).isEqualTo(before);
        var next=Map.<String,Object>of("id",UUID.randomUUID(),"sequence","3","expectedRoutineVersion","0","previousApprovedWeightKg","60","nextApprovedWeightKg","70");
        error(resolve(resolutionBody(base,List.of(first.id(),second.id()),"REAPPROVE",next)),409,"RESOLUTION_CONFLICT");
        assertThat(row()).isEqualTo(before);assertThat(eventCount()).isZero();
    }

    @Test void deletionAndReplacementInOneSnapshotVacateActiveUniquenessAndAuditOnce() throws Exception {
        var base=initialState();var first=set(base,UUID.randomUUID(),1,"60",8,new Context("INITIAL_APPROVED",null));var oldCardio=completedCardio(base,UUID.randomUUID());
        node(save(1,state(base,List.of(first),List.of(oldCardio),List.of(),List.of(),List.of())));
        var replacement=set(base,UUID.randomUUID(),1,"61",9,new Context("TODAY_ONLY",null));
        var skipped=new Cardio(UUID.randomUUID(),oldCardio.sessionActivityId(),"SKIPPED",null,oldCardio.occurredAt(),null);
        var value=state(base,List.of(replacement),List.of(skipped),List.of(first.id()),List.of(oldCardio.id()),List.of());
        node(save(2,value));assertThat(count("record_change_events")).isEqualTo(2);assertThat(count("performed_sets")).isEqualTo(2);assertThat(count("cardio_results")).isEqualTo(2);
        node(save(3,value));assertThat(count("record_change_events")).isEqualTo(2);
    }

    @Test void ownApprovalSequenceCannotRunBackwardsInDeviceTime() throws Exception {
        var base=initialState();var first=approval(base,1,null,0,"60","65");node(save(1,state(base,List.of(),List.of(),List.of(),List.of(),List.of(first))));
        var next=new Approval(UUID.randomUUID(),first.sessionExerciseId(),2,null,1,"65","70",first.occurredAt().minusMillis(1));
        error(save(2,state(base,List.of(),List.of(),List.of(),List.of(),List.of(next))),422,"CLOCK_CONTEXT_INVALID");
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test void finishCommitsFinalStateAndFirstAssessmentOnceAndSealsBothPlans() throws Exception {
        var base=initialState();var initial=node(browser.get(path())).get("initialPlan");
        var value=confirmed(base,new int[]{10,10,10,1},new Context("INITIAL_APPROVED",null));
        var result=node(finish(1,value));assertThat(result.get("status").asString()).isEqualTo("FINISHED");
        assertThat(result.get("version").asString()).isEqualTo("1");assertThat(result.get("duplicate").asBoolean()).isFalse();
        var assessment=result.get("completionAssessment");var evaluated=assessment.get("exercises").get(0);
        assertThat(evaluated.get("eligible").asBoolean()).isTrue();assertThat(evaluated.get("advice").asString()).isEqualTo("INCREASE");
        assertThat(evaluated.get("evaluatedPerformedSetIds").size()).isEqualTo(3);assertThat(count("performed_sets")).isEqualTo(4);
        var original=row();EmailOtpFixtures.CLOCK.advance(Duration.ofHours(25));
        assertThat(node(finish(1,value)).get("duplicate").asBoolean()).isTrue();assertThat(row()).isEqualTo(original);
        assertThat(node(browser.get(path())).get("initialPlan")).isEqualTo(initial);
        assertThat(node(browser.get(BASE+"/active")).get("session").isNull()).isTrue();
        var changed=finishBody(1,value);changed.put("endedAt","2026-10-31T16:11:00Z");error(sendFinish(changed),409,"FINISH_REQUEST_MISMATCH");
        error(finish(2,value),409,"FINISH_REQUEST_MISMATCH");error(save(2,value),409,"SESSION_NOT_ACTIVE");
        error(browser.send("POST",path()+"/takeover",json(takeoverBody(UUID.randomUUID(),"0")),csrf),409,"SESSION_NOT_ACTIVE");
        for(String assignment:List.of("applied_plan_snapshot='{}'::jsonb","completion_assessment_snapshot='{}'::jsonb","status='ACTIVE'","ended_at=ended_at+interval '1 second'"))
            assertThatThrownBy(()->jdbc.update("UPDATE workout_sessions SET "+assignment+" WHERE id=?",session)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(row()).isEqualTo(original);
        // A future correction's root version must not change the original finish ACK.
        jdbc.update("UPDATE workout_sessions SET version=version+1 WHERE id=?",session);
        assertThat(node(finish(1,value)).get("version").asString()).isEqualTo("1");
        assertThat(node(browser.get(path())).get("completionAssessment")).isEqualTo(assessment);
    }

    @Test void finishRetryPreservesServerTimestampsAcrossPostgresPrecisionRoundTrip() throws Exception {
        EmailOtpFixtures.CLOCK.advance(Duration.ofNanos(123456789));
        var started=node(start(startBody()));
        assertThat(node(browser.get(path())).get("lastWorkoutSyncedAt")).isEqualTo(started.get("lastWorkoutSyncedAt"));
        var base=JSON.treeToValue(started.get("snapshot").get("state"),State.class);
        var value=confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null));
        EmailOtpFixtures.CLOCK.advance(Duration.ofNanos(123456789));
        var first=node(finish(1,value));var retry=node(finish(1,value));
        for(String field:List.of("closedAt","snapshotSavedAt","lastWorkoutSyncedAt","endedAt","completionAssessment"))
            assertThat(retry.get(field)).as(field).isEqualTo(first.get(field));
        assertThat(retry.get("duplicate").asBoolean()).isTrue();
    }

    @Test void finishOfAlreadySyncedStateDoesNotDoubleIncrementOrExtendNoOpSync() throws Exception {
        var base=initialState();var value=confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null));node(save(1,value));
        var saved=row();EmailOtpFixtures.CLOCK.advance(Duration.ofMinutes(2));
        var ack=node(finish(1,value));assertThat(ack.get("version").asString()).isEqualTo("2");
        assertThat(row().get("last_workout_synced_at")).isEqualTo(saved.get("last_workout_synced_at"));
        assertThat(row().get("last_snapshot_saved_at")).isEqualTo(saved.get("last_snapshot_saved_at"));
        assertThat(count("performed_sets")).isEqualTo(3);
    }

    @Test void finishKeepsPendingApprovalAndPartialCardioWithoutCreatingMissingPerformances() throws Exception {
        var base=initialState();assertThat(editRoutine("PUT").statusCode()).isEqualTo(200);
        var request=approval(base,1,null,0,"60","65");
        var one=set(base,UUID.randomUUID(),1,"65",8,new Context("APPROVAL_REQUEST",request.id()));
        var value=state(base,List.of(one),List.of(),List.of(),List.of(),List.of(request));
        var ack=node(finish(1,value));assertThat(ack.get("approvalResults").get(0).get("status").asString()).isEqualTo("PENDING");
        assertThat(count("performed_sets")).isEqualTo(1);assertThat(count("cardio_results")).isZero();assertThat(eventCount()).isZero();
        assertThat(ack.get("completionAssessment").get("overallWeightPlanComplete").asBoolean()).isFalse();
        assertThat(ack.get("completionAssessment").get("exercises").get(0).get("advice").asString()).isEqualTo("UNAVAILABLE");
        assertThat(node(browser.get(path())).get("snapshot").get("recordedContexts").get(0).get("approvalStateAtRecording").asString()).isEqualTo("PENDING");
    }

    @Test void finishApprovesSequentialRequestsAtomicallyAndPreservesEachPerformedContext() throws Exception {
        var base=initialState();var a=approval(base,1,null,0,"60","62.5");var b=approval(base,2,a.id(),0,"62.5","65");
        var value=confirmed(base,new int[]{8,8,8},new Context("APPROVAL_REQUEST",b.id()));
        var sets=value.performedSets().stream().map(s->new SetValue(s.id(),s.sessionExerciseId(),s.ordinal(),"65",s.reps(),s.completedAt(),s.weightContext(),null,null)).toList();
        value=state(base,sets,List.of(),List.of(),List.of(),List.of(a,b));
        var result=node(finish(1,value));assertThat(eventCount()).isEqualTo(2);assertThat(routineVersion()).isEqualTo(1);
        assertThat(result.get("completionAssessment").get("exercises").get(0).get("comparisonContext").get("approvedWeightKg").asString()).isEqualTo("65");
        var old=row().get("completion_assessment_snapshot");
        var update=updateRoutine();update.put("expectedVersion","1");update.put("weightApprovals",List.of(Map.of("placementId",placement,"nextApprovedWeightKg","70","occurredAt","2026-10-31T16:12:00Z")));
        node(browser.send("PUT",routinePath(),json(update),csrf));
        assertThat(row().get("completion_assessment_snapshot")).isEqualTo(old);node(finish(1,value));assertThat(eventCount()).isEqualTo(3);
    }

    @Test void deletedPreApprovalPerformanceDoesNotEraseHistoryOrPermanentlyDisqualifyNewApprovedSets() throws Exception {
        var base=initialState();var old=set(base,UUID.randomUUID(),1,"60",8,new Context("INITIAL_APPROVED",null));
        node(save(1,state(base,List.of(old),List.of(),List.of(),List.of(),List.of())));
        var request=new Approval(UUID.randomUUID(),old.sessionExerciseId(),1,null,0,"60","62.5",java.time.Instant.parse("2026-10-31T16:03:00Z"));
        var sets=new ArrayList<SetValue>();for(int i=1;i<=3;i++)sets.add(new SetValue(UUID.randomUUID(),old.sessionExerciseId(),i,"62.5",8,
                java.time.Instant.parse("2026-10-31T16:04:00Z").plusSeconds(i),new Context("APPROVAL_REQUEST",request.id()),null,null));
        var result=node(finish(2,state(base,sets,List.of(),List.of(old.id()),List.of(),List.of(request))));
        var exerciseAssessment=result.get("completionAssessment").get("exercises").get(0);
        assertThat(exerciseAssessment.get("eligible").asBoolean()).isTrue();
        assertThat(exerciseAssessment.get("comparisonContext").get("approvedWeightKg").asString()).isEqualTo("62.5");
        var original=jdbc.queryForMap("SELECT weight,approved_weight_at_recording,approval_state_at_recording,deleted_at FROM performed_sets WHERE id=?",old.id());
        assertThat((java.math.BigDecimal)original.get("weight")).isEqualByComparingTo("60");
        assertThat((java.math.BigDecimal)original.get("approved_weight_at_recording")).isEqualByComparingTo("60");
        assertThat(original.get("approval_state_at_recording")).isEqualTo("INITIAL_APPROVED");assertThat(original.get("deleted_at")).isNotNull();
        assertThat(count("performed_sets")).isEqualTo(4);assertThat(count("record_change_events")).isEqualTo(1);assertThat(eventCount()).isEqualTo(1);
    }

    @Test void historicalBenchmarkUsesWholeSessionSumThenActualEndedTimeAndUuid() throws Exception {
        var first=initialState();UUID firstId=session;node(finish(1,confirmed(first,new int[]{10,10,10},new Context("INITIAL_APPROVED",null))));
        session=UUID.randomUUID();var second=initialStateAt("2026-10-31T17:00:00Z");
        var body=finishBody(1,confirmed(second,new int[]{15,8,8},new Context("INITIAL_APPROVED",null)));body.put("endedAt","2026-10-31T17:10:00Z");node(sendFinish(body));UUID best=session;
        session=UUID.randomUUID();var current=initialStateAt("2026-10-31T18:00:00Z");
        body=finishBody(1,confirmed(current,new int[]{8,8,8},new Context("INITIAL_APPROVED",null)));body.put("endedAt","2026-10-31T18:10:00Z");
        var result=node(sendFinish(body)).get("completionAssessment").get("exercises").get(0);
        assertThat(result.get("benchmarkEvidence").get("sessionId").asString()).isEqualTo(best.toString()).isNotEqualTo(firstId.toString());
        assertThat(result.get("benchmarkEvidence").get("reps")).isEqualTo(JSON.valueToTree(List.of(15,8,8)));
        assertThat(result.get("advice").asString()).isEqualTo("MAINTAIN");
    }

    @Test void historicalCutoffExcludesFutureEndedSessionAndAutoClosedRecord() throws Exception {
        var base=initialState();node(finish(1,confirmed(base,new int[]{10,10,10},new Context("INITIAL_APPROVED",null))));
        session=UUID.randomUUID();base=initialStateAt("2026-10-31T16:05:00Z");
        var body=finishBody(1,confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null)));body.put("endedAt","2026-10-31T16:08:00Z");
        assertThat(node(sendFinish(body)).get("completionAssessment").get("exercises").get(0).get("benchmarkEvidence").isNull()).isTrue();
        session=UUID.randomUUID();base=initialStateAt("2026-10-31T17:00:00Z");node(save(1,confirmed(base,new int[]{100,100,100},new Context("INITIAL_APPROVED",null))));
        EmailOtpFixtures.CLOCK.advance(Duration.ofHours(24));node(browser.get(path()));assertThat(row().get("status")).isEqualTo("AUTO_CLOSED");
        session=UUID.randomUUID();base=initialStateAt("2026-10-31T18:00:00Z");body=finishBody(1,confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null)));body.put("endedAt","2026-10-31T18:10:00Z");
        var evidence=node(sendFinish(body)).get("completionAssessment").get("exercises").get(0).get("benchmarkEvidence");
        assertThat(evidence.get("repsSum").asString()).isEqualTo("30");
    }

    @Test void historicalTieResolvesByRecentEndThenCanonicalUuidAscending() throws Exception {
        var ids=new ArrayList<UUID>();
        for(int i=0;i<2;i++) {
            var base=initialState();ids.add(session);node(finish(1,confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null))));session=UUID.randomUUID();
        }
        var base=initialStateAt("2026-10-31T17:00:00Z");var body=finishBody(1,confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null)));body.put("endedAt","2026-10-31T17:10:00Z");
        var evidence=node(sendFinish(body)).get("completionAssessment").get("exercises").get(0).get("benchmarkEvidence");
        assertThat(evidence.get("sessionId").asString()).isEqualTo(ids.stream().map(UUID::toString).sorted().findFirst().orElseThrow());
        UUID recent=session;session=UUID.randomUUID();base=initialStateAt("2026-10-31T18:00:00Z");body=finishBody(1,confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null)));body.put("endedAt","2026-10-31T18:10:00Z");
        assertThat(node(sendFinish(body)).get("completionAssessment").get("exercises").get(0).get("benchmarkEvidence").get("sessionId").asString()).isEqualTo(recent.toString());
    }

    @ParameterizedTest @ValueSource(strings={"snapshot","finish","takeover","resolution"})
    void exactDeadlineClosesBeforeLateRequestAndCommitsDespiteHttpConflict(String action) throws Exception {
        var base=initialState();assertThat(editRoutine("PUT").statusCode()).isEqualTo(200);var request=approval(base,1,null,0,"60","65");
        var value=state(base,List.of(set(base,UUID.randomUUID(),1,"65",8,new Context("APPROVAL_REQUEST",request.id()))),List.of(completedCardio(base,UUID.randomUUID())),List.of(),List.of(),List.of(request));
        node(save(1,value));var stored=row();var sets=jdbc.queryForList("SELECT * FROM performed_sets WHERE session_id=?",session);
        var resolution=resolutionBody(base,List.of(request.id()),"KEEP_CURRENT",null);
        EmailOtpFixtures.CLOCK.advance(Duration.ofHours(24));
        var response=switch(action) {
            case "snapshot"->save(1,value);case "finish"->finish(2,value);
            case "takeover"->browser.send("POST",path()+"/takeover",json(takeoverBody(UUID.randomUUID(),"0")),csrf);
            default->resolve(resolution);
        };
        error(response,409,"SESSION_AUTO_CLOSED");assertThat(row().get("status")).isEqualTo("AUTO_CLOSED");
        assertThat(row().get("ended_at")).isNull();assertThat(row().get("completion_assessment_snapshot")).isNull();
        assertThat(row().get("last_workout_synced_at")).isEqualTo(stored.get("last_workout_synced_at"));
        assertThat(jdbc.queryForList("SELECT * FROM performed_sets WHERE session_id=?",session)).isEqualTo(sets);
        assertThat(count("cardio_results")).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT status FROM session_weight_approval_requests WHERE id=?",String.class,request.id())).isEqualTo("PENDING");
        var closed=row();error(save(3,value),409,"SESSION_AUTO_CLOSED");error(finish(3,value),409,"SESSION_AUTO_CLOSED");assertThat(row()).isEqualTo(closed);
    }

    @Test void noOpActivityDoesNotDelayExpiryAndGetCommitsItOutsideReadOnlyQuery() throws Exception {
        var base=initialState();var started=row();EmailOtpFixtures.CLOCK.advance(Duration.ofHours(23));
        node(save(1,base));node(save(2,base));node(save(2,base));node(browser.get(path()));
        UUID next=UUID.randomUUID();node(browser.send("POST",path()+"/takeover",json(takeoverBody(next,"0")),csrf));
        assertThat(row().get("last_workout_synced_at")).isEqualTo(started.get("last_workout_synced_at"));
        EmailOtpFixtures.CLOCK.advance(Duration.ofHours(1));var result=node(browser.get(path()));
        assertThat(result.get("status").asString()).isEqualTo("AUTO_CLOSED");assertThat(result.get("snapshot").get("state").get("cardioResults").isEmpty()).isTrue();
        assertThat(node(browser.get(BASE+"/active")).get("session").isNull()).isTrue();
    }

    @Test void actualStateChangeBeforeDeadlineExtendsExpiryButOneMillisecondAtDeadlineDoesNot() throws Exception {
        var base=initialState();EmailOtpFixtures.CLOCK.advance(Duration.ofHours(24).minusMillis(1));
        node(save(1,confirmed(base,new int[]{8},new Context("INITIAL_APPROVED",null))));var saved=row();
        EmailOtpFixtures.CLOCK.advance(Duration.ofMillis(1));assertThat(node(browser.get(path())).get("status").asString()).isEqualTo("ACTIVE");
        EmailOtpFixtures.CLOCK.advance(Duration.ofHours(24).minusMillis(1));node(browser.get(path()));assertThat(row().get("status")).isEqualTo("AUTO_CLOSED");
        assertThat(row().get("last_workout_synced_at")).isEqualTo(saved.get("last_workout_synced_at"));assertThat(count("performed_sets")).isEqualTo(1);
    }

    @Test void newStartClosesOldExpiredSessionAndOnlyCreatesOneNewActive() throws Exception {
        var base=initialState();UUID old=session;node(save(1,confirmed(base,new int[]{8},new Context("INITIAL_APPROVED",null))));
        EmailOtpFixtures.CLOCK.advance(Duration.ofHours(24));session=UUID.randomUUID();node(start(startBody()));
        assertThat(jdbc.queryForObject("SELECT status FROM workout_sessions WHERE id=?",String.class,old)).isEqualTo("AUTO_CLOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_sessions WHERE user_id=? AND status='ACTIVE'",Integer.class,owner.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM performed_sets WHERE session_id=?",Integer.class,old)).isEqualTo(1);
    }

    @Test void reservationJobRechecksRowsIsIdempotentAndCatchesUpOnRestart() throws Exception {
        var base=initialState();node(save(1,confirmed(base,new int[]{8,8},new Context("INITIAL_APPROVED",null))));
        var service=app.getBean(WorkoutSessionService.class);EmailOtpFixtures.CLOCK.advance(Duration.ofHours(24));
        new WorkoutExpiryJob(service).closeExpired();assertThat(row().get("status")).isEqualTo("AUTO_CLOSED");var closed=row();
        new WorkoutExpiryJob(service).closeExpired();assertThat(row()).isEqualTo(closed);assertThat(count("performed_sets")).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings={"insert into performed_sets","insert into progress_change_events","update workout_sessions","closure"})
    void finishSqlFailureRollsBackSnapshotApprovalAssessmentAndClosure(String failingSql) throws Exception {
        var base=initialState();var before=row();var request=approval(base,1,null,0,"60","65");
        var one=set(base,UUID.randomUUID(),1,"65",8,new Context("APPROVAL_REQUEST",request.id()));var value=state(base,List.of(one),List.of(),List.of(),List.of(),List.of(request));
        var once=new AtomicBoolean();var updates=new AtomicInteger();WorkoutReadFixtures.observer=event->{
            boolean target=event.before()&&(failingSql.equals("closure")?event.sql().startsWith("update workout_sessions")&&updates.incrementAndGet()==2:event.sql().startsWith(failingSql));
            if(target&&once.compareAndSet(false,true)){
            try(var statement=event.connection().createStatement()){statement.execute("SELECT 1/0");}catch(java.sql.SQLException e){throw new org.springframework.dao.DataAccessResourceFailureException("isolated finish failure",e);}
        }};
        try{error(finish(1,value),503,"WORKOUT_DATA_UNAVAILABLE");}finally{WorkoutReadFixtures.observer=null;}
        assertThat(row()).isEqualTo(before);assertThat(count("performed_sets")).isZero();assertThat(eventCount()).isZero();assertThat(count("session_weight_approval_requests")).isZero();assertThat(routineVersion()).isZero();
        node(finish(1,value));
    }

    @Test void finishClockAndAuthenticationFailuresNeverCommitFinalSnapshot() throws Exception {
        var base=initialState();var value=confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null));var before=row();var body=finishBody(1,value);
        error(browser.send("POST",path()+"/finish",json(body),null),403,"CSRF_INVALID");
        try(var anonymous=new Browser()){error(anonymous.send("POST",path()+"/finish",json(body),null),401,"AUTHENTICATION_REQUIRED");}
        try(var foreign=new Browser()){foreign.login(UUID.randomUUID()+"@example.test");error(foreign.send("POST",path()+"/finish",json(body),foreign.csrf()),404,"SESSION_NOT_FOUND");}
        body.put("dataGeneration","1");error(sendFinish(body),409,"DATA_GENERATION_MISMATCH");body.put("dataGeneration","0");
        body.put("writerEpoch","1");error(sendFinish(body),409,"EDITOR_OWNERSHIP_LOST");body.put("writerEpoch","0");
        body.put("endedAt","2026-10-31T16:01:00Z");error(sendFinish(body),422,"CLOCK_CONTEXT_INVALID");
        assertThat(row()).isEqualTo(before);assertThat(count("performed_sets")).isZero();
    }

    @Test void cardioOnlyFinishHasNoFabricatedWeightAssessmentOrTargetDuration() throws Exception {
        var update=updateRoutine();update.put("weightPlacements",List.of());node(browser.send("PUT",routinePath(),json(update),csrf));
        var base=initialState();var value=state(base,List.of(),List.of(completedCardio(base,UUID.randomUUID())),List.of(),List.of(),List.of());
        var result=node(finish(1,value));assertThat(result.get("completionAssessment").get("overallWeightPlanComplete").isNull()).isTrue();
        assertThat(result.get("completionAssessment").get("exercises").isEmpty()).isTrue();assertThat(count("performed_sets")).isZero();
        assertThat(jdbc.queryForObject("SELECT active_ms FROM cardio_results WHERE session_id=?",Long.class,session)).isEqualTo(60000L);
    }

    @Test void realScheduledJobClosesPersistedOverdueRowsOnApplicationRestart() throws Exception {
        var base=initialState();node(save(1,confirmed(base,new int[]{8},new Context("INITIAL_APPROVED",null))));EmailOtpFixtures.CLOCK.advance(Duration.ofHours(24));
        var key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        try(var restarted=new SpringApplicationBuilder(OverloadServerApplication.class,EmailOtpFixtures.class).web(WebApplicationType.NONE)
                .run("--spring.profiles.active=integration","--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
                        "--OTP_HMAC_KEY="+Base64.getEncoder().encodeToString(key),"--OTP_EMAIL_ENABLED=false","--GOOGLE_OIDC_ENABLED=false","--GOOGLE_REAUTH_ENABLED=false",
                        "--overload.workout.expiry.enabled=true","--overload.workout.expiry.delay-ms=20","--logging.level.root=WARN","--spring.main.banner-mode=off")) {
            MixedPersistenceAssertions.verify(restarted);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(row().get("status").equals("ACTIVE")&&System.nanoTime()<deadline)Thread.sleep(20);
            assertThat(row().get("status")).isEqualTo("AUTO_CLOSED");assertThat(row().get("completion_assessment_snapshot")).isNull();assertThat(count("performed_sets")).isEqualTo(1);
        }
    }

    @ParameterizedTest @ValueSource(strings={"snapshot","takeover","duplicate","job"})
    void finishSerializesCompetingWritesAndExpiryWithoutDuplicatingClosure(String action) throws Exception {
        var base=initialState();var value=confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null));
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        WorkoutReadFixtures.observer=event->{if(!event.before()&&event.sql().startsWith("update workout_sessions")&&once.compareAndSet(false,true)){locked.countDown();await(release);}};
        try(var executor=Executors.newFixedThreadPool(2)) {
            var finishing=executor.submit(()->finish(1,value));assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            if(action.equals("job")) {
                EmailOtpFixtures.CLOCK.advance(Duration.ofHours(24));
                executor.submit(()->app.getBean(WorkoutSessionService.class).expireBatch()).get(10,TimeUnit.SECONDS);
                release.countDown();
            } else {
                var other=executor.submit(()->switch(action){case "snapshot"->save(2,value);case "takeover"->browser.send("POST",path()+"/takeover",json(takeoverBody(UUID.randomUUID(),"0")),csrf);default->finish(1,value);});
                try{assertLockWait("workout_sessions");}finally{release.countDown();}
                var response=other.get(15,TimeUnit.SECONDS);
                if(action.equals("duplicate"))assertThat(node(response).get("duplicate").asBoolean()).isTrue();else error(response,409,"SESSION_NOT_ACTIVE");
            }
            assertThat(finishing.get(15,TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }finally{release.countDown();WorkoutReadFixtures.observer=null;}
        assertThat(row().get("status")).isEqualTo("FINISHED");assertThat(row().get("version")).isEqualTo(1L);assertThat(count("performed_sets")).isEqualTo(3);
    }

    @Test void automaticClosureWinsAgainstLateFinishAtRealTransactionBoundary() throws Exception {
        var base=initialState();var value=confirmed(base,new int[]{8},new Context("INITIAL_APPROVED",null));node(save(1,value));
        EmailOtpFixtures.CLOCK.advance(Duration.ofHours(24));
        // Only this disposable fixture's row is due, so the observed worker lock belongs to this test.
        jdbc.update("UPDATE workout_sessions SET last_workout_synced_at=? WHERE status='ACTIVE' AND user_id<>?",java.sql.Timestamp.from(EmailOtpFixtures.CLOCK.instant().plus(Duration.ofHours(24))),owner.id());
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        WorkoutReadFixtures.observer=event->{if(!event.before()&&event.sql().startsWith("update workout_sessions")&&once.compareAndSet(false,true)){locked.countDown();await(release);}};
        try(var executor=Executors.newFixedThreadPool(2)) {
            var closing=executor.submit(()->app.getBean(WorkoutSessionService.class).expireBatch());assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var finishing=executor.submit(()->finish(2,value));
            try{assertLockWait("workout_sessions");}finally{release.countDown();}
            assertThat(closing.get(15,TimeUnit.SECONDS)).isGreaterThan(0);error(finishing.get(15,TimeUnit.SECONDS),409,"SESSION_AUTO_CLOSED");
        }finally{release.countDown();WorkoutReadFixtures.observer=null;}
        assertThat(row().get("status")).isEqualTo("AUTO_CLOSED");assertThat(row().get("ended_at")).isNull();assertThat(count("performed_sets")).isEqualTo(1);
    }

    @Test void failedAutomaticClosureRollsBackAndTheNextJobRetries() throws Exception {
        var base=initialState();node(save(1,confirmed(base,new int[]{8},new Context("INITIAL_APPROVED",null))));EmailOtpFixtures.CLOCK.advance(Duration.ofHours(24));var before=row();
        WorkoutReadFixtures.observer=event->{if(event.before()&&event.sql().startsWith("update workout_sessions"))throw new org.springframework.dao.DataAccessResourceFailureException("isolated expiry failure");};
        try{app.getBean(WorkoutSessionService.class).expireBatch();}finally{WorkoutReadFixtures.observer=null;}
        assertThat(row()).isEqualTo(before);app.getBean(WorkoutSessionService.class).expireBatch();assertThat(row().get("status")).isEqualTo("AUTO_CLOSED");assertThat(count("performed_sets")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"FINISHED","AUTO_CLOSED"})
    void closedCorrectionsAndDeletesPreserveSealedFactsAndIncrementOnce(String status) throws Exception {
        var base=initialState();var value=state(base,confirmed(base,new int[]{10,10,10},new Context("INITIAL_APPROVED",null)).performedSets(),List.of(completedCardio(base,UUID.randomUUID())),List.of(),List.of(),List.of());
        close(status,value);long beforeVersion=(Long)row().get("version");var sealed=closureFacts();var contexts=node(browser.get(path())).get("snapshot").get("recordedContexts");
        var first=value.performedSets().getFirst();var cardioId=value.cardioResults().getFirst().id();
        var correction=new WorkoutRecordData.CardioCorrection(cardioId,65000,true,"2",120);
        var ack=node(patchRecords(List.of(new WorkoutRecordData.SetCorrection(first.id(),"62.5",9)),List.of(correction),List.of(),List.of()));
        assertThat(ack.get("version").asString()).isEqualTo(Long.toString(beforeVersion+1));assertThat(count("record_change_events")).isEqualTo(2);
        assertThat(closureFacts()).isEqualTo(sealed);assertThat(node(browser.get(path())).get("snapshot").get("recordedContexts")).isEqualTo(contexts);
        assertThat(jdbc.queryForObject("SELECT timer_ms FROM cardio_results WHERE id=?",Long.class,cardioId)).isEqualTo(60000L);
        var again=node(patchRecords(List.of(new WorkoutRecordData.SetCorrection(first.id(),"62.5",9)),List.of(correction),List.of(),List.of()));
        assertThat(again.get("changed").asBoolean()).isFalse();assertThat(again.get("version").asString()).isEqualTo(Long.toString(beforeVersion+1));
        node(patchRecords(List.of(),List.of(),List.of(first.id()),List.of(cardioId)));
        assertThat(row().get("version")).isEqualTo(beforeVersion+2);assertThat(closureFacts()).isEqualTo(sealed);assertThat(count("record_change_events")).isEqualTo(4);
        assertThat(node(browser.get(path())).get("snapshot").get("state").get("performedSets").size()).isEqualTo(2);
        var current=node(browser.get(path()+"/assessment")).get("currentAssessment");
        if(status.equals("AUTO_CLOSED"))assertThat(current.get("reasonCodes").get(0).asString()).isEqualTo("AUTO_CLOSED");
        else assertThat(current.get("evaluation").get("exercises").get(0).get("eligible").asBoolean()).isFalse();
    }

    @Test void currentAdviceUsesCorrectedWeightAndRepsWithoutOverwritingFirstAssessment() throws Exception {
        var base=initialState();var value=confirmed(base,new int[]{10,10,10},new Context("INITIAL_APPROVED",null));node(finish(1,value));
        var first=node(browser.get(path()+"/assessment")).get("completionAssessment");UUID key=value.performedSets().getFirst().id();
        node(patchRecords(List.of(new WorkoutRecordData.SetCorrection(key,"60",5)),List.of(),List.of(),List.of()));
        assertThat(currentExercise().get("advice").asString()).isEqualTo("DECREASE");
        node(patchRecords(List.of(new WorkoutRecordData.SetCorrection(key,"61",5)),List.of(),List.of(),List.of()));
        assertThat(currentExercise().get("advice").asString()).isEqualTo("UNAVAILABLE");
        node(patchRecords(List.of(new WorkoutRecordData.SetCorrection(key,"60",8)),List.of(),List.of(),List.of()));
        assertThat(currentExercise().get("advice").asString()).isEqualTo("MAINTAIN");
        assertThat(node(browser.get(path()+"/assessment")).get("completionAssessment")).isEqualTo(first);
        var retry=node(finish(1,value));assertThat(retry.get("version").asString()).isEqualTo("1");assertThat(retry.get("currentVersion").asString()).isEqualTo("4");
    }

    @Test void explicitRecoveryAddsMissingAndExtraSetsKeepsOrdinalAndIsSafelyRecognizedOnRequery() throws Exception {
        var base=initialState();var original=confirmed(base,new int[]{8},new Context("INITIAL_APPROVED",null));node(finish(1,original));var sealed=closureFacts();
        var missing=new ArrayList<SetValue>();for(int ordinal=2;ordinal<=4;ordinal++)missing.add(set(base,UUID.randomUUID(),ordinal,"60",8,new Context("INITIAL_APPROVED",null)));
        var response=node(recover(missing,List.of()));assertThat(response.get("recoveredIds").size()).isEqualTo(3);assertThat(count("performed_sets")).isEqualTo(4);
        assertThat(currentExercise().get("eligible").asBoolean()).isTrue();assertThat(currentExercise().get("evaluatedPerformedSetIds").size()).isEqualTo(3);
        var duplicate=node(recover(missing,List.of()));assertThat(duplicate.get("changed").asBoolean()).isFalse();assertThat(duplicate.get("alreadyPresentIds").size()).isEqualTo(3);
        assertThat(count("record_change_events")).isEqualTo(3);assertThat(row().get("version")).isEqualTo(2L);assertThat(closureFacts()).isEqualTo(sealed);
        var history=node(browser.get(path()+"/record-change-events")).get("events");assertThat(history.get(0).get("before").isNull()).isTrue();
        assertThat(history.get(0).get("changeContext").get("origin").get("writerEpoch").asString()).isEqualTo("0");
    }

    @Test void unknownApprovalClaimIsStoredUnverifiedAndNeverUpgradedByLaterRequests() throws Exception {
        var base=initialState();node(finish(1,confirmed(base,new int[]{8,8},new Context("INITIAL_APPROVED",null))));
        UUID unknown=UUID.randomUUID(),manufacturer=CatalogFixtures.manufacturer(jdbc);
        var missing=equipmentSet(set(base,UUID.randomUUID(),3,"60",8,new Context("APPROVAL_REQUEST",unknown)),manufacturer,"PLATE");node(recover(List.of(missing),List.of()));
        var detail=node(browser.get(path())).get("snapshot");var context=detail.get("recordedContexts");
        assertThat(context.toString()).contains("UNVERIFIED");assertThat(currentExercise().get("eligible").asBoolean()).isFalse();
        var history=node(browser.get(path()+"/record-change-events")).get("events").get(0).get("changeContext").get("claimedWeightContext");
        assertThat(history.get("requestId").asString()).isEqualTo(unknown.toString());
        assertThat(jdbc.queryForObject("SELECT approval_request_id FROM performed_sets WHERE id=?",UUID.class,missing.id())).isNull();
        var repeated=node(recover(List.of(missing),List.of()));assertThat(repeated.get("changed").asBoolean()).isFalse();assertThat(count("record_change_events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT manufacturer_id FROM performed_sets WHERE id=?",UUID.class,missing.id())).isEqualTo(manufacturer);
        assertThat(jdbc.queryForObject("SELECT load_type FROM performed_sets WHERE id=?",String.class,missing.id())).isEqualTo("PLATE");
    }

    @Test void recoveryUsesStoredActiveApprovalInsteadOfCurrentRoutineWeight() throws Exception {
        var base=initialState();var request=approval(base,1,null,0,"60","62.5");
        node(finish(1,state(base,List.of(),List.of(),List.of(),List.of(),List.of(request))));
        var update=updateRoutine();update.put("expectedVersion","1");update.put("weightApprovals",List.of(Map.of("placementId",placement,"nextApprovedWeightKg","70","occurredAt","2026-10-31T16:12:00Z")));node(browser.send("PUT",routinePath(),json(update),csrf));
        var sets=new ArrayList<SetValue>();for(int ordinal=1;ordinal<=3;ordinal++)sets.add(set(base,UUID.randomUUID(),ordinal,"62.5",8,new Context("APPROVAL_REQUEST",request.id())));
        node(recover(sets,List.of()));assertThat(currentExercise().get("eligible").asBoolean()).isTrue();
        assertThat(currentExercise().get("comparisonContext").get("approvedWeightKg").asString()).isEqualTo("62.5");assertThat(eventCount()).isEqualTo(2);
    }

    @Test void autoClosedRecoveryAcceptsActualCompletionAfterServerClosureWithoutCreatingFinish() throws Exception {
        var base=initialState();close("AUTO_CLOSED",base);var sealed=closureFacts();
        var at=((java.sql.Timestamp)row().get("closed_at")).toInstant().plusSeconds(120);
        var one=new SetValue(UUID.randomUUID(),base.appliedPlan().weightExercises().getFirst().sessionExerciseId(),1,"60",8,at,new Context("INITIAL_APPROVED",null),null,null);
        var result=new Cardio(UUID.randomUUID(),base.appliedPlan().cardioActivities().getFirst().sessionActivityId(),"COMPLETED",at.minusSeconds(60),at,new CardioValue(60000L,60000L,false,null,null));
        node(recover(List.of(one),List.of(result)));assertThat(closureFacts()).isEqualTo(sealed);
        assertThat(row().get("ended_at")).isNull();assertThat(row().get("completion_assessment_snapshot")).isNull();assertThat(row().get("status")).isEqualTo("AUTO_CLOSED");
        assertThat(node(browser.get(path()+"/assessment")).get("currentAssessment").get("advice").asString()).isEqualTo("UNAVAILABLE");
    }

    @ParameterizedTest @ValueSource(strings={"FINISHED","AUTO_CLOSED"})
    void closedApprovalResolutionKeepsOriginalPerformanceContextAndReportsCurrentVersion(String status) throws Exception {
        var base=initialState();node(editRoutine("PUT"));var request=approval(base,1,null,0,"60","65");
        var value=state(base,List.of(set(base,UUID.randomUUID(),1,"65",8,new Context("APPROVAL_REQUEST",request.id()))),List.of(),List.of(),List.of(),List.of(request));
        close(status,value);var frozen=closureFacts();long before=(Long)row().get("version");
        var body=closedResolutionBody(base,List.of(request.id()),"KEEP_CURRENT",null);var original=new LinkedHashMap<>(body);
        var resolved=node(browser.send("POST",path()+"/closed-approval-resolutions",json(body),csrf));
        assertThat(resolved.get("version").asString()).isEqualTo(Long.toString(before+1));assertThat(resolved.get("approvalResults").get(0).get("status").asString()).isEqualTo("NOT_APPLIED");
        assertThat(closureFacts()).isEqualTo(frozen);assertThat(eventCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT approval_state_at_recording FROM performed_sets WHERE session_id=?",String.class,session)).isEqualTo("PENDING");
        error(browser.send("POST",path()+"/closed-approval-resolutions",json(original),csrf),409,"SESSION_VERSION_CONFLICT");
        body.put("expectedVersion",row().get("version").toString());assertThat(node(browser.send("POST",path()+"/closed-approval-resolutions",json(body),csrf)).get("changed").asBoolean()).isFalse();
        assertThat(node(browser.get(path())).get("snapshot").get("approvalResults").get(0).get("reason").asString()).isEqualTo("KEPT_CURRENT");
        if(status.equals("FINISHED")) {
            var retry=node(finish(1,value));assertThat(retry.get("duplicate").asBoolean()).isTrue();assertThat(retry.get("version").asString()).isEqualTo("1");
            assertThat(retry.get("currentVersion").asString()).isEqualTo("2");assertThat(retry.get("approvalResults").get(0).get("status").asString()).isEqualTo("NOT_APPLIED");
        }
    }

    @Test void postClosureReapprovalIsNewFactAndCannotAuthorizeRecoveredOldSessionSets() throws Exception {
        var base=initialState();node(editRoutine("PUT"));var request=approval(base,1,null,0,"60","65");
        close("AUTO_CLOSED",state(base,List.of(set(base,UUID.randomUUID(),1,"65",8,new Context("APPROVAL_REQUEST",request.id()))),List.of(),List.of(),List.of(),List.of(request)));
        var sealed=closureFacts();UUID fresh=UUID.randomUUID();var body=closedResolutionBody(base,List.of(request.id()),"REAPPROVE",
                Map.of("id",fresh,"sequence","2","expectedRoutineVersion","1","previousApprovedWeightKg","60","nextApprovedWeightKg","65"));
        var occurred=EmailOtpFixtures.CLOCK.instant().plusSeconds(120);body.put("occurredAt",occurred.toString());
        node(browser.send("POST",path()+"/closed-approval-resolutions",json(body),csrf));assertThat(eventCount()).isEqualTo(1);assertThat(routineVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT created_after_closure FROM session_weight_approval_requests WHERE id=?",Boolean.class,fresh)).isTrue();
        var claim=new SetValue(UUID.randomUUID(),base.appliedPlan().weightExercises().getFirst().sessionExerciseId(),2,"65",8,occurred.plusSeconds(60),new Context("APPROVAL_REQUEST",fresh),null,null);
        node(recover(List.of(claim),List.of()));
        assertThat(jdbc.queryForObject("SELECT approval_state_at_recording FROM performed_sets WHERE id=?",String.class,claim.id())).isEqualTo("UNVERIFIED");
        assertThat(jdbc.queryForObject("SELECT approval_state_at_recording FROM performed_sets WHERE ordinal=1 AND session_id=?",String.class,session)).isEqualTo("PENDING");
        assertThat(closureFacts()).isEqualTo(sealed);assertThat(row().get("completion_assessment_snapshot")).isNull();
    }

    @Test void sessionLogicalDeletePreservesChildrenAndOriginalFinishConfirmationWithoutResurrection() throws Exception {
        var base=initialState();var value=confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null));node(finish(1,value));var sealed=closureFacts();
        var response=node(browser.send("DELETE",path(),json(recordScope()),csrf));assertThat(response.get("deletedAt").isNull()).isFalse();
        assertThat(count("performed_sets")).isEqualTo(3);assertThat(jdbc.queryForObject("SELECT count(*) FROM performed_sets WHERE session_id=? AND deleted_at IS NOT NULL",Integer.class,session)).isZero();
        assertThat(closureFacts()).isEqualTo(sealed);assertThat(node(browser.get(path())).get("deletedAt").isNull()).isFalse();
        var retry=node(finish(1,value));assertThat(retry.get("duplicate").asBoolean()).isTrue();assertThat(retry.get("deletedAt").isNull()).isFalse();assertThat(row().get("version")).isEqualTo(2L);
        error(patchRecords(List.of(new WorkoutRecordData.SetCorrection(value.performedSets().getFirst().id(),"60",9)),List.of(),List.of(),List.of()),409,"SESSION_DELETED");
        error(recover(List.of(set(base,UUID.randomUUID(),4,"60",8,new Context("INITIAL_APPROVED",null))),List.of()),409,"SESSION_DELETED");
        error(save(2,value),409,"SESSION_DELETED");error(browser.send("POST",path()+"/takeover",json(takeoverBody(UUID.randomUUID(),"0")),csrf),409,"SESSION_DELETED");
        assertThat(node(browser.get(path()+"/assessment")).get("currentAssessment").get("reasonCodes").get(0).asString()).isEqualTo("SESSION_DELETED");
        var event=node(browser.get(path()+"/record-change-events")).get("events").get(0);assertThat(event.get("recordKind").asString()).isEqualTo("SESSION");assertThat(event.get("changeKind").asString()).isEqualTo("DELETED");
    }

    @Test void editingOrDeletingPastCandidateRecalculatesCurrentBenchmarkOnly() throws Exception {
        var base=initialState();var old=confirmed(base,new int[]{10,10,10},new Context("INITIAL_APPROVED",null));node(finish(1,old));UUID past=session;
        session=UUID.randomUUID();base=initialStateAt("2026-10-31T17:00:00Z");var body=finishBody(1,confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null)));body.put("endedAt","2026-10-31T17:10:00Z");node(sendFinish(body));
        UUID current=session;var frozen=row().get("completion_assessment_snapshot");assertThat(currentExercise().get("benchmarkEvidence").get("repsSum").asString()).isEqualTo("30");
        session=past;node(patchRecords(List.of(new WorkoutRecordData.SetCorrection(old.performedSets().getFirst().id(),"60",5)),List.of(),List.of(),List.of()));
        session=current;assertThat(currentExercise().get("benchmarkEvidence").get("repsSum").asString()).isEqualTo("25");assertThat(row().get("completion_assessment_snapshot")).isEqualTo(frozen);
        session=past;node(browser.send("DELETE",path(),json(recordScope()),csrf));session=current;
        assertThat(currentExercise().get("benchmarkEvidence").isNull()).isTrue();assertThat(row().get("completion_assessment_snapshot")).isEqualTo(frozen);
    }

    @ParameterizedTest @ValueSource(strings={"tombstone","different-body","ordinal","new-exercise","future-epoch","before-start","after-end","cardio-slot","cardio-clock"})
    void recoveryRejectsConflictsWithoutPartialInsertionOrRewritingHistory(String conflict) throws Exception {
        var base=initialState();var original=state(base,confirmed(base,new int[]{8},new Context("INITIAL_APPROVED",null)).performedSets(),List.of(completedCardio(base,UUID.randomUUID())),List.of(),List.of(),List.of());node(finish(1,original));
        var first=original.performedSets().getFirst();var candidate=set(base,UUID.randomUUID(),2,"60",8,new Context("INITIAL_APPROVED",null));var sets=new ArrayList<>(List.of(set(base,UUID.randomUUID(),3,"60",8,new Context("INITIAL_APPROVED",null))));var cardio=new ArrayList<Cardio>();
        int status=409;String code="RECORD_SLOT_CONFLICT";
        switch(conflict) {
            case "tombstone"->{node(patchRecords(List.of(),List.of(),List.of(first.id()),List.of()));candidate=first;status=422;code="RECORD_DELETED";}
            case "different-body"->{candidate=new SetValue(first.id(),first.sessionExerciseId(),first.ordinal(),"65",9,first.completedAt(),first.weightContext(),null,null);code="RECORD_ID_CONFLICT";}
            case "ordinal"->candidate=set(base,UUID.randomUUID(),1,"60",8,new Context("INITIAL_APPROVED",null));
            case "new-exercise"->{candidate=new SetValue(UUID.randomUUID(),UUID.randomUUID(),2,"60",8,first.completedAt(),first.weightContext(),null,null);status=422;code="INVALID_SNAPSHOT";}
            case "before-start"->{candidate=new SetValue(UUID.randomUUID(),first.sessionExerciseId(),2,"60",8,java.time.Instant.parse("2026-10-31T15:00:00Z"),first.weightContext(),null,null);status=422;code="CLOCK_CONTEXT_INVALID";}
            case "after-end"->{candidate=new SetValue(UUID.randomUUID(),first.sessionExerciseId(),2,"60",8,java.time.Instant.parse("2026-10-31T17:00:00Z"),first.weightContext(),null,null);status=422;code="CLOCK_CONTEXT_INVALID";}
            case "cardio-slot"->cardio.add(completedCardio(base,UUID.randomUUID()));
            case "cardio-clock"->{var c=completedCardio(base,UUID.randomUUID());cardio.add(new Cardio(c.id(),c.sessionActivityId(),c.status(),c.startedAt(),c.occurredAt(),new CardioValue(60001L,60001L,false,null,null)));status=422;code="CLOCK_CONTEXT_INVALID";}
            case "future-epoch"->{status=422;code="INVALID_RECOVERY_ORIGIN";}
        }
        sets.add(candidate);var body=recoveryBody(sets,cardio);if(conflict.equals("future-epoch"))body.put("origin",Map.of("editorDeviceId",device,"writerEpoch","1","localRevision","12"));
        var before=row();int events=count("record_change_events"),performances=count("performed_sets");
        error(browser.send("POST",path()+"/recoveries",json(body),csrf),status,code);
        assertThat(row()).isEqualTo(before);assertThat(count("record_change_events")).isEqualTo(events);assertThat(count("performed_sets")).isEqualTo(performances);
    }

    @Test void twoAuthenticatedDevicesWithSameExpectedVersionOnlyCommitOneCorrection() throws Exception {
        var base=initialState();var value=confirmed(base,new int[]{8,8,8},new Context("INITIAL_APPROVED",null));node(finish(1,value));
        UUID key=value.performedSets().getFirst().id();var firstBody=recordEdit(List.of(new WorkoutRecordData.SetCorrection(key,"60",9)),List.of(),List.of(),List.of());
        var secondBody=recordEdit(List.of(new WorkoutRecordData.SetCorrection(key,"60",10)),List.of(),List.of(),List.of());
        EmailOtpFixtures.CLOCK.advance(Duration.ofSeconds(61));
        try(var other=new Browser();var executor=Executors.newFixedThreadPool(2)) {
            other.login(email);String otherCsrf=other.csrf();var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
            WorkoutReadFixtures.observer=event->{if(!event.before()&&event.sql().startsWith("update workout_sessions")&&once.compareAndSet(false,true)){locked.countDown();await(release);}};
            try {
                var first=executor.submit(()->browser.send("PATCH",path()+"/records",json(firstBody),csrf));assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
                var second=executor.submit(()->other.send("PATCH",path()+"/records",json(secondBody),otherCsrf));
                try{assertLockWait("workout_sessions");}finally{release.countDown();}
                node(first.get(15,TimeUnit.SECONDS));error(second.get(15,TimeUnit.SECONDS),409,"SESSION_VERSION_CONFLICT");
            }finally{release.countDown();WorkoutReadFixtures.observer=null;}
        }
        assertThat(row().get("version")).isEqualTo(2L);assertThat(count("record_change_events")).isEqualTo(1);
    }

    @Test void closedReapprovalSerializesWithRoutineEditingAndRollsBackLosingExternalVersion() throws Exception {
        var base=initialState();node(editRoutine("PUT"));var request=approval(base,1,null,0,"60","65");node(finish(1,state(base,List.of(),List.of(),List.of(),List.of(),List.of(request))));
        var resolution=closedResolutionBody(base,List.of(request.id()),"REAPPROVE",Map.of("id",UUID.randomUUID(),"sequence","2","expectedRoutineVersion","1","previousApprovedWeightKg","60","nextApprovedWeightKg","65"));resolution.put("occurredAt","2026-10-31T16:12:00Z");
        var routineEdit=updateRoutine();routineEdit.put("expectedVersion","1");
        var locked=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        WorkoutReadFixtures.observer=event->{if(!event.before()&&event.sql().startsWith("update workout_sessions")&&once.compareAndSet(false,true)){locked.countDown();await(release);}};
        try(var executor=Executors.newFixedThreadPool(2)) {
            var resolving=executor.submit(()->browser.send("POST",path()+"/closed-approval-resolutions",json(resolution),csrf));assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var editing=executor.submit(()->browser.send("PUT",routinePath(),json(routineEdit),csrf));
            try{assertLockWait("routines");}finally{release.countDown();}
            node(resolving.get(15,TimeUnit.SECONDS));error(editing.get(15,TimeUnit.SECONDS),409,"ROUTINE_VERSION_CONFLICT");
        }finally{release.countDown();WorkoutReadFixtures.observer=null;}
        assertThat(row().get("version")).isEqualTo(2L);assertThat(routineVersion()).isEqualTo(2);assertThat(eventCount()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"update performed_sets","insert into record_change_events","update workout_sessions","insert into performed_sets","delete-session","approval-event"})
    void postClosureSqlFailureRollsBackFactsAuditApprovalsAndVersion(String failingSql) throws Exception {
        var base=initialState();var value=confirmed(base,new int[]{8},new Context("INITIAL_APPROVED",null));Approval pending=null;
        if(failingSql.equals("approval-event")){node(editRoutine("PUT"));pending=approval(base,1,null,0,"60","65");value=state(base,List.of(),List.of(),List.of(),List.of(),List.of(pending));}
        node(finish(1,value));var before=row();var facts=jdbc.queryForList("SELECT * FROM performed_sets WHERE session_id=?",session);var approvals=jdbc.queryForList("SELECT * FROM session_weight_approval_requests WHERE session_id=?",session);
        var once=new AtomicBoolean();String target=failingSql.equals("delete-session")?"update workout_sessions":failingSql.equals("approval-event")?"insert into progress_change_events":failingSql;
        WorkoutReadFixtures.observer=event->{if(event.before()&&event.sql().startsWith(target)&&once.compareAndSet(false,true)){
            try(var statement=event.connection().createStatement()){statement.execute("SELECT 1/0");}catch(java.sql.SQLException e){throw new org.springframework.dao.DataAccessResourceFailureException("isolated record edit failure",e);}
        }};
        try {
            HttpResponse<String> response;
            if(failingSql.equals("delete-session"))response=browser.send("DELETE",path(),json(recordScope()),csrf);
            else if(failingSql.equals("insert into performed_sets"))response=recover(List.of(set(base,UUID.randomUUID(),2,"60",8,new Context("INITIAL_APPROVED",null))),List.of());
            else if(failingSql.equals("approval-event")) {
                var body=closedResolutionBody(base,List.of(pending.id()),"REAPPROVE",Map.of("id",UUID.randomUUID(),"sequence","2","expectedRoutineVersion","1","previousApprovedWeightKg","60","nextApprovedWeightKg","65"));body.put("occurredAt","2026-10-31T16:12:00Z");
                response=browser.send("POST",path()+"/closed-approval-resolutions",json(body),csrf);
            }else response=patchRecords(List.of(new WorkoutRecordData.SetCorrection(value.performedSets().getFirst().id(),"60",9)),List.of(),List.of(),List.of());
            error(response,503,"WORKOUT_DATA_UNAVAILABLE");assertThat(once).isTrue();
        }finally{WorkoutReadFixtures.observer=null;}
        assertThat(row()).isEqualTo(before);assertThat(jdbc.queryForList("SELECT * FROM performed_sets WHERE session_id=?",session)).isEqualTo(facts);
        assertThat(jdbc.queryForList("SELECT * FROM session_weight_approval_requests WHERE session_id=?",session)).isEqualTo(approvals);assertThat(count("record_change_events")).isZero();assertThat(eventCount()).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"edit","recover","delete","resolve"})
    void closedWritesRequireCsrfCurrentGenerationAndOwnerButNotOldWriterCredentials(String operation) throws Exception {
        var base=initialState();node(editRoutine("PUT"));var pending=approval(base,1,null,0,"60","65");var value=state(base,List.of(set(base,UUID.randomUUID(),1,"60",8,new Context("INITIAL_APPROVED",null))),List.of(),List.of(),List.of(),List.of(pending));node(finish(1,value));
        String method=operation.equals("edit")?"PATCH":operation.equals("delete")?"DELETE":"POST";
        String suffix=switch(operation){case "edit"->"/records";case "recover"->"/recoveries";case "resolve"->"/closed-approval-resolutions";default->"";};
        var body=switch(operation){case "edit"->recordEdit(List.of(new WorkoutRecordData.SetCorrection(value.performedSets().getFirst().id(),"60",9)),List.of(),List.of(),List.of());case "recover"->recoveryBody(List.of(set(base,UUID.randomUUID(),2,"60",8,new Context("INITIAL_APPROVED",null))),List.of());case "resolve"->closedResolutionBody(base,List.of(pending.id()),"KEEP_CURRENT",null);default->recordScope();};
        var before=row();error(browser.send(method,path()+suffix,json(body),null),403,"CSRF_INVALID");
        try(var anonymous=new Browser()){error(anonymous.send(method,path()+suffix,json(body),null),401,"AUTHENTICATION_REQUIRED");}
        try(var foreign=new Browser()){foreign.login(UUID.randomUUID()+"@example.test");error(foreign.send(method,path()+suffix,json(body),foreign.csrf()),404,"SESSION_NOT_FOUND");error(foreign.get(path()+"/assessment"),404,"SESSION_NOT_FOUND");error(foreign.get(path()+"/record-change-events"),404,"SESSION_NOT_FOUND");}
        body.put("dataGeneration","1");error(browser.send(method,path()+suffix,json(body),csrf),409,"DATA_GENERATION_MISMATCH");body.put("dataGeneration","0");
        body.put("expectedVersion","0");error(browser.send(method,path()+suffix,json(body),csrf),409,"SESSION_VERSION_CONFLICT");
        assertThat(row()).isEqualTo(before);assertThat(count("record_change_events")).isZero();
    }

    @Test void activeSessionCannotUseClosedEditsAndOldOriginRecoveryDoesNotRetagEpoch() throws Exception {
        var base=initialState();var one=set(base,UUID.randomUUID(),1,"60",8,new Context("INITIAL_APPROVED",null));
        error(recover(List.of(one),List.of()),409,"SESSION_NOT_CLOSED");error(browser.send("DELETE",path(),json(recordScope()),csrf),409,"SESSION_NOT_CLOSED");error(browser.get(path()+"/assessment"),409,"SESSION_NOT_CLOSED");
        UUID next=UUID.randomUUID();node(browser.send("POST",path()+"/takeover",json(takeoverBody(next,"0")),csrf));
        var finish=finishBody(1,base);finish.put("editorDeviceId",next);finish.put("writerEpoch","1");node(sendFinish(finish));var frozen=closureFacts();
        node(recover(List.of(one),List.of()));assertThat(closureFacts()).isEqualTo(frozen);assertThat(row().get("writer_epoch")).isEqualTo(1L);
        assertThat(node(browser.get(path()+"/record-change-events")).get("events").get(0).get("changeContext").get("origin").get("writerEpoch").asString()).isEqualTo("0");
    }

    @Test void startCopiesRoutineEquipmentOnceAndInactiveCatalogReferencesRemainUsable() throws Exception {
        UUID manufacturer=CatalogFixtures.manufacturer(jdbc);routineEquipment(manufacturer,"PLATE",0);
        jdbc.update("UPDATE standard_exercise SET is_active=false WHERE id=?",exercise);
        jdbc.update("UPDATE manufacturer SET is_active=false WHERE id=?",manufacturer);
        var result=node(start(startBody()));var initial=result.get("initialPlan");
        assertThat(initial.get("schemaVersion").asInt()).isEqualTo(2);
        var weight=initial.get("weightExercises").get(0);
        assertThat(weight.get("standardExerciseId").asString()).isEqualTo(exercise.toString());
        assertThat(weight.get("manufacturerId").asString()).isEqualTo(manufacturer.toString());assertThat(weight.get("loadType").asString()).isEqualTo("PLATE");
        var base=JSON.treeToValue(result.get("snapshot").get("state"),State.class);
        assertThat(base.appliedPlan().schemaVersion()).isEqualTo(2);assertThat(base.appliedPlan().weightExercises().getFirst().manufacturerId()).isEqualTo(manufacturer);
        assertThat(node(start(startBody())).get("initialPlan")).isEqualTo(initial);
        assertThat(save(1,base).statusCode()).isEqualTo(200);var saved=row();
        assertThat(node(save(1,base)).get("duplicate").asBoolean()).isTrue();assertThat(row()).isEqualTo(saved);
        var added=UUID.randomUUID();var identities=new ArrayList<>(base.sessionExercises());identities.add(new Exercise(added,"WEIGHT",exercise));
        var plan=new ArrayList<>(base.appliedPlan().weightExercises());plan.add(new WeightPlan(added,1,6,10,null,null));
        var attempted=new State(identities,new Plan(2,plan,base.appliedPlan().cardioActivities()),List.of(),List.of(),List.of(),List.of(),List.of());
        error(save(2,attempted),404,"EXERCISE_NOT_FOUND");assertThat(row()).isEqualTo(saved);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions WHERE user_id=?",Integer.class,owner.id())).isZero();
    }

    @Test void equipmentSnapshotPreservesMixedCompletedFactsHashAndOfficialEligibility() throws Exception {
        UUID a=CatalogFixtures.manufacturer(jdbc),b=CatalogFixtures.manufacturer(jdbc);routineEquipment(a,"PLATE",0);
        var base=initialState();var initial=row().get("initial_plan_snapshot");var original=confirmed(base,new int[]{10,10,10},new Context("INITIAL_APPROVED",null)).performedSets();
        var first=equipmentSet(original.get(0),a,"PLATE");var second=equipmentSet(original.get(1),a,"PLATE");var third=equipmentSet(original.get(2),b,"STACK");
        var value=state(planEquipment(base,b,"STACK"),List.of(first,second,third),List.of(),List.of(),List.of(),List.of());
        EmailOtpFixtures.CLOCK.advance(Duration.ofSeconds(10));node(save(1,value));var saved=row();
        assertThat(node(save(1,value)).get("duplicate").asBoolean()).isTrue();assertThat(row()).isEqualTo(saved);
        error(save(1,planEquipment(value,a,"PLATE")),409,"REVISION_PAYLOAD_MISMATCH");assertThat(row()).isEqualTo(saved);
        var invalid=state(value,List.of(equipmentSet(first,b,"STACK"),second,third),List.of(),List.of(),List.of(),List.of());
        error(save(2,invalid),422,"IMMUTABLE_RECORD_CONTEXT");assertThat(row()).isEqualTo(saved);
        EmailOtpFixtures.CLOCK.advance(Duration.ofSeconds(10));node(save(2,value));
        assertThat(row().get("last_workout_synced_at")).isEqualTo(saved.get("last_workout_synced_at"));
        assertThat(row().get("initial_plan_snapshot")).isEqualTo(initial);assertThat(routineVersion()).isEqualTo(1);assertThat(eventCount()).isZero();
        assertThat(jdbc.queryForObject("SELECT manufacturer_id FROM routine_weight_placements WHERE id=?",UUID.class,placement)).isEqualTo(a);
        var finished=node(finish(3,value));var assessment=finished.get("completionAssessment");
        assertThat(assessment.get("schemaVersion").asInt()).isEqualTo(2);var evaluation=assessment.get("exercises").get(0);
        assertThat(evaluation.get("eligible").asBoolean()).isTrue();assertThat(evaluation.get("advice").asString()).isEqualTo("INCREASE");
        assertThat(evaluation.get("comparisonContext").get("standardExerciseId").asString()).isEqualTo(exercise.toString());
        assertThat(evaluation.get("comparisonContext").has("manufacturerId")).isFalse();
        var detail=JSON.treeToValue(node(browser.get(path())).get("snapshot").get("state"),State.class);
        assertThat(detail.performedSets()).containsExactlyInAnyOrder(first,second,third);
        assertThat(node(finish(3,value)).get("completionAssessment")).isEqualTo(assessment);
        UUID past=session;session=UUID.randomUUID();base=initialStateAt("2026-10-31T17:00:00Z");
        var next=confirmed(planEquipment(base,null,"BODYWEIGHT"),new int[]{8,8,8},new Context("INITIAL_APPROVED",null));
        var body=finishBody(1,next);body.put("endedAt","2026-10-31T17:10:00Z");
        var compared=node(sendFinish(body)).get("completionAssessment").get("exercises").get(0);
        assertThat(compared.get("benchmarkEvidence").get("sessionId").asString()).isEqualTo(past.toString());
    }

    @ParameterizedTest @ValueSource(strings={"FINISHED","AUTO_CLOSED"})
    void closedRecordEquipmentSurvivesCorrectionDeletionRecoveryAndIdentityChecks(String status) throws Exception {
        UUID a=CatalogFixtures.manufacturer(jdbc),b=CatalogFixtures.manufacturer(jdbc);routineEquipment(a,"PLATE",0);
        var base=initialState();var first=equipmentSet(set(base,UUID.randomUUID(),1,"60",8,new Context("INITIAL_APPROVED",null)),a,"PLATE");
        var value=state(base,List.of(first),List.of(),List.of(),List.of(),List.of());close(status,value);var frozen=closureFacts();
        var missing=equipmentSet(set(base,UUID.randomUUID(),2,"60",8,new Context("INITIAL_APPROVED",null)),b,"DUMBBELL");
        jdbc.update("UPDATE manufacturer SET is_active=false WHERE id=?",b);
        assertThat(node(recover(List.of(missing),List.of())).get("recoveredIds").size()).isEqualTo(1);
        var before=row();error(recover(List.of(equipmentSet(missing,a,"PLATE")),List.of()),409,"RECORD_ID_CONFLICT");assertThat(row()).isEqualTo(before);
        assertThat(node(recover(List.of(missing),List.of())).get("changed").asBoolean()).isFalse();
        node(patchRecords(List.of(new WorkoutRecordData.SetCorrection(first.id(),"61",9)),List.of(),List.of(),List.of()));
        assertThat(jdbc.queryForObject("SELECT manufacturer_id FROM performed_sets WHERE id=?",UUID.class,first.id())).isEqualTo(a);
        assertThat(jdbc.queryForObject("SELECT load_type FROM performed_sets WHERE id=?",String.class,first.id())).isEqualTo("PLATE");
        var events=node(browser.get(path()+"/record-change-events")).get("events");
        assertThat(events.toString()).contains(a.toString(),b.toString(),"DUMBBELL","PLATE");
        node(patchRecords(List.of(),List.of(),List.of(first.id()),List.of()));
        error(recover(List.of(first),List.of()),422,"RECORD_DELETED");
        assertThat(jdbc.queryForObject("SELECT manufacturer_id FROM performed_sets WHERE id=?",UUID.class,first.id())).isEqualTo(a);
        assertThat(closureFacts()).isEqualTo(frozen);
    }

    @Test void routineEquipmentEditUsesExistingApprovalRaceWhileSessionEquipmentDoesNotChangeRoutine() throws Exception {
        UUID a=CatalogFixtures.manufacturer(jdbc),b=CatalogFixtures.manufacturer(jdbc);routineEquipment(a,"PLATE",0);var base=initialState();
        var before=row().get("last_workout_synced_at");EmailOtpFixtures.CLOCK.advance(Duration.ofSeconds(10));
        var local=planEquipment(base,b,"STACK");node(save(1,local));assertThat(routineVersion()).isEqualTo(1);
        assertThat(row().get("last_workout_synced_at")).isNotEqualTo(before);
        routineEquipment(b,"STACK",1);var approval=approval(base,1,null,1,"60","65");
        var value=state(local,List.of(equipmentSet(set(base,UUID.randomUUID(),1,"60",8,new Context("INITIAL_APPROVED",null)),a,"PLATE")),List.of(),List.of(),List.of(),List.of(approval));
        var saved=node(save(2,value));assertThat(saved.get("approvalResults").get(0).get("reason").asString()).isEqualTo("ROUTINE_CHANGED");
        assertThat(eventCount()).isZero();assertThat(count("performed_sets")).isEqualTo(1);assertThat(routineVersion()).isEqualTo(2);
    }

    @Test void unknownManufacturerRejectsCompleteSnapshotAndRecoveryWithoutPartialWrites() throws Exception {
        var base=initialState();var original=row();var unknown=UUID.randomUUID();
        var fact=set(base,UUID.randomUUID(),1,"60",8,new Context("INITIAL_APPROVED",null));
        error(save(1,state(planEquipment(base,unknown,"STACK"),List.of(fact),List.of(),List.of(),List.of(),List.of())),422,"INVALID_SNAPSHOT");
        assertThat(row()).isEqualTo(original);assertThat(count("performed_sets")).isZero();
        error(save(1,state(base,List.of(equipmentSet(fact,unknown,"STACK")),List.of(),List.of(),List.of(),List.of())),422,"INVALID_SNAPSHOT");
        assertThat(row()).isEqualTo(original);node(finish(1,base));var frozen=row();
        error(recover(List.of(equipmentSet(fact,unknown,"STACK")),List.of()),422,"INVALID_SNAPSHOT");assertThat(row()).isEqualTo(frozen);assertThat(count("record_change_events")).isZero();
    }

    @Test void actualCardioCalculationUsesPausedActivityTimeAndStaysOnTheSessionStartDateAcrossMidnight() throws Exception {
        var body=startBody();body.put("startedAt","2026-10-31T14:50:00Z");body.put("workoutDate","2026-10-31");
        var base=JSON.treeToValue(node(start(body)).get("snapshot").get("state"),State.class);UUID activity=base.appliedPlan().cardioActivities().getFirst().sessionActivityId();
        var cardio=new Cardio(UUID.randomUUID(),activity,"COMPLETED",Instant.parse("2026-10-31T14:55:00Z"),Instant.parse("2026-10-31T15:35:00Z"),new CardioValue(1800000L,1800000L,false,null,null,new CardioMeasurement("8",null)));
        var value=state(base,List.of(),List.of(cardio),List.of(),List.of(),List.of());var finish=finishBody(1,value);finish.put("endedAt","2026-10-31T15:40:00Z");String frozen=json(finish);
        node(browser.send("POST",path()+"/finish",frozen,csrf));var before=row();
        assertThat(node(browser.send("POST",path()+"/finish",frozen,csrf)).get("duplicate").asBoolean()).isTrue();assertThat(row()).isEqualTo(before);
        var actual=node(browser.get(path())).get("snapshot").get("state").get("cardioResults").get(0).get("result");
        assertThat(actual.get("distanceKm").asString()).isEqualTo("4");assertThat(actual.get("measurement").get("actualSpeedKmh").asString()).isEqualTo("8");assertThat(actual.get("measurement").get("distanceSource").asString()).isEqualTo("CALCULATED");
        var stats=node(browser.get("/api/v1/statistics/overview?fromDate=2026-10-31&toDateExclusive=2026-11-02"));
        assertThat(stats.get("statistics").get("days").get(0).get("cardioActiveMs").asString()).isEqualTo("1800000");assertThat(stats.get("statistics").get("days").get(1).get("cardioActiveMs").asString()).isEqualTo("0");
        assertThat(stats.get("cardioByKind").get(0).get("calculatedDistanceKm").asString()).isEqualTo("4");assertThat(eventCount()).isZero();
    }
    @Test void cardioManualCorrectionsKeepProvenanceAndNeverRewriteTheInitialPlanOrSealedEvaluation() throws Exception {
        var base=initialState();UUID activity=base.appliedPlan().cardioActivities().getFirst().sessionActivityId(),id=UUID.randomUUID();
        var actual=new Cardio(id,activity,"COMPLETED",Instant.parse("2026-10-31T16:01:00Z"),Instant.parse("2026-10-31T16:31:00Z"),new CardioValue(1800000L,1800000L,false,"3.8",null,new CardioMeasurement("8","MANUAL")));
        var finish=finishBody(1,state(base,List.of(),List.of(actual),List.of(),List.of(),List.of()));finish.put("endedAt","2026-10-31T16:40:00Z");node(sendFinish(finish));
        var sealed=jdbc.queryForMap("SELECT initial_plan_snapshot::text,applied_plan_snapshot::text,completion_assessment_snapshot::text,last_snapshot_hash,last_workout_synced_at FROM workout_sessions WHERE id=?",session);
        node(patchRecords(List.of(),List.of(new WorkoutRecordData.CardioCorrection(id,3600000L,true,"3.8",120)),List.of(),List.of()));
        var stored=jdbc.queryForMap("SELECT distance,measurement::text FROM cardio_results WHERE id=?",id);assertThat(stored.get("distance").toString()).isEqualTo("3.800000");assertThat(stored.get("measurement").toString()).contains("MANUAL","8");
        node(patchRecords(List.of(),List.of(new WorkoutRecordData.CardioCorrection(id,3600000L,true,"3.5",120,new CardioMeasurement("10","MANUAL"))),List.of(),List.of()));
        assertThat(jdbc.queryForObject("SELECT distance FROM cardio_results WHERE id=?",java.math.BigDecimal.class,id)).isEqualByComparingTo("3.5");
        node(patchRecords(List.of(),List.of(new WorkoutRecordData.CardioCorrection(id,3600000L,true,null,120,new CardioMeasurement("10",null))),List.of(),List.of()));
        assertThat(jdbc.queryForObject("SELECT distance FROM cardio_results WHERE id=?",java.math.BigDecimal.class,id)).isEqualByComparingTo("10");
        var calculated=jdbc.queryForMap("SELECT * FROM cardio_results WHERE id=?",id);var beforeFailure=row();
        error(patchRecords(List.of(),List.of(new WorkoutRecordData.CardioCorrection(id,3600000L,true,"15",120,new CardioMeasurement("10","CALCULATED"))),List.of(),List.of()),400,"INVALID_REQUEST");
        assertThat(jdbc.queryForMap("SELECT * FROM cardio_results WHERE id=?",id)).isEqualTo(calculated);assertThat(row()).isEqualTo(beforeFailure);
        assertThat(jdbc.queryForMap("SELECT initial_plan_snapshot::text,applied_plan_snapshot::text,completion_assessment_snapshot::text,last_snapshot_hash,last_workout_synced_at FROM workout_sessions WHERE id=?",session)).isEqualTo(sealed);
        assertThat(count("record_change_events")).isEqualTo(3);assertThat(eventCount()).isZero();
    }
    @Test void legacyCardioRequestsRemainExactAndTargetSpeedNeverBecomesActualSpeed() throws Exception {
        var base=initialState();UUID activity=base.appliedPlan().cardioActivities().getFirst().sessionActivityId();var target=cardioPlan();target.put("intensity",Map.of("mode","SPEED","value","8"));
        var applied=new State(base.sessionExercises(),new Plan(2,base.appliedPlan().weightExercises(),List.of(new CardioPlan(activity,JSON.valueToTree(target)))),List.of(),List.of(),List.of(),List.of(),List.of());
        var actual=new Cardio(UUID.randomUUID(),activity,"COMPLETED",Instant.parse("2026-10-31T16:01:00Z"),Instant.parse("2026-10-31T16:02:00Z"),new CardioValue(60000L,60000L,false,null,null));
        var value=state(applied,List.of(),List.of(actual),List.of(),List.of(),List.of());String frozen=json(saveBody(1,value));assertThat(frozen).doesNotContain("measurement");
        node(browser.send("PUT",path()+"/snapshot",frozen,csrf));var before=row();assertThat(node(browser.send("PUT",path()+"/snapshot",frozen,csrf)).get("duplicate").asBoolean()).isTrue();assertThat(row()).isEqualTo(before);
        var result=node(browser.get(path())).get("snapshot").get("state").get("cardioResults").get(0).get("result");assertThat(result.get("distanceKm").isNull()).isTrue();assertThat(result.has("measurement")).isFalse();
        node(finish(1,value));var stats=node(browser.get("/api/v1/statistics/overview?fromDate=2026-11-01&toDateExclusive=2026-11-02"));assertThat(stats.get("cardioByKind").get(0).get("distanceKm").isNull()).isTrue();assertThat(stats.get("cardioByKind").get(0).get("recordsWithoutDistance").asString()).isEqualTo("1");
    }
    void routineEquipment(UUID manufacturer,String load,long version) throws Exception {
        var input=updateRoutine();input.put("expectedVersion",Long.toString(version));input.put("name","session source");
        var weight=equipment(Map.of("mode","EXISTING","id",placement,"setCount",3,"minReps",6,"maxReps",10));
        weight.put("manufacturerId",manufacturer);weight.put("loadType",load);input.put("weightPlacements",List.of(weight));
        assertThat(browser.send("PUT",routinePath(),json(input),csrf).statusCode()).isEqualTo(200);
    }
    static State planEquipment(State value,UUID manufacturer,String load) {
        var weights=value.appliedPlan().weightExercises().stream().map(p->new WeightPlan(p.sessionExerciseId(),p.setCount(),p.minReps(),p.maxReps(),manufacturer,load)).toList();
        return new State(value.sessionExercises(),new Plan(2,weights,value.appliedPlan().cardioActivities()),value.performedSets(),value.cardioResults(),value.deletedPerformedSetIds(),value.deletedCardioResultIds(),value.approvalRequests());
    }
    static SetValue equipmentSet(SetValue value,UUID manufacturer,String load) {
        return new SetValue(value.id(),value.sessionExerciseId(),value.ordinal(),value.weightKg(),value.reps(),value.completedAt(),value.weightContext(),manufacturer,load);
    }

    Map<String,Object> closedResolutionBody(State base,List<UUID> pending,String decision,Object next) {
        var body=resolutionBody(base,pending,decision,next);body.remove("editorDeviceId");body.remove("writerEpoch");body.remove("expectedLocalRevision");body.put("expectedVersion",body.remove("expectedSessionVersion"));return body;
    }
    Map<String,Object> recordScope() { return new LinkedHashMap<>(Map.of("dataGeneration","0","expectedVersion",row().get("version").toString())); }
    Map<String,Object> recordEdit(List<WorkoutRecordData.SetCorrection> sets,List<WorkoutRecordData.CardioCorrection> cardio,List<UUID> deletedSets,List<UUID> deletedCardio) {
        var body=recordScope();body.put("setCorrections",sets);body.put("cardioCorrections",cardio);body.put("deletedPerformedSetIds",deletedSets);body.put("deletedCardioResultIds",deletedCardio);return body;
    }
    HttpResponse<String> patchRecords(List<WorkoutRecordData.SetCorrection> sets,List<WorkoutRecordData.CardioCorrection> cardio,List<UUID> deletedSets,List<UUID> deletedCardio)throws Exception {
        return browser.send("PATCH",path()+"/records",json(recordEdit(sets,cardio,deletedSets,deletedCardio)),csrf);
    }
    Map<String,Object> recoveryBody(List<SetValue> sets,List<Cardio> cardio) {
        var body=recordScope();body.put("confirm",true);body.put("origin",Map.of("editorDeviceId",device,"writerEpoch","0","localRevision","12"));body.put("performedSets",sets);body.put("cardioResults",cardio);return body;
    }
    HttpResponse<String> recover(List<SetValue> sets,List<Cardio> cardio)throws Exception{return browser.send("POST",path()+"/recoveries",json(recoveryBody(sets,cardio)),csrf);}
    void close(String status,State state)throws Exception {
        if(status.equals("FINISHED"))node(finish(1,state));else {node(save(1,state));EmailOtpFixtures.CLOCK.advance(Duration.ofHours(24));node(browser.get(path()));}
    }
    Map<String,Object> closureFacts() {
        var copy=new LinkedHashMap<>(row());copy.remove("version");copy.remove("updated_at");copy.remove("deleted_at");return copy;
    }
    JsonNode currentExercise()throws Exception{return node(browser.get(path()+"/assessment")).get("currentAssessment").get("evaluation").get("exercises").get(0);}

    State initialStateAt(String started) throws Exception {var body=startBody();body.put("startedAt",started);return JSON.treeToValue(node(start(body)).get("snapshot").get("state"),State.class);}
    State confirmed(State base,int[] reps,Context context) {
        var values=new ArrayList<SetValue>();var started=((java.sql.Timestamp)row().get("started_at")).toInstant();
        for(int i=0;i<reps.length;i++) values.add(new SetValue(UUID.randomUUID(),base.appliedPlan().weightExercises().getFirst().sessionExerciseId(),i+1,"60",reps[i],started.plusSeconds(120+i),context,null,null));
        return state(base,values,List.of(),List.of(),List.of(),List.of());
    }
    Map<String,Object> finishBody(long revision,State state) {var body=saveBody(revision,state);body.put("endedAt","2026-10-31T16:10:00Z");return body;}
    HttpResponse<String> sendFinish(Map<String,Object> body)throws Exception{return browser.send("POST",path()+"/finish",json(body),csrf);}
    HttpResponse<String> finish(long revision,State state)throws Exception{return sendFinish(finishBody(revision,state));}

    State initialState() throws Exception { return JSON.treeToValue(node(start(startBody())).get("snapshot").get("state"), State.class); }
    static State state(State base,List<SetValue> sets,List<Cardio> cardio,List<UUID> deletedSets,List<UUID> deletedCardio,List<Approval> approvals) {
        return new State(base.sessionExercises(),base.appliedPlan(),sets,cardio,deletedSets,deletedCardio,approvals);
    }
    static SetValue set(State base,UUID id,int ordinal,String weight,int reps,Context context) { return new SetValue(id,base.appliedPlan().weightExercises().getFirst().sessionExerciseId(),ordinal,weight,reps,java.time.Instant.parse("2026-10-31T16:02:00Z"),context,null,null); }
    static Cardio completedCardio(State base,UUID id) { return new Cardio(id,base.appliedPlan().cardioActivities().getFirst().sessionActivityId(),"COMPLETED",java.time.Instant.parse("2026-10-31T16:01:00Z"),java.time.Instant.parse("2026-10-31T16:02:00Z"),new CardioValue(60000L,60000L,false,null,null)); }
    static Approval approval(State base,long sequence,UUID previous,long version,String oldWeight,String newWeight) { return new Approval(UUID.randomUUID(),base.appliedPlan().weightExercises().getFirst().sessionExerciseId(),sequence,previous,version,oldWeight,newWeight,java.time.Instant.parse("2026-10-31T16:01:00Z").plusMillis(sequence)); }
    Map<String,Object> saveBody(long revision,State value) { return new LinkedHashMap<>(Map.of("dataGeneration","0","editorDeviceId",device,"writerEpoch","0","localRevision",Long.toString(revision),"snapshot",value)); }
    HttpResponse<String> save(long revision,State value) throws Exception { return browser.send("PUT",path()+"/snapshot",json(saveBody(revision,value)),csrf); }
    HttpResponse<String> resolve(Map<String,Object> body) throws Exception { return browser.send("POST",path()+"/approval-resolutions",json(body),csrf); }
    Map<String,Object> resolutionBody(State base,List<UUID> pending,String decision,Object next) {
        var body=new LinkedHashMap<String,Object>(); body.put("dataGeneration","0");body.put("editorDeviceId",device);body.put("writerEpoch","0");body.put("expectedSessionVersion",row().get("version").toString());body.put("expectedLocalRevision",row().get("local_revision").toString());
        body.put("sessionExerciseId",base.appliedPlan().weightExercises().getFirst().sessionExerciseId());body.put("pendingRequestIds",pending);body.put("decision",decision);body.put("occurredAt","2026-10-31T16:05:00Z");body.put("newApprovalRequest",next);return body;
    }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE session_id=?",Integer.class,session); }
    long routineVersion() { return jdbc.queryForObject("SELECT version FROM routines WHERE id=?",Long.class,routine); }
    int eventCount() { return jdbc.queryForObject("SELECT count(*) FROM progress_change_events WHERE routine_id=?",Integer.class,routine); }

    HttpResponse<String> editRoutine(String method) throws Exception {
        return browser.send(method, routinePath(), json(method.equals("PUT") ? updateRoutine() : Map.of("dataGeneration", "0", "expectedVersion", "0")), csrf);
    }
    Map<String, Object> routineBody() {
        return new LinkedHashMap<>(Map.of("id", routine, "dataGeneration", "0", "name", "session source", "weightPlacements",
                List.of(equipment(Map.of("id", placement, "standardExerciseId", exercise, "setCount", 3, "minReps", 6, "maxReps", 10, "initialWeightKg", "60"))),
                "cardioPlacements", List.of(Map.of("id", cardio, "plan", cardioPlan()))));
    }
    Map<String, Object> updateRoutine() {
        return new LinkedHashMap<>(Map.of("dataGeneration", "0", "expectedVersion", "0", "name", "updated source", "weightPlacements",
                List.of(equipment(Map.of("mode", "EXISTING", "id", placement, "setCount", 4, "minReps", 6, "maxReps", 10))),
                "cardioPlacements", List.of(Map.of("mode", "EXISTING", "id", cardio, "plan", cardioPlan())), "weightApprovals", List.of()));
    }
    static Map<String,Object> equipment(Map<String,?> input) { var value=new LinkedHashMap<String,Object>(input); value.put("manufacturerId",null); value.put("loadType",null); return value; }
    static Map<String, Object> cardioPlan() {
        var result = new LinkedHashMap<String, Object>();
        result.put("kind", "TREADMILL"); result.put("name", "run"); result.put("targetSeconds", 600);
        for (String key : List.of("targetDistanceKm", "intensity", "inclinePercent", "resistance")) result.put(key, null);
        return result;
    }
    Map<String, Object> startBody() {
        return new LinkedHashMap<>(Map.of("id", session, "dataGeneration", "0", "routineId", routine, "editorDeviceId", device,
                "startedAt", "2026-10-31T16:00:00.123Z", "workoutDate", "2026-11-01", "startedTimeZone", "Asia/Seoul"));
    }
    static Map<String, Object> takeoverBody(UUID device, String epoch) {
        return new LinkedHashMap<>(Map.of("dataGeneration", "0", "editorDeviceId", device, "expectedWriterEpoch", epoch));
    }
    String path() { return BASE + "/" + session; }
    String routinePath() { return "/api/v1/routines/" + routine; }
    HttpResponse<String> start(Map<String, Object> body) throws Exception { return browser.send("POST", BASE, json(body), csrf); }
    Map<String, Object> row() { return jdbc.queryForMap("SELECT * FROM workout_sessions WHERE id=?", session); }
    static String json(Object value) { return JSON.writeValueAsString(value); }
    static JsonNode node(HttpResponse<String> response) { headers(response); assertThat(response.statusCode()).as(response.body()).isIn(200, 201); return JSON.readTree(response.body()); }
    static void error(HttpResponse<String> response, int status, String code) {
        headers(response); assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree(json(Map.of("code", code))));
    }
    static void headers(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Request-ID").orElse("")).matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    }
    static void await(CyclicBarrier barrier) { try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); } }
    static void await(CountDownLatch latch) { try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }
    static void assertLockWait(String table) throws Exception {
        var independent = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting;
        do {
            waiting = independent.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE ?)", Boolean.class, "%from " + table + " %");
            if (!waiting) Thread.sleep(20);
        } while (!waiting && System.nanoTime() < deadline);
        assertThat(waiting).isTrue();
    }
    static final class Browser implements AutoCloseable {
        final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(5)).build();
        void login(String email) throws Exception {
            String token = csrf(); var issued = send("POST", "/api/v1/auth/email-otp/request", json(Map.of("email", email)), token);
            assertThat(issued.statusCode()).as(issued.body()).isEqualTo(202);
            var message = app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString()));
            assertThat(send("POST", "/api/v1/auth/email-otp/verify", json(Map.of("email", email, "challengeId", message.issuanceId(), "code", message.code())), token).statusCode()).isEqualTo(204);
        }
        String csrf() throws Exception { return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString(); }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort() + path)).timeout(Duration.ofSeconds(20));
            if (token != null) request.header("X-CSRF-TOKEN", token);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { client.close(); }
    }
}
