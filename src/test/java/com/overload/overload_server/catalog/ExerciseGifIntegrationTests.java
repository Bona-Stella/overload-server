package com.overload.overload_server.catalog;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.testfixture.EmailOtpFixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres")
@Testcontainers
class ExerciseGifIntegrationTests {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte[] GIF = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
    private static final String REPRESENTATIVE = "nZZZy9m"; // Synthetic bytes in this fixture.
    private static final String MEDIA = "/media/exercises/v1/";
    private static final String REQUEST = MEDIA + REPRESENTATIVE + ".gif";
    private static final String AUTH = ExerciseGifAuthorizationController.PATH;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Container
    final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_gif_isolated_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    @TempDir Path temporary;

    @Test void nginxServesOnlyTheAuthorizedCurrentRepresentativeAndFailsClosed() throws Exception {
        EmailOtpFixtures.CLOCK.reset();
        String token = HexFormat.of().formatHex(randomBytes());
        Path gifs = Files.createDirectory(temporary.resolve("synthetic"));
        Path env = Files.createFile(temporary.resolve("empty.env"));
        for (String id : List.of(REPRESENTATIVE, "Other01", "Ghost01")) Files.write(gifs.resolve(id + ".gif"), GIF);
        Files.writeString(gifs.resolve("private.txt"), "must never be served");
        try (var app = startBackend(true, token, 0)) {
            int backendPort = port(app);
            String backend = "http://127.0.0.1:" + backendPort;
            var jdbc = app.getBean(JdbcTemplate.class);
            UUID exercise = seed(jdbc);
            try (var nginx = new LocalNginx(gifs, env, backendPort, token)) {
                nginx.start();
                String origin = nginx.origin();
                var sender = app.getBean(EmailOtpFixtures.FakeSender.class);
                try (var member = new Browser(); var denied = new Browser(); var anonymous = new Browser()) {
                    anonymous.assertGif(origin, REQUEST, 401);
                    UUID userId = member.login(origin, sender);
                    denied.login(origin, sender);
                    denied.assertGif(origin, REQUEST, 403);
                    assertThat(denied.get(origin, "/api/v1/standard-exercises/" + exercise).statusCode()).isEqualTo(200);
                    assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_gif_access", Integer.class)).isZero();
                    changeGrant(userId, true);
                    var response = member.assertGif(origin, REQUEST, 200);
                    assertThat(response.headers().firstValue("Content-Type")).contains("image/gif");
                    assertThat(response.body()).isEqualTo(GIF);
                    verifyChrome(origin, member);
                    assertThat(member.send(origin, "HEAD", REQUEST, null, Map.of()).statusCode()).isEqualTo(200);
                    var badHeader = Map.of(ExerciseGifAuthorizationController.TOKEN_HEADER, "0".repeat(64),
                            ExerciseGifAuthorizationController.SOURCE_HEADER, "Other01");
                    member.assertGif(origin, MEDIA + "Other01.gif", 403);
                    assertThat(member.send(origin, "GET", REQUEST, null, badHeader).body()).isEqualTo(GIF);
                    member.assertGif(origin, MEDIA + "Ghost01.gif", 403);
                    member.assertGif(origin, MEDIA + "nzzzy9m.gif", 403);
                    assertThat(member.send(backend, "GET", AUTH, null, badHeader).statusCode()).isEqualTo(403);
                    assertThat(member.send(backend, "GET", AUTH, null, Map.of()).statusCode()).isEqualTo(403);
                    assertThat(member.send(backend, "GET", AUTH, null, authHeaders(token, "Other01")).statusCode()).isEqualTo(404);
                    assertThat(member.send(backend, "GET", AUTH, null, authHeaders(token, REPRESENTATIVE)).statusCode()).isEqualTo(204);
                    assertThat(member.send(origin, "GET", "/__gif_authorize", null, authHeaders(token, REPRESENTATIVE)).statusCode()).isEqualTo(403);
                    for (String path : List.of("/__gif_denied", AUTH, "/%69nternal/media/exercise-gif/authorize",
                            MEDIA, "/private.txt", MEDIA + "../v1/" + REPRESENTATIVE + ".gif",
                            MEDIA + "%2e%2e/v1/" + REPRESENTATIVE + ".gif", MEDIA + "%6eZZZy9m.gif",
                            "/media//exercises/v1/" + REPRESENTATIVE + ".gif", REQUEST + "?v=1", MEDIA + "private.txt")) {
                        assertThat(member.send(origin, "GET", path, null, authHeaders(token, REPRESENTATIVE)).statusCode())
                                .as(path).isEqualTo(404);
                    }
                    for (String flag : List.of("is_active", "muscle_targets_reviewed", "splits_reviewed")) {
                        jdbc.update("UPDATE standard_exercise SET " + flag + "=false WHERE id=?", exercise);
                        member.assertGif(origin, REQUEST, 403);
                        jdbc.update("UPDATE standard_exercise SET " + flag + "=true WHERE id=?", exercise);
                    }
                    jdbc.update("UPDATE exercise_source SET original_gif_reference='test-reference' WHERE source_exercise_id='Other01'");
                    member.assertGif(origin, REQUEST, 403);
                    member.assertGif(origin, MEDIA + "Other01.gif", 403);
                    jdbc.update("UPDATE exercise_source SET original_gif_reference='other-reference' WHERE source_exercise_id='Other01'");

                    changeGrant(userId, false);
                    member.assertGif(origin, REQUEST, 403);
                    changeGrant(userId, true);
                    member.assertGif(origin, REQUEST, 200);
                    assertThat(member.send(origin, "POST", "/api/v1/auth/logout", null,
                            Map.of("X-CSRF-TOKEN", member.csrf(origin))).statusCode()).isEqualTo(204);
                    member.assertGif(origin, REQUEST, 401);

                    UUID expiring = member.login(origin, sender);
                    changeGrant(expiring, true);
                    member.assertGif(origin, REQUEST, 200);
                    EmailOtpFixtures.CLOCK.advance(Duration.ofDays(91));
                    member.assertGif(origin, REQUEST, 401);
                    EmailOtpFixtures.CLOCK.reset();
                    UUID last = member.login(origin, sender);
                    changeGrant(last, true);
                    member.assertGif(origin, REQUEST, 200);
                    app.close();
                    member.assertGif(origin, REQUEST, 500);
                    var wrongUpstream = HttpServer.create(new InetSocketAddress("127.0.0.1", backendPort), 0);
                    wrongUpstream.createContext("/", exchange -> {
                        try (exchange) { exchange.sendResponseHeaders(200, -1); }
                    });
                    wrongUpstream.start();
                    try {
                        member.assertGif(origin, REQUEST, 404);
                    } finally {
                        wrongUpstream.stop(0);
                    }
                    try (var disabled = startBackend(false, token, backendPort)) {
                        assertThat(port(disabled)).isEqualTo(backendPort);
                        assertThat(member.get(origin, "/api/v1/auth/session").statusCode()).isEqualTo(200);
                        member.assertGif(origin, REQUEST, 403);
                    }
                }
            }
        } finally {
            EmailOtpFixtures.CLOCK.reset();
        }
    }

