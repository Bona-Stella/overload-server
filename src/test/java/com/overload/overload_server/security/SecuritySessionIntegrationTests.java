package com.overload.overload_server.security;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.testfixture.SecurityFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.overload.testfixture.SecurityFixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
@Testcontainers
class SecuritySessionIntegrationTests {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_security_test").withUsername("overload_test")
			.withPassword(UUID.randomUUID().toString());

	@TempDir
	static Path temporaryDirectory;
	static ConfigurableApplicationContext application;
	static JdbcTemplate jdbc;
	static final JsonMapper JSON = JsonMapper.builder().build();
	CookieManager cookies;
	HttpClient client;

	@BeforeAll
	static void start() {
		CLOCK.reset();
		application = startApplication(true, false);
		jdbc = application.getBean(JdbcTemplate.class);
		jdbc.update("INSERT INTO users (id) VALUES (?)", SecurityFixtures.USER_ID);
	}

	@BeforeEach
	void isolateSessionState() {
		CLOCK.reset();
		// This container belongs solely to this test class; never the Compose development database.
		jdbc.update("DELETE FROM spring_session");
		cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
	}

	@AfterAll
	static void stop() {
		if (application != null) {
			application.close();
		}
	}

	@AfterEach
	void closeClient() {
		if (client != null) {
			client.close();
		}
	}

