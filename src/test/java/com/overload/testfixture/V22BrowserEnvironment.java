package com.overload.testfixture;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.overload.overload_server.OverloadServerApplication;
import org.flywaydb.core.Flyway;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

/** Explicit test-classpath entry point. No production configuration or developer data is accepted. */
public final class V22BrowserEnvironment implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String DATABASE = "overload_v22_isolated";
    public static final String RUN_LABEL = "com.overload.verification.run";
    private final UUID runId;
    private final UUID instanceId = UUID.randomUUID();
    private final Path directory;
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private PostgreSQLContainer postgres;
    private ConfigurableApplicationContext app;
    private V22BrowserControl control;
    private volatile boolean closed;
    private boolean ready;

    private V22BrowserEnvironment(UUID runId) throws Exception {
        this.runId = runId;
        directory = Path.of("build", "verification-v22", runId.toString()).toAbsolutePath().normalize();
        Files.createDirectories(directory.getParent());
        Files.createDirectory(directory); // Never overwrite a prior run, including failed runs.
        evidence.put("runId", runId.toString());
        evidence.put("startedAt", Instant.now().toString());
        evidence.put("backendPid", ProcessHandle.current().pid());
    }

    public static V22BrowserEnvironment start(UUID runId, boolean failBeforeReady) throws Exception {
        return start(runId, failBeforeReady, new Class<?>[0]);
    }

    static V22BrowserEnvironment start(UUID runId, boolean failBeforeReady, Class<?>... additionalSources) throws Exception {
        // The local HTTP cookie profile is safe only with the test resource overlay.
        var overlay = V22BrowserEnvironment.class.getClassLoader().getResource("application-local.yaml");
        require(overlay != null && overlay.toString().contains("/build/resources/test/"), "Test resource overlay required");
        require(System.getenv().keySet().stream().noneMatch(k -> k.matches("^(SPRING_|OTP_|RESEND_|GOOGLE_|AUTH_|OVERLOAD_).*")), "Clean child environment required");
        var environment = new V22BrowserEnvironment(runId);
        try {
            environment.startBackend(additionalSources);
            if (failBeforeReady) throw new IllegalStateException("Requested pre-ready failure");
            environment.evidence.put("readyAt", Instant.now().toString());
            environment.ready = true;
            environment.write("ready.json", environment.evidence);
            return environment;
        } catch (Exception | Error failure) {
            environment.evidence.put("failureType", failure.getClass().getSimpleName());
            environment.close();
            throw failure;
        }
    }

    private void startBackend(Class<?>... additionalSources) throws Exception {
        postgres = new PostgreSQLContainer("postgres:18.6-alpine")
                .withDatabaseName(DATABASE).withUsername("overload_test").withPassword(UUID.randomUUID().toString())
                .withReuse(false).withLabel(RUN_LABEL, runId.toString())
                .withTmpFs(Map.of("/var/lib/postgresql", "rw"))
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))));
        postgres.start();
        evidence.put("containerId", postgres.getContainerId());
        var info = postgres.getContainerInfo();
        require(runId.toString().equals(info.getConfig().getLabels().get(RUN_LABEL)), "Run label mismatch");
        evidence.put("containerCreatedAt", info.getCreated());
        evidence.put("containerRunId", info.getConfig().getLabels().get(RUN_LABEL));
        require(info.getHostConfig().getBinds() == null || info.getHostConfig().getBinds().length == 0, "Host volume prohibited");
        require(info.getHostConfig().getTmpFs().containsKey("/var/lib/postgresql"), "Fresh tmpfs required");
        var binding = info.getNetworkSettings().getPorts().getBindings().get(ExposedPort.tcp(5432));
        require(binding.length == 1 && "127.0.0.1".equals(binding[0].getHostIp()), "Database must bind loopback");
        int port = postgres.getMappedPort(5432);
        require(port != 5432 && port != 5433, "Dedicated random database port required");
        String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + port + "/" + DATABASE;
        var direct = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, postgres.getUsername(), postgres.getPassword()));
        require(DATABASE.equals(direct.queryForObject("SELECT current_database()", String.class)), "Database identity mismatch");
        require(direct.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'", Integer.class) == 0, "Database must start empty");
        evidence.put("jdbcUrl", jdbcUrl);
        evidence.put("postgresVersion", direct.queryForObject("SHOW server_version", String.class));
        evidence.put("database", DATABASE);
        evidence.put("databasePort", port);
        evidence.put("freshDatabase", true);
        evidence.put("loopbackDatabase", true);
        evidence.put("tmpfsOnly", true);

        var key = new byte[32]; new SecureRandom().nextBytes(key);
        app = new SpringApplicationBuilder(OverloadServerApplication.class, WorkoutBrowserEnvironment.TestEmail.class)
                .sources(additionalSources)
                .initializers(context -> {
                    var identity = new FilterRegistrationBean<Filter>((request, response, chain) -> {
                        var incoming = (HttpServletRequest) request;
                        var http = (HttpServletResponse) response;
                        if (!"POST".equals(incoming.getMethod()) || incoming.getHeader("Origin") != null) {
                            http.setStatus(403);
                            return;
                        }
                        identityHeaders("http://127.0.0.1:" + request.getLocalPort()).forEach(http::setHeader);
                        http.setStatus(204);
                    });
                    identity.addUrlPatterns("/__v22_identity");
                    identity.setOrder(Integer.MIN_VALUE);
                    context.getBeanFactory().registerSingleton("v22IdentityProbe", identity);
                })
                .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
                        "--spring.datasource.url=" + jdbcUrl, "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(), "--server.address=127.0.0.1", "--server.port=0",
                        "--OTP_HMAC_KEY=" + Base64.getEncoder().encodeToString(key), "--OTP_EMAIL_ENABLED=false",
                        "--GOOGLE_OIDC_ENABLED=false", "--GOOGLE_REAUTH_ENABLED=false", "--spring.session.jdbc.cleanup-cron=-",
                        "--logging.level.root=OFF", "--spring.main.banner-mode=off", "--spring.main.log-startup-info=false");
        MixedPersistenceAssertions.verify(app);
        var jdbc = app.getBean(JdbcTemplate.class);
        try (var connection = app.getBean(javax.sql.DataSource.class).getConnection()) {
            require(jdbcUrl.equals(connection.getMetaData().getURL()), "Application datasource mismatch");
        }
        app.getBean(Flyway.class).validate();
        var versions = jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        require(versions.equals(java.util.stream.IntStream.rangeClosed(1, 29).mapToObj(Integer::toString).toList()), "V1 through V29 required");
        require(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class) == 0, "Failed migration");
        require(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class) == 0, "Fresh users required");
        require(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions", Integer.class) == 0, "Personal seed prohibited");
        for (var table : List.of("spring_session", "spring_session_attributes", "routine_plan_history", "weekly_schedule_versions")) {
            require(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table), "Required table missing");
        }
        for (var column : List.of("cardio_results.measurement", "performed_sets.bodyweight_load")) {
            var parts = column.split("\\.");
            require("jsonb".equals(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name=? AND column_name=?", String.class, parts[0], parts[1])), "V21/V22 JSONB required");
        }
        require(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname IN ('standard_exercise_joint_type_check','cardio_measurement_shape','performed_sets_bodyweight_load_check','workout_start_schedule_scope')", Integer.class) == 4, "V18/V21/V22/V24 constraints required");
        require(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname='weekly_schedule_versions_effective_from_check'",Integer.class)==0,"V24 removes the old Monday restriction");
        evidence.put("initialUsers", 0);
        evidence.put("migrations", versions.size());
        evidence.put("migrationVersions", versions);
        evidence.put("schemaValidated", true);
        evidence.put("noDeveloperEnv", true);
        evidence.put("springSessionTables", true);
        evidence.put("fakeEmailSender", app.getBean(com.overload.overload_server.auth.email.OtpEmailSender.class) instanceof EmailOtpFixtures.FakeSender);
        evidence.put("externalAuthDisabled", true);
        String origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
        evidence.put("backendOrigin", origin);
        seedCatalog(jdbc);
        smoke(origin);
        require(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class) == 1, "One normal OTP account required");
        require(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions", Integer.class) == 0, "Personal seed prohibited");
        control = new V22BrowserControl(app.getBean(EmailOtpFixtures.FakeSender.class), jdbc, identityHeaders(origin));
        evidence.put("browserControlOrigin", control.origin());
        evidence.put("instanceId", instanceId.toString());
    }

    private Map<String, String> identityHeaders(String origin) {
        return Map.of("X-V22-Run", runId.toString(), "X-V22-Instance", instanceId.toString(),
                "X-V22-Pid", Long.toString(ProcessHandle.current().pid()), "X-V22-Backend", origin,
                "Cache-Control", "no-store");
    }

    private void seedCatalog(JdbcTemplate jdbc) {
        UUID weight = UUID.randomUUID(), body = UUID.randomUUID(), primary = UUID.randomUUID(), secondary = UUID.randomUUID(), manufacturer = UUID.randomUUID();
        jdbc.update("INSERT INTO standard_exercise(id,name,joint_type) VALUES (?,'V22 synthetic weight','COMPOUND'),(?,'V22 synthetic bodyweight','COMPOUND')", weight, body);
        jdbc.update("INSERT INTO muscle(id,group_code,name) VALUES (?,'CHEST','V22 synthetic primary'),(?,'ARMS','V22 synthetic secondary')", primary, secondary);
        for (UUID exercise : List.of(weight, body)) {
            jdbc.update("INSERT INTO exercise_muscle(standard_exercise_id,muscle_id,role) VALUES (?,?,'PRIMARY'),(?,?,'SECONDARY')", exercise, primary, exercise, secondary);
        }
        jdbc.update("INSERT INTO manufacturer(id,name) VALUES (?,'V22 synthetic manufacturer')", manufacturer);
        evidence.put("catalog", Map.of("weightExerciseId", weight.toString(), "bodyweightExerciseId", body.toString(), "manufacturerId", manufacturer.toString(),
                "cardioKinds", List.of("TREADMILL", "BIKE", "OUTDOOR", "STAIRS", "OTHER")));
    }

    private void smoke(String origin) throws Exception {
        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build()) {
            var csrf = JSON.readTree(send(client, origin, "/api/v1/auth/csrf", "GET", null, null, 200).body());
            send(client, origin, "/api/v1/auth/session", "GET", null, null, 401);
            String email = "v22-" + runId + "@example.invalid";
            var issued = JSON.readTree(send(client, origin, "/api/v1/auth/email-otp/request", "POST", Map.of("email", email), csrf, 202).body());
            var message = app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(issued.get("challengeId").asString()));
            require(message != null, "Fake delivery missing");
            send(client, origin, "/api/v1/auth/email-otp/verify", "POST", Map.of("email", email, "challengeId", message.issuanceId().toString(), "code", message.code()), csrf, 204);
            send(client, origin, "/api/v1/auth/session", "GET", null, null, 200);
            send(client, origin, "/api/v1/auth/csrf", "GET", null, null, 200);
            var catalog = JSON.readTree(send(client, origin, "/api/v1/standard-exercises/search-data", "GET", null, null, 200).body());
            require(catalog.get("schemaVersion").asInt() == 1 && catalog.get("exercises").size() == 2, "Current catalog response required");
            send(client, origin, "/api/v1/workout-settings", "GET", null, null, 200);
            send(client, origin, "/api/v1/health", "GET", null, null, 200);
        }
        evidence.put("accountFlow", "EMAIL_OTP");
        evidence.put("preparedUsers", 1);
        evidence.put("httpSmokePassed", true);
    }

    private static HttpResponse<String> send(HttpClient client, String origin, String path, String method, Object body,
                                              tools.jackson.databind.JsonNode csrf, int expected) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
        if (csrf != null) request.header(csrf.get("headerName").asString(), csrf.get("token").asString());
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == expected, "HTTP smoke failed: " + method + " " + path + " status=" + response.statusCode());
        return response;
    }

    public Map<String, Object> evidence() { return Map.copyOf(evidence); }
    ConfigurableApplicationContext context() { return app; }
    public Path directory() { return directory; }
    private void write(String name, Object value) throws Exception {
        Files.writeString(directory.resolve(name), JSON.writeValueAsString(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }

    @Override public synchronized void close() throws Exception {
        if (closed) return;
        closed = true;
        try { if (control != null) control.close(); if (app != null) app.close(); }
        finally {
            try {
                // Explicit archive shutdown only: never dispose a user-approved review container.
                // A verified backup/restore and local shutdown agent establish this marker first.
                var preservation = directory.resolve("preserve-review-db");
                boolean preserve = Files.exists(preservation) && Files.readString(preservation).trim().equals(runId.toString());
                if (preserve) evidence.put("containerPreserved", true);
                else if (postgres != null) postgres.close();
            }
            finally {
                Files.deleteIfExists(directory.resolve("ready.json"));
                evidence.remove("instanceId");
                evidence.put("status", ready ? "STOPPED" : "FAILED");
                evidence.put("stoppedAt", Instant.now().toString());
                evidence.put("applicationStopped", app == null || !app.isActive());
                evidence.put("containerStopped", postgres == null || !postgres.isRunning());
                write("result.json", evidence);
            }
        }
    }

    public static void main(String[] args) {
        try {
            require(args.length == 1, "Supply one new verification run UUID");
            try (var environment = start(UUID.fromString(args[0]), false)) {
                var hook = new Thread(() -> { try { environment.close(); } catch (Exception ignored) { System.err.println("V22 cleanup failed"); } });
                Runtime.getRuntime().addShutdownHook(hook);
                System.out.println("V22_READY run=" + environment.runId + " origin=" + environment.evidence.get("backendOrigin"));
                try {
                    // Bounded lease accommodates the explicitly requested full online browser scenarios.
                    long deadline = System.nanoTime() + Duration.ofHours(2).toNanos();
                    while (!environment.closed && System.nanoTime() < deadline) {
                        var stop = environment.directory.resolve("stop");
                        if (Files.exists(stop) && Files.readString(stop).trim().equals(environment.runId.toString())) break;
                        Thread.sleep(150);
                    }
                } finally { try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { } }
            }
        } catch (Exception failure) {
            System.err.println("V22 environment failed: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