    private ConfigurableApplicationContext startBackend(boolean enabled, String token, int port) {
        return new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class)
                .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(), "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(), "--server.address=127.0.0.1", "--server.port=" + port,
                        "--OTP_HMAC_KEY=" + Base64.getEncoder().encodeToString(randomBytes()), "--OTP_EMAIL_ENABLED=false",
                        "--GOOGLE_OIDC_ENABLED=false", "--GOOGLE_REAUTH_ENABLED=false", "--spring.session.jdbc.cleanup-cron=-",
                        "--logging.level.root=WARN", "--spring.main.banner-mode=off",
                        "--overload.gif.serving-enabled=" + enabled, "--overload.gif.proxy-token=" + token);
    }

    private void changeGrant(UUID userId, boolean grant) throws Exception {
        var command = new ArrayList<>(List.of("docker", "exec", "-i", postgres.getContainerId(),
                "psql", "-X", "-U", postgres.getUsername(), "-d", postgres.getDatabaseName(),
                "-v", "user_id=" + userId, "-v", "grant_access=" + grant));
        var process = new ProcessBuilder(command).redirectInput(Path.of("deploy/local/gif-access.sql").toFile())
                .redirectErrorStream(true).redirectOutput(temporary.resolve("grant.log").toFile()).start();
        checkProcess(process, temporary.resolve("grant.log"));
    }

    private void verifyChrome(String origin, Browser member) throws Exception {
        String chrome = System.getenv("OVERLOAD_GIF_TEST_CHROME");
        if (chrome == null || chrome.isBlank()) return;
        Path log = temporary.resolve("chrome.log");
        var process = new ProcessBuilder("node", "src/test/resources/verify-local-gif-chrome.mjs")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (var input = process.getOutputStream()) {
            input.write(JSON.writeValueAsBytes(Map.of("chrome", chrome, "profile", temporary.resolve("chrome").toString(),
                    "origin", origin, "path", REQUEST, "cookie", member.sessionCookie(origin),
                    "gif", Base64.getEncoder().encodeToString(GIF))));
        }
        checkProcess(process, log);
    }

    private static UUID seed(JdbcTemplate jdbc) {
        UUID exercise = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO standard_exercise(id,name,muscle_targets_reviewed,splits_reviewed,representative_gif_key)
                VALUES (?,'검증용 운동',true,true,'test-reference')
                """, exercise);
        jdbc.update("""
                INSERT INTO exercise_source VALUES ('EXERCISEDB_V1',? ,?,'Test GIF','test-reference'),
                    ('EXERCISEDB_V1','Other01',?,'Other GIF','other-reference')
                """, REPRESENTATIVE, exercise, exercise);
        return exercise;
    }

    private static int port(ConfigurableApplicationContext app) {
        return ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static Map<String, String> authHeaders(String token, String id) {
        return Map.of(ExerciseGifAuthorizationController.TOKEN_HEADER, token, ExerciseGifAuthorizationController.SOURCE_HEADER, id);
    }

    private static String checkProcess(Process process, Path log) throws Exception {
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor();
            throw new IllegalStateException("Isolated Docker command timed out");
        }
        String output = Files.readString(log);
        assertThat(process.exitValue()).as(output).isZero();
        return output.trim();
    }

    private final class LocalNginx implements AutoCloseable {
        private final String project = "overload-gif-test-" + UUID.randomUUID();
        private final List<String> compose;
        private final Map<String, String> env;

        LocalNginx(Path gifs, Path envFile, int backendPort, String token) {
            compose = List.of("docker", "compose", "--env-file", envFile.toString(), "-p", project,
                    "-f", Path.of("deploy/local/compose.yml").toAbsolutePath().toString());
            env = Map.of("OVERLOAD_GIF_DIR", gifs.toString(), "OVERLOAD_GIF_NGINX_PORT", "0",
                    "OVERLOAD_GIF_BACKEND_UPSTREAM", "host.docker.internal:" + backendPort, "OVERLOAD_GIF_PROXY_TOKEN", token);
        }

        void start() throws Exception { command("up", "-d", "--pull", "never", "--wait", "--wait-timeout", "30"); }
        String origin() throws Exception { return "http://" + command("port", "nginx", "8080"); }
        @Override public void close() throws Exception { command("down", "--timeout", "5"); }

        private String command(String... args) throws Exception {
            var invocation = new ArrayList<>(compose);
            invocation.addAll(List.of(args));
            Path log = temporary.resolve("compose-" + args[0] + ".log");
            var builder = new ProcessBuilder(invocation).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(env);
            return checkProcess(builder.start(), log);
        }
    }

    private static final class Browser implements AutoCloseable {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder()
                .cookieHandler(cookies).connectTimeout(Duration.ofSeconds(8)).build();

        Map<String, String> sessionCookie(String origin) {
            var cookie = cookies.getCookieStore().get(URI.create(origin)).stream()
                    .filter(value -> value.getName().equals("SESSION")).findFirst().orElseThrow();
            return Map.of("name", cookie.getName(), "value", cookie.getValue());
        }

        UUID login(String origin, EmailOtpFixtures.FakeSender sender) throws Exception {
            String email = UUID.randomUUID() + "@example.test";
            var csrf = Map.of("X-CSRF-TOKEN", csrf(origin));
            var issued = send(origin, "POST", "/api/v1/auth/email-otp/request", Map.of("email", email), csrf);
            assertThat(issued.statusCode()).isEqualTo(202);
            var challenge = UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString());
            var message = sender.messages.get(challenge);
            assertThat(send(origin, "POST", "/api/v1/auth/email-otp/verify",
                    Map.of("email", email, "challengeId", challenge, "code", message.code()), csrf).statusCode()).isEqualTo(204);
            return UUID.fromString(JSON.readTree(get(origin, "/api/v1/auth/session").body()).get("userId").asString());
        }

        String csrf(String origin) throws Exception {
            return JSON.readTree(get(origin, "/api/v1/auth/csrf").body()).get("token").asString();
        }

        HttpResponse<byte[]> assertGif(String origin, String path, int status) throws Exception {
            var response = get(origin, path);
            assertThat(response.statusCode()).as(path + " response: " + new String(response.body(), StandardCharsets.UTF_8)).isEqualTo(status);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            if (status != 200) assertThat(response.body()).isNotEqualTo(GIF);
            return response;
        }

        HttpResponse<byte[]> get(String origin, String path) throws Exception {
            return send(origin, "GET", path, null, Map.of());
        }

        HttpResponse<byte[]> send(String origin, String method, String path, Object body, Map<String, String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(12));
            headers.forEach(request::header);
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        @Override public void close() { http.close(); }
    }
}