	@Test
	void unauthenticatedPrivateApiReturnsJson401WithoutRedirectOrCredentialLogin() throws Exception {
		var response = request("GET", "/api/v1/private", null);
		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).isEqualTo("{\"code\":\"AUTHENTICATION_REQUIRED\"}");
		assertThat(response.headers().firstValue("Location")).isEmpty();
		assertThat(request("GET", "/login", null).statusCode()).isEqualTo(401);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
		assertThat(request("POST", "/api/v1/private", null).statusCode()).isEqualTo(401);
		// CSRF may create an anonymous session for unsafe requests; it must not become a long-lived login.
		assertThat(jdbc.queryForObject("SELECT principal_name IS NULL AND max_inactive_interval = 1800 FROM spring_session",
				Boolean.class)).isTrue();
	}

	@Test
	void healthContractPreservedWithSecurityAndCsrfEnabled() throws Exception {
		assertThat(request("GET", "/api/v1/health", null).body()).isEqualTo("{\"status\":\"UP\"}");
		assertThat(request("GET", "/api/v1/health", null).statusCode()).isEqualTo(200);
		assertThat(request("POST", "/api/v1/health", null).statusCode()).isEqualTo(405);
	}

	@Test
	void sessionSavedInPostgresReusedAndSessionFixationPrevented() throws Exception {
		var token = token();
		var anonymousId = sessionId();
		assertThat(jdbc.queryForObject("SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
				Integer.class, anonymousId)).isEqualTo(1800);
		var login = request("POST", "/__test__/login", token);
		assertThat(login.statusCode()).isEqualTo(204);
		assertThat(sessionId()).isNotEqualTo(anonymousId);
		assertThat(jdbc.queryForObject("SELECT principal_name FROM spring_session WHERE session_id = ?",
				String.class, sessionId())).isEqualTo(SecurityFixtures.USER_ID.toString());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes", Integer.class)).isGreaterThanOrEqualTo(2);
		assertThat(request("GET", "/__test__/me", null).statusCode()).isEqualTo(200);
		assertThat(requestWithCookie("GET", "/__test__/me", anonymousId).statusCode()).isEqualTo(401);
		assertPersistentCookie(login, 90 * 24 * 60 * 60, false);
	}

	@Test
	void csrfRequiredAndRotatedAfterAuthenticationAndLogout() throws Exception {
		var before = token();
		assertThat(request("POST", "/__test__/login", before).statusCode()).isEqualTo(204);
		assertThat(request("POST", "/__test__/mutate", null).statusCode()).isEqualTo(403);
		assertThat(request("POST", "/__test__/mutate", "invalid").body()).contains("CSRF_INVALID");
		assertThat(request("POST", "/__test__/mutate", before).statusCode()).isEqualTo(403);
		var after = token();
		assertThat(request("POST", "/__test__/mutate", after).statusCode()).isEqualTo(204);
		assertThat(request("GET", "/__test__/denied", null).body()).contains("ACCESS_DENIED");
		var oldId = sessionId();
		var logout = request("POST", "/api/v1/auth/logout", after);
		assertThat(logout.statusCode()).isEqualTo(204);
		assertThat(logout.headers().allValues("Set-Cookie").toString()).contains("Max-Age=0");
		assertThat(requestWithCookie("GET", "/__test__/me", oldId).statusCode()).isEqualTo(401);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
		var fresh = token();
		assertThat(request("POST", "/__test__/login", after).statusCode()).isEqualTo(401);
		assertThat(request("POST", "/__test__/login", fresh).statusCode()).isEqualTo(204);
	}

	@Test
	void absoluteExpiryIsNotSlidingAcrossActivityRotationOrReauthentication() throws Exception {
		login();
		var original = JSON.readTree(request("GET", "/__test__/me", null).body());
		CLOCK.advance(Duration.ofDays(89));
		assertThat(request("GET", "/__test__/me", null).statusCode()).isEqualTo(200);
		var rotated = request("POST", "/__test__/rotate", token());
		assertThat(rotated.statusCode()).isEqualTo(204);
		assertPersistentCookie(rotated, 24 * 60 * 60, false);
		var reauthenticated = request("POST", "/__test__/reauthenticate", token());
		assertThat(reauthenticated.statusCode()).isEqualTo(204);
		assertPersistentCookie(reauthenticated, 24 * 60 * 60, false);
		var current = JSON.readTree(request("GET", "/__test__/me", null).body());
		assertThat(current.get("loginStartedAt")).isEqualTo(original.get("loginStartedAt"));
		assertThat(current.get("absoluteExpiresAt")).isEqualTo(original.get("absoluteExpiresAt"));
		assertThat(jdbc.queryForObject("SELECT max_inactive_interval FROM spring_session WHERE session_id = ?",
				Integer.class, sessionId())).isLessThanOrEqualTo(24 * 60 * 60);
		CLOCK.advance(Duration.ofDays(1).minusMillis(1));
		assertThat(request("GET", "/__test__/me", null).statusCode()).isEqualTo(200);
		CLOCK.advance(Duration.ofMillis(1));
		assertThat(request("GET", "/__test__/me", null).statusCode()).isEqualTo(401);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session", Integer.class)).isZero();
	}

	@Test
	void recentReauthenticationUsesTheTenMinuteBoundaryWithoutChangingLoginDeadline() throws Exception {
		login();
		CLOCK.advance(Duration.ofMinutes(10));
		assertThat(request("GET", "/__test__/sensitive", null).statusCode()).isEqualTo(204);
		CLOCK.advance(Duration.ofMillis(1));
		assertThat(request("GET", "/__test__/sensitive", null).statusCode()).isEqualTo(403);
		var before = JSON.readTree(request("GET", "/__test__/me", null).body());
		assertThat(request("POST", "/__test__/reauthenticate", token()).statusCode()).isEqualTo(204);
		assertThat(request("GET", "/__test__/sensitive", null).statusCode()).isEqualTo(204);
		assertThat(JSON.readTree(request("GET", "/__test__/me", null).body()).get("absoluteExpiresAt"))
				.isEqualTo(before.get("absoluteExpiresAt"));
	}

	@Test
	void persistedSessionSurvivesServerRestart() throws Exception {
		login();
		var id = sessionId();
		application.close();
		application = startApplication(true, false);
		jdbc = application.getBean(JdbcTemplate.class);
		assertThat(request("GET", "/__test__/me", null).statusCode()).isEqualTo(200);
		assertThat(sessionId()).isEqualTo(id);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history", Integer.class)).isEqualTo(25);
	}

	@Test
	void productionHasNoTestLoginProviderAndEnforcesSecureCookieEvenWithLocalOverride() throws Exception {
		try (var production = startApplication(false, false)) {
			assertThat(production.getBean(RequestMappingHandlerMapping.class).getHandlerMethods().keySet().toString())
					.doesNotContain("__test__");
			assertThat(production.getBeansOfType(UserDetailsService.class)).isEmpty();
			assertThatThrownBy(() -> production.getBean(AuthenticationManager.class).authenticate(
					UsernamePasswordAuthenticationToken.unauthenticated("user", "invalid")))
					.isInstanceOf(ProviderNotFoundException.class);
			try (var browser = HttpClient.newHttpClient()) {
				var response = browser.send(HttpRequest.newBuilder(uri(production, false, "/api/v1/auth/csrf")).GET().build(),
						HttpResponse.BodyHandlers.ofString());
				assertThat(response.headers().allValues("Set-Cookie").toString()).contains("Secure", "HttpOnly", "SameSite=Lax")
						.doesNotContain("Max-Age=");
			}
		}
	}

	@Test
	void actualHttpsAuthenticationCookieIsSecureHttpOnlyPersistentAndReused() throws Exception {
		var password = UUID.randomUUID().toString();
		var keyStoreFile = temporaryDirectory.resolve("test-server.p12");
		var keytool = Path.of(System.getProperty("java.home"), "bin", "keytool" + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
		var process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "test", "-keyalg", "RSA",
				"-storetype", "PKCS12", "-keystore", keyStoreFile.toString(), "-storepass", password,
				"-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1,dns:localhost", "-validity", "2", "-noprompt")
				.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		try {
			assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
			assertThat(process.exitValue()).isZero();
		} finally {
			if (process.isAlive()) {
				process.destroyForcibly().waitFor();
			}
		}
		var keys = KeyStore.getInstance("PKCS12");
		try (var input = Files.newInputStream(keyStoreFile)) {
			keys.load(input, password.toCharArray());
		}
		var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trust.init(keys);
		var tls = SSLContext.getInstance("TLS");
		tls.init(null, trust.getTrustManagers(), null);
		try (var https = startApplication(true, true, "--server.ssl.key-store=" + keyStoreFile.toUri(),
				"--server.ssl.key-store-password=" + password, "--server.ssl.key-store-type=PKCS12")) {
			var jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
			try (var browser = HttpClient.newBuilder().sslContext(tls).cookieHandler(jar).build()) {
				var csrf = browser.send(HttpRequest.newBuilder(uri(https, true, "/api/v1/auth/csrf")).GET().build(),
						HttpResponse.BodyHandlers.ofString());
				var response = browser.send(HttpRequest.newBuilder(uri(https, true, "/__test__/login"))
						.header("X-CSRF-TOKEN", JSON.readTree(csrf.body()).get("token").asString())
						.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
				assertThat(response.statusCode()).isEqualTo(204);
				assertPersistentCookie(response, 90 * 24 * 60 * 60, true);
				assertThat(browser.send(HttpRequest.newBuilder(uri(https, true, "/__test__/me")).GET().build(),
						HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
			}
		}
	}

	@Test
	void flywayUsesTheExactOfficialVersionedPostgresSessionSchema() throws Exception {
		String official;
		String migration;
		try (var input = getClass().getResourceAsStream("/org/springframework/session/jdbc/schema-postgresql.sql")) {
			official = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		try (var input = getClass().getResourceAsStream("/db/migration/V3__create_spring_session.sql")) {
			migration = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
		assertThat(migration.replaceAll("(?m)^--.*$", "").replaceAll("\\s", ""))
				.isEqualTo(official.replaceAll("\\s", ""));
		assertThat(application.getEnvironment().getProperty("spring.session.jdbc.initialize-schema")).isEqualTo("never");
	}

	private void login() throws Exception {
		assertThat(request("POST", "/__test__/login", token()).statusCode()).isEqualTo(204);
	}

	private String token() throws Exception {
		var response = request("GET", "/api/v1/auth/csrf", null);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
		return JSON.readTree(response.body()).get("token").asString();
	}

	private HttpResponse<String> request(String method, String path, String token) throws Exception {
		var builder = HttpRequest.newBuilder(uri(application, false, path)).timeout(Duration.ofSeconds(10));
		if (token != null) {
			builder.header("X-CSRF-TOKEN", token);
		}
		return client.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> requestWithCookie(String method, String path, String id) throws Exception {
		try (var browser = HttpClient.newHttpClient()) {
			return browser.send(HttpRequest.newBuilder(uri(application, false, path)).header("Cookie", "SESSION=" + id)
					.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
		}
	}

	private String sessionId() {
		return cookies.getCookieStore().getCookies().stream().filter(cookie -> "SESSION".equals(cookie.getName()))
				.findFirst().orElseThrow().getValue();
	}

	private static void assertPersistentCookie(HttpResponse<String> response, int maximumSeconds, boolean secure) {
		var value = response.headers().allValues("Set-Cookie").stream().filter(header -> header.startsWith("SESSION="))
				.findFirst().orElseThrow();
		assertThat(value).contains("HttpOnly", "SameSite=Lax", "Path=/", "Expires=");
		assertThat(value.contains("Secure")).isEqualTo(secure);
		var matcher = java.util.regex.Pattern.compile("Max-Age=(\\d+)").matcher(value);
		assertThat(matcher.find()).isTrue();
		assertThat(Integer.parseInt(matcher.group(1))).isPositive().isLessThanOrEqualTo(maximumSeconds);
	}

	private static URI uri(ConfigurableApplicationContext context, boolean https, String path) {
		return URI.create((https ? "https" : "http") + "://127.0.0.1:"
				+ ((WebServerApplicationContext) context).getWebServer().getPort() + path);
	}

	private static ConfigurableApplicationContext startApplication(boolean fixture, boolean https, String... extra) {
		var arguments = new ArrayList<>(List.of("--spring.profiles.active=" + (fixture && !https ? "local,integration" : "local,production"),
				"--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
				"--spring.datasource.password=" + POSTGRES.getPassword(), "--server.address=127.0.0.1", "--server.port=0",
				"--overload.session.cookie.secure=false", "--spring.session.jdbc.cleanup-cron=-"));
		arguments.addAll(Arrays.asList(extra));
		var builder = new SpringApplicationBuilder(OverloadServerApplication.class).web(WebApplicationType.SERVLET);
		if (fixture) {
			builder.sources(SecurityFixtures.class);
		}
		var context = builder.run(arguments.toArray(String[]::new));
		com.overload.testfixture.MixedPersistenceAssertions.verify(context);
		return context;
	}

}
