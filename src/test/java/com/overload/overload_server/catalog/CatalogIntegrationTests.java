package com.overload.overload_server.catalog;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.testfixture.*;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
@Testcontainers
class CatalogIntegrationTests {
    static final String SEARCH = "/api/v1/standard-exercises/search-data", MANUFACTURERS = "/api/v1/manufacturers";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Instant NOW = Instant.parse("2026-11-02T00:00:00Z");
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_catalog_api_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext app;
    static JdbcTemplate jdbc;
    Browser browser;

    @BeforeAll static void start() {
        assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432, 5433);
        assertThat(POSTGRES.getDatabaseName()).isEqualTo("overload_catalog_api_test");
        var key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
        app = new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class, WorkoutReadFixtures.class)
                .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword(), "--server.address=127.0.0.1", "--server.port=0",
                        "--OTP_HMAC_KEY=" + Base64.getEncoder().encodeToString(key), "--OTP_EMAIL_ENABLED=false", "--GOOGLE_OIDC_ENABLED=false",
                        "--GOOGLE_REAUTH_ENABLED=false", "--spring.session.jdbc.cleanup-cron=-", "--logging.level.root=WARN", "--spring.main.banner-mode=off");
        jdbc = app.getBean(JdbcTemplate.class);
        MixedPersistenceAssertions.verify(app);
        assertThat(app.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        app.getBean(Flyway.class).validate();
        assertThat(app.getBean(Flyway.class).info().current().getVersion().toString()).isEqualTo("21");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standard_exercise", Integer.class)).isZero();
    }

    @BeforeEach void prepare() throws Exception {
        WorkoutReadFixtures.observer = null;
        EmailOtpFixtures.CLOCK.reset(); app.getBean(EmailOtpFixtures.FakeSender.class).reset();
        jdbc.update("DELETE FROM exercise_alias"); jdbc.update("DELETE FROM exercise_muscle");
        jdbc.update("DELETE FROM muscle"); jdbc.update("DELETE FROM standard_exercise"); jdbc.update("DELETE FROM manufacturer");
        jdbc.update("DELETE FROM email_otp_rate_limits");
        browser = new Browser(); browser.login();
    }

    @AfterEach void cleanup() { WorkoutReadFixtures.observer = null; if (browser != null) browser.close(); }
    @AfterAll static void stop() { if (app != null) app.close(); }

    @Test void emptyCatalogRespondsWithoutSeedingOrMixingPersonalExercises() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions", Integer.class)).isZero();
        var owner=jdbc.queryForObject("SELECT id FROM users LIMIT 1",UUID.class);
        CatalogFixtures.personalExercises(jdbc,owner);
        assertThat(success(browser.get(SEARCH))).isEqualTo(JSON.readTree("{\"schemaVersion\":1,\"exercises\":[]}"));
        assertThat(success(browser.get(MANUFACTURERS))).isEqualTo(JSON.readTree("{\"manufacturers\":[]}"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM standard_exercise", Integer.class)).isZero();
    }

    @Test void activeExercisesAndAliasesAreUniqueOrderedSharedAndLightweight() throws Exception {
        exercise(1, "바벨 벤치프레스", true); exercise(2, "덤벨 벤치프레스", true);
        exercise(3, "불가리안 스플릿 스쿼트", true); exercise(4, "하이로우", false);
        alias(1, "벤치"); alias(1, "barbell bench press"); alias(2, "벤치"); alias(4, "숨김 별칭");
        var response = browser.get(SEARCH); var data = success(response);
        assertThat(data.size()).isEqualTo(2); assertThat(data.get("schemaVersion").asInt()).isEqualTo(1);
        var values = data.get("exercises"); assertThat(values.size()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(values.get(i).get("id").asString()).isEqualTo(id(i + 1).toString());
            assertThat(values.get(i).size()).isEqualTo(3);
        }
        assertThat(values.get(0).get("aliases")).isEqualTo(JSON.readTree("[\"barbell bench press\",\"벤치\"]"));
        assertThat(values.get(1).get("aliases")).isEqualTo(JSON.readTree("[\"벤치\"]"));
        assertThat(values.get(2).get("aliases").isEmpty()).isTrue();
        assertThat(browser.get(SEARCH).body()).isEqualTo(response.body());
        try (var other = new Browser()) { other.login(); assertThat(other.get(SEARCH).body()).isEqualTo(response.body()); }
    }

    @Test void manufacturersAreActiveOnlyOrderedAndIndependentFromExercises() throws Exception {
        manufacturer(1, "파나타", true); manufacturer(2, "해머스트렝스", true); manufacturer(3, "비활성", false);
        var response = browser.get(MANUFACTURERS); var items = success(response).get("manufacturers");
        assertThat(items.size()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            assertThat(items.get(i).size()).isEqualTo(2);
            assertThat(items.get(i).get("id").asString()).isEqualTo(id(i + 1).toString());
        }
        assertThat(items.get(0).get("name").asString()).isEqualTo("파나타");
        assertThat(browser.get(MANUFACTURERS).body()).isEqualTo(response.body());
        assertThat(success(browser.get(SEARCH)).get("exercises").isEmpty()).isTrue();
    }

    @Test void reviewedJointTypesRoundTripWithoutChangingSearchContractOrGuessingUnknownValues() throws Exception {
        var repository = app.getBean(StandardExerciseRepository.class);
        for (var type : StandardExercise.JointType.values()) {
            UUID key = id(type.ordinal() + 1);
            repository.saveAndFlush(StandardExercise.create(key, type.name(), type, true, NOW));
            assertThat(repository.findById(key)).get().extracting(StandardExercise::getJointType).isEqualTo(type);
        }
        repository.saveAndFlush(StandardExercise.create(id(4), "unreviewed", true, NOW));
        assertThat(repository.findById(id(4))).get().extracting(StandardExercise::getJointType).isNull();
        var values = success(browser.get(SEARCH)).get("exercises");
        assertThat(values.size()).isEqualTo(4);
        for (var value : values) assertThat(value.size()).isEqualTo(3);
    }

    @Test void allFiveJpaMappingsRoundTripCompositeKeysAndMultiplePrimaryMuscles() {
        exercise(1, "동일 이름", true); exercise(2, "동일 이름", true); manufacturer(1, "독립 제조사", true);
        var stored = app.getBean(ExerciseAliasRepository.class).saveAndFlush(ExerciseAlias.create(id(1), "　ＢＥＮＣＨ\u00a0 PRESS　"));
        assertThat(app.getBean(ExerciseAliasRepository.class).findById(new ExerciseAlias.Key(id(1), "bench press")))
                .get().extracting(ExerciseAlias::getAlias).isEqualTo(stored.getAlias());
        var muscles = app.getBean(MuscleRepository.class);
        muscles.saveAndFlush(Muscle.create(id(11), Muscle.Group.CHEST, "근육1", true));
        muscles.saveAndFlush(Muscle.create(id(12), Muscle.Group.CHEST, "근육2", true));
        var links = app.getBean(ExerciseMuscleRepository.class);
        links.saveAndFlush(ExerciseMuscle.create(id(1), id(11), ExerciseMuscle.Role.PRIMARY));
        links.saveAndFlush(ExerciseMuscle.create(id(1), id(12), ExerciseMuscle.Role.PRIMARY));
        assertThat(links.findAll()).hasSize(2).allMatch(value -> value.getRole() == ExerciseMuscle.Role.PRIMARY);
        assertThat(links.findById(new ExerciseMuscle.Key(id(1), id(11)))).isPresent();
        assertThat(muscles.findById(id(11))).get().extracting(Muscle::getGroup).isEqualTo(Muscle.Group.CHEST);
        assertThat(app.getBean(StandardExerciseRepository.class).findAll()).hasSize(2);
        assertThat(app.getBean(ManufacturerRepository.class).findById(id(1))).get().extracting(Manufacturer::getName).isEqualTo("독립 제조사");
    }

    @Test void aliasUniquenessIsPerExerciseAndConstraintsRejectInvalidRowsAndCascadingDeletes() {
        exercise(1, "벤치", true); exercise(2, "벤치", true); alias(1, "Bench"); alias(2, "BENCH");
        dbError("23505", () -> jdbc.update("INSERT INTO exercise_alias VALUES (?, 'Ｂｅｎｃｈ', 'bench')", id(1)));
        jdbc.update("INSERT INTO muscle(id,group_code,name) VALUES (?,'CHEST','근육1'),(?,'CHEST','근육2')", id(11), id(12));
        jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'PRIMARY'),(?,?,'PRIMARY')", id(1), id(11), id(1), id(12));
        dbError("23505", () -> jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'SECONDARY')", id(1), id(11)));
        dbError("23514", () -> jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'OTHER')", id(2), id(11)));
        dbError("23514", () -> jdbc.update("INSERT INTO muscle(id,group_code,name) VALUES (?,'UNKNOWN','근육')", id(13)));
        dbError("23514", () -> jdbc.update("INSERT INTO standard_exercise(id,name) VALUES (?, E' \\t\\n')", id(3)));
        dbError("23514", () -> jdbc.update("INSERT INTO manufacturer(id,name) VALUES (?, ' ')", id(3)));
        dbError("23514", () -> jdbc.update("INSERT INTO muscle(id,group_code,name) VALUES (?,'CORE',' ')", id(13)));
        dbError("23514", () -> jdbc.update("INSERT INTO exercise_alias VALUES (?, ' ', 'blank')", id(1)));
        dbError("23514", () -> jdbc.update("INSERT INTO exercise_alias VALUES (?, 'alias', ' ')", id(1)));
        dbError("23503", () -> jdbc.update("INSERT INTO exercise_alias VALUES (?, 'missing', 'missing')", id(99)));
        dbError("23503", () -> jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'PRIMARY')", id(99), id(11)));
        dbError("23503", () -> jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,'PRIMARY')", id(1), id(99)));
        dbError("23503", () -> jdbc.update("DELETE FROM standard_exercise WHERE id=?", id(1)));
        dbError("23503", () -> jdbc.update("DELETE FROM muscle WHERE id=?", id(11)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_alias", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_muscle", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT is_active AND created_at=updated_at FROM standard_exercise WHERE id=?", Boolean.class, id(2))).isTrue();
    }

    @Test void searchUsesTwoReadOnlyJpaQueriesAndOneConsistentSnapshot() throws Exception {
        exercise(1, "벤치", true); alias(1, "bench");
        var independent = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        var selects = new CopyOnWriteArrayList<String>(); var changed = new AtomicBoolean();
        WorkoutReadFixtures.observer = event -> {
            if (!catalogSql(event.sql())) return;
            if (event.before()) {
                selects.add(event.sql());
                assertThat(TransactionSynchronizationManager.hasResource(app.getBean(EntityManagerFactory.class))).isTrue();
                try {
                    assertThat(event.connection().isReadOnly()).isTrue();
                    assertThat(event.connection().getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ);
                } catch (SQLException failure) { throw new AssertionError(failure); }
            } else if (!event.sql().contains("exercise_alias") && changed.compareAndSet(false, true)) {
                independent.update("UPDATE standard_exercise SET is_active=false WHERE id=?", id(1));
                independent.update("DELETE FROM exercise_alias WHERE standard_exercise_id=?", id(1));
            }
        };
        var data = success(browser.get(SEARCH));
        WorkoutReadFixtures.observer = null;
        assertThat(changed).isTrue(); assertThat(selects).hasSize(2);
        assertThat(data.get("exercises").size()).isEqualTo(1);
        assertThat(data.get("exercises").get(0).get("aliases")).isEqualTo(JSON.readTree("[\"bench\"]"));
        assertThat(success(browser.get(SEARCH)).get("exercises").isEmpty()).isTrue();
    }

    @ParameterizedTest @ValueSource(strings = {SEARCH, MANUFACTURERS})
    void anonymousAndExpiredSessionsAreRejected(String path) throws Exception {
        try (var anonymous = new Browser()) { error(anonymous.get(path), 401, "AUTHENTICATION_REQUIRED"); }
        EmailOtpFixtures.CLOCK.advance(Duration.ofDays(91));
        error(browser.get(path), 401, "AUTHENTICATION_REQUIRED");
    }

    @ParameterizedTest @ValueSource(strings = {SEARCH, MANUFACTURERS})
    void queryAndGetBodiesAreRejectedBeforeCatalogSqlAndNoManagementApiExists(String path) throws Exception {
        var queries = new CopyOnWriteArrayList<String>();
        WorkoutReadFixtures.observer = event -> { if (event.before() && catalogSql(event.sql())) queries.add(event.sql()); };
        error(browser.get(path + "?q=bench"), 400, "INVALID_REQUEST");
        error(browser.send("GET", path, "{}", null), 400, "INVALID_REQUEST");
        assertThat(queries).isEmpty(); WorkoutReadFixtures.observer = null;
        assertThat(browser.send("POST", path, "{}", browser.csrf()).statusCode()).isEqualTo(405);
    }

    @ParameterizedTest @ValueSource(strings = {SEARCH, MANUFACTURERS})
    void databaseAndSecurityFailuresKeepExistingReadErrorContract(String path) throws Exception {
        WorkoutReadFixtures.observer = event -> { if (event.before() && catalogSql(event.sql())) throw new DataAccessResourceFailureException("private connection detail"); };
        error(browser.get(path), 503, "WORKOUT_DATA_UNAVAILABLE");
        WorkoutReadFixtures.observer = event -> { if (event.before() && catalogSql(event.sql())) throw new DataIntegrityViolationException("private SQL detail"); };
        error(browser.get(path), 500, "WORKOUT_DATA_READ_FAILED");
        WorkoutReadFixtures.observer = event -> { if (event.before() && catalogSql(event.sql())) throw new AccessDeniedException("private security detail"); };
        error(browser.get(path), 403, "ACCESS_DENIED");
    }

    private static boolean catalogSql(String sql) {
        String value = sql.toLowerCase(Locale.ROOT);
        return value.startsWith("select ") && (value.contains("from standard_exercise") || value.contains("from exercise_alias") || value.contains("from manufacturer"));
    }
    private static void exercise(int id, String name, boolean active) {
        app.getBean(StandardExerciseRepository.class).saveAndFlush(StandardExercise.create(id(id), name, active, NOW));
    }
    private static void alias(int exercise, String name) {
        app.getBean(ExerciseAliasRepository.class).saveAndFlush(ExerciseAlias.create(id(exercise), name));
    }
    private static void manufacturer(int id, String name, boolean active) {
        app.getBean(ManufacturerRepository.class).saveAndFlush(Manufacturer.create(id(id), name, active, NOW));
    }
    private static UUID id(int value) { return new UUID(0, value); }
    private static void dbError(String state, Runnable action) {
        var failure = catchThrowable(action::run);
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(((SQLException) ((DataIntegrityViolationException) failure).getMostSpecificCause()).getSQLState()).isEqualTo(state);
    }
    private static JsonNode success(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200); headers(response); return JSON.readTree(response.body());
    }
    private static void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status); headers(response);
        assertThat(JSON.readTree(response.body())).isEqualTo(JSON.readTree(JSON.writeValueAsString(Map.of("code", code))));
    }
    private static void headers(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow()).version()).isEqualTo(4);
    }

    static final class Browser implements AutoCloseable {
        final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        void login() throws Exception {
            String email = UUID.randomUUID() + "@example.test", token = csrf();
            var issued = send("POST", "/api/v1/auth/email-otp/request", JSON.writeValueAsString(Map.of("email", email)), token);
            assertThat(issued.statusCode()).isEqualTo(202);
            var message = app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString()));
            assertThat(send("POST", "/api/v1/auth/email-otp/verify", JSON.writeValueAsString(Map.of("email", email, "challengeId", message.issuanceId(), "code", message.code())), token).statusCode()).isEqualTo(204);
        }
        String csrf() throws Exception { return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString(); }
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        HttpResponse<String> send(String method, String path, String body, String csrf) throws Exception {
            String origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
            var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(15));
            if (csrf != null) request.header("X-CSRF-TOKEN", csrf);
            if (body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { client.close(); }
    }
}
