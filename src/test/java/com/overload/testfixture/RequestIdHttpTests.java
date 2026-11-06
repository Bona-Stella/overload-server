package com.overload.testfixture;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.http.RequestIdFilter;
import com.overload.overload_server.auth.email.EmailOtpService;
import com.overload.overload_server.auth.email.EmailIdentityService;
import com.overload.overload_server.auth.email.OtpEmailSender;
import com.overload.overload_server.auth.identity.IdentityChangeFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real embedded servlet/Security/Session chain; no DataSource, Flyway or external provider. */
@SpringBootTest(classes = OverloadServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"spring.profiles.active=request-id-test", "server.address=127.0.0.1",
				"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
				"GOOGLE_OIDC_ENABLED=true", "GOOGLE_REAUTH_ENABLED=false", "OTP_EMAIL_ENABLED=false",
				"RESEND_API_KEY=", "RESEND_FROM_EMAIL=", "OTP_HMAC_KEY=",
				"AUTH_FRONTEND_RETURN_URI=", "AUTH_CONTACT_FRONTEND_RETURN_URI=", "AUTH_LINK_FRONTEND_RETURN_URI="
		})
@Import({RequestIdHttpTests.Fixtures.class, RequestIdHttpTests.ProbeController.class,
		SecurityFixtures.class, SecurityFixtures.FixtureController.class})
@MockitoBean(types = {JdbcTemplate.class, PlatformTransactionManager.class, EmailOtpService.class, EmailIdentityService.class, OtpEmailSender.class,
		com.overload.overload_server.statistics.DailyTrainingReviewService.class,
		com.overload.overload_server.workoutsession.WorkoutRecordService.class,
		com.overload.overload_server.workoutsession.WorkoutSnapshotService.class, com.overload.overload_server.workoutsession.WorkoutSnapshotRepository.class, com.overload.overload_server.workoutsession.WorkoutSessionService.class, com.overload.overload_server.workoutsession.WorkoutSessionRepository.class,
		com.overload.overload_server.workout.WorkoutReadService.class,
		com.overload.overload_server.catalog.CatalogReadService.class,
		com.overload.overload_server.routine.RoutineService.class, com.overload.overload_server.routine.RoutineRepository.class, com.overload.overload_server.workout.WorkoutWriteService.class})
class RequestIdHttpTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	@Autowired WebServerApplicationContext app;
	@Autowired jakarta.servlet.ServletContext servletContext;
	@Autowired JdbcTemplate jdbc;
	@Autowired PlatformTransactionManager transactions;
	@Autowired EmailOtpService otp;
	@Autowired EmailIdentityService emailIdentities;
	@Autowired OtpEmailSender sender;
	@Autowired MemorySessions sessions;
	@Autowired ProbeController probe;
	private HttpClient client;
	private String cookie;
	private final LinkedBlockingQueue<ILoggingEvent> logs = new LinkedBlockingQueue<>();
	private final Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
	private final AppenderBase<ILoggingEvent> capture = new AppenderBase<>() {
		@Override protected void append(ILoggingEvent event) { event.prepareForDeferredProcessing(); logs.add(event); }
	};

	@BeforeEach
	void browser() {
		SecurityFixtures.CLOCK.reset();
		client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
		cookie = null;
		sessions.observed.clear();
		capture.start();
		logger.addAppender(capture);
	}

	@AfterEach
	void close() {
		client.close();
		logger.detachAppender(capture);
		capture.stop();
		verifyNoInteractions(jdbc, transactions, sender);
		assertThat(sessions.observed).allSatisfy(id -> assertThat(UUID.fromString(id).version()).isEqualTo(4));
	}

	@Test
	void health200HasOneFreshServerIdAndExactExistingBody() throws Exception {
		var first = send("GET", "/api/v1/health", null, null, "untrusted-client-id");
		var second = send("GET", "/api/v1/health", null, null, null);
		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(first.body()).isEqualTo("{\"status\":\"UP\"}");
		assertThat(id(first)).isNotEqualTo(id(second)).isNotEqualTo("untrusted-client-id");
		assertSecurityHeaders(first);
	}

	@Test
	void csrfSession204Rotation403AndLogoutKeepAuthenticationContracts() throws Exception {
		var unauthenticated = send("GET", "/api/v1/auth/session", null, null, null);
		assertThat(unauthenticated.statusCode()).isEqualTo(401);
		assertThat(unauthenticated.body()).isEqualTo("{\"code\":\"AUTHENTICATION_REQUIRED\"}");
		assertThat(unauthenticated.headers().firstValue("Location")).isEmpty();
		id(unauthenticated);
		assertSecurityHeaders(unauthenticated);
		var token = csrf();
		var before = cookie;
		var login = send("POST", "/__test__/login", "{}", token, null);
		assertThat(login.statusCode()).isEqualTo(204);
		assertThat(login.body()).isEmpty();
		assertThat(cookie).isNotEqualTo(before);
		assertThat(login.headers().allValues("Set-Cookie").toString()).contains("Secure", "HttpOnly", "SameSite=Lax");
		id(login);
		var session = send("GET", "/api/v1/auth/session", null, null, null);
		assertThat(session.body()).isEqualTo("{\"userId\":\"" + SecurityFixtures.USER_ID + "\"}");
		assertThat(session.statusCode()).isEqualTo(200);
		id(session);
		assertSecurityHeaders(session);
		var csrfDenied = send("POST", "/__test__/mutate", "{}", token, null);
		assertThat(csrfDenied.statusCode()).isEqualTo(403);
		assertThat(csrfDenied.body()).isEqualTo("{\"code\":\"CSRF_INVALID\"}");
		id(csrfDenied);
		assertSecurityHeaders(csrfDenied);
		var denied = send("GET", "/__test__/denied", null, null, null);
		assertThat(denied.statusCode()).isEqualTo(403);
		assertThat(denied.body()).isEqualTo("{\"code\":\"ACCESS_DENIED\"}");
		id(denied);
		var logout = send("POST", "/api/v1/auth/logout", "{}", csrf(), null);
		assertThat(logout.statusCode()).isEqualTo(204);
		assertThat(logout.body()).isEmpty();
		assertThat(logout.headers().allValues("Set-Cookie").toString()).contains("Max-Age=0");
		id(logout);
		assertThat(sessions.observed).isNotEmpty();
	}

	@Test
	void existingOtp202AndAdvice400BodiesRemainUnwrapped() throws Exception {
		var challenge = UUID.randomUUID();
		when(otp.request(anyString(), anyString(), anyString())).thenReturn(challenge);
		var accepted = send("POST", "/api/v1/auth/email-otp/request", "{\"email\":\"synthetic@example.test\"}", csrf(), null);
		assertThat(accepted.statusCode()).isEqualTo(202);
		assertThat(JSON.readTree(accepted.body()).size()).isEqualTo(2);
		assertThat(JSON.readTree(accepted.body()).get("status").asString()).isEqualTo("ACCEPTED");
		assertThat(JSON.readTree(accepted.body()).get("challengeId").asString()).isEqualTo(challenge.toString());
		id(accepted);
		assertSecurityHeaders(accepted);
		var invalid = send("POST", "/api/v1/auth/email-otp/verify", "{\"challengeId\":\"not-a-uuid\"}", csrf(), null);
		assertThat(invalid.statusCode()).isEqualTo(400);
		assertThat(invalid.body()).isEqualTo("{\"code\":\"INVALID_REQUEST\"}");
		id(invalid);
		assertSecurityHeaders(invalid);
	}

	@Test
	void existingEmailLink201And200AndMappedFailuresKeepExactJsonShape() throws Exception {
		send("POST", "/__test__/login", "{}", csrf(), null);
		var identity = UUID.randomUUID();
		for (boolean created : new boolean[] {true, false}) {
			when(emailIdentities.link(any(UUID.class), any(HttpServletRequest.class)))
					.thenReturn(new EmailIdentityService.Linked(identity, created));
			var response = send("POST", "/api/v1/auth/identities/email/link", "{}", csrf(), null);
			assertThat(response.statusCode()).isEqualTo(created ? 201 : 200);
			var data = JSON.readTree(response.body());
			assertThat(data.size()).isEqualTo(2);
			assertThat(data.get("status").asString()).isEqualTo(created ? "LINKED" : "ALREADY_LINKED");
			assertThat(data.get("identity").size()).isEqualTo(2);
			assertThat(data.get("identity").get("id").asString()).isEqualTo(identity.toString());
			assertThat(data.get("identity").get("provider").asString()).isEqualTo("email");
			id(response);
			assertSecurityHeaders(response);
		}
		for (var failure : List.of(new IdentityChangeFailure(403, "EMAIL_OWNERSHIP_PROOF_REQUIRED"),
				new IdentityChangeFailure(404, "IDENTITY_NOT_FOUND"), new IdentityChangeFailure(409, "IDENTITY_ALREADY_OWNED"),
				new IdentityChangeFailure(503, "IDENTITY_CHANGE_UNAVAILABLE"))) {
			org.mockito.Mockito.doThrow(failure).when(emailIdentities).link(any(UUID.class), any(HttpServletRequest.class));
			var response = send("POST", "/api/v1/auth/identities/email/link", "{}", csrf(), null);
			assertThat(response.statusCode()).isEqualTo(failure.status);
			assertThat(response.body()).isEqualTo("{\"code\":\"" + failure.code + "\"}");
			id(response);
			assertSecurityHeaders(response);
		}
	}

	@Test
	void realOauthStartAndFailedCallbackKeep302HeadersWithoutAnyProviderCall() throws Exception {
		var start = send("GET", "/oauth2/authorization/google", null, null, null);
		assertThat(start.statusCode()).isEqualTo(302);
		assertThat(start.headers().firstValue("Location").orElseThrow()).startsWith("http://127.0.0.1:1/authorize?");
		assertThat(start.headers().firstValue("Referrer-Policy")).contains("no-referrer");
		assertThat(start.body()).isEmpty();
		id(start);
		assertSecurityHeaders(start);
		// No session/pending state: the real callback filter fails before JDBC or token exchange.
		cookie = null;
		var callback = send("GET", "/login/oauth2/code/google?error=access_denied", null, null, null);
		assertThat(callback.statusCode()).isEqualTo(302);
		assertThat(callback.headers().firstValue("Location")).contains("/auth/google/failure");
		assertThat(callback.headers().firstValue("Referrer-Policy")).contains("no-referrer");
		id(callback);
		var failure = send("GET", "/auth/google/failure", null, null, null);
		assertThat(failure.statusCode()).isEqualTo(401);
		assertThat(failure.body()).isEqualTo("{\"code\":\"GOOGLE_LOGIN_FAILED\"}");
		id(failure);
	}

	@Test
	void realContainerErrorResetAndCommittedResponsesKeepSameCorrelation() throws Exception {
		send("POST", "/__test__/login", "{}", csrf(), null);
		var reset = send("GET", "/__request_id__/reset", null, null, null);
		assertThat(reset.statusCode()).isEqualTo(200);
		assertThat(reset.body()).isEqualTo("reset-ok");
		assertThat(id(reset)).isEqualTo(probe.lastId);
		var committed = send("GET", "/__request_id__/commit", null, null, null);
		assertThat(committed.statusCode()).isEqualTo(204);
		assertThat(committed.body()).isEmpty();
		assertThat(id(committed)).isEqualTo(probe.lastId);
		var error = send("GET", "/__request_id__/error", null, null, null);
		assertThat(error.statusCode()).isEqualTo(500);
		assertThat(id(error)).isEqualTo(probe.lastId);
		assertThat(error.body()).doesNotContain("requestId", "exception", "trace");
		var errorId = id(error);
		var dispatches = new java.util.ArrayList<ILoggingEvent>();
		while (true) {
			var event = logs.poll(5, TimeUnit.SECONDS);
			assertThat(event).as("real container ERROR dispatch log").isNotNull();
			if (!errorId.equals(event.getMDCPropertyMap().get("requestId"))) continue;
			dispatches.add(event);
			if (event.getFormattedMessage().contains("dispatcher=ERROR")) break;
		}
		assertThat(dispatches).hasSize(2);
		assertThat(dispatches.getFirst().getFormattedMessage()).contains("dispatcher=REQUEST");
		assertThat(dispatches.getLast().getFormattedMessage()).contains("dispatcher=ERROR", "status=500");
	}

	@Test
	void registrationIsUniqueAndPrecedesSessionAndSecurity() {
		var registration = (FilterRegistrationBean<?>) app.getBean("requestIdFilterRegistration");
		assertThat(registration.getOrder()).isEqualTo(SessionRepositoryFilter.DEFAULT_ORDER - 1).isEqualTo(-2147483599);
		var session = app.getBean("sessionRepositoryFilterRegistration", DelegatingFilterProxyRegistrationBean.class);
		var security = app.getBean("securityFilterChainRegistration", DelegatingFilterProxyRegistrationBean.class);
		assertThat(session.getOrder()).isEqualTo(-2147483598).isGreaterThan(registration.getOrder());
		assertThat(security.getOrder()).isEqualTo(-100).isGreaterThan(registration.getOrder());
		assertThat(app.getBeansOfType(RequestIdFilter.class)).isEmpty();
		assertThat(servletContext.getFilterRegistrations().values().stream()
				.filter(value -> RequestIdFilter.class.getName().equals(value.getClassName())).count()).isEqualTo(1);
		assertThat(app.getBeansOfType(SecurityFilterChain.class).values()).allSatisfy(chain ->
				assertThat(chain.getFilters()).noneMatch(RequestIdFilter.class::isInstance));
	}

	private String csrf() throws Exception {
		var response = send("GET", "/api/v1/auth/csrf", null, null, null);
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(response.body()).size()).isEqualTo(2);
		assertThat(JSON.readTree(response.body()).get("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
		id(response);
		return JSON.readTree(response.body()).get("token").asString();
	}

	private HttpResponse<String> send(String method, String path, String body, String csrf, String externalId) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + path))
				.timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
		if (cookie != null) builder.header("Cookie", cookie);
		if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
		if (externalId != null) builder.header(RequestIdFilter.HEADER, externalId);
		if (body != null) builder.header("Content-Type", "application/json");
		var result = client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
		result.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION="))
				.forEach(value -> cookie = value.contains("Max-Age=0") ? null : value.split(";", 2)[0]);
		return result;
	}

	private static String id(HttpResponse<?> response) {
		assertThat(response.headers().allValues(RequestIdFilter.HEADER)).hasSize(1);
		var id = response.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
		assertThat(UUID.fromString(id).version()).isEqualTo(4);
		return id;
	}

	private static void assertSecurityHeaders(HttpResponse<?> response) {
		assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
		assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
		assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableSpringHttpSession
	static class Fixtures {
		@Bean MemorySessions memorySessions() { return new MemorySessions(); }
		@Bean ClientRegistrationRepository fixtureClientRegistrations() {
			return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
					.clientId("synthetic-client").clientSecret("synthetic-secret").redirectUri("http://127.0.0.1:1/login/oauth2/code/google")
					.authorizationUri("http://127.0.0.1:1/authorize").tokenUri("http://127.0.0.1:1/token")
					.jwkSetUri("http://127.0.0.1:1/jwks").build());
		}
	}

	/** Live in-memory references model immediate visibility only; never a JDBC persistence test. */
	static class MemorySessions implements SessionRepository<MapSession> {
		private final Map<String, MapSession> stored = new ConcurrentHashMap<>();
		final List<String> observed = new CopyOnWriteArrayList<>();
		private void observe() { observed.add(MDC.get("requestId") == null ? "MISSING" : MDC.get("requestId")); }
		@Override public MapSession createSession() { observe(); var session = new MapSession(); stored.put(session.getId(), session); return session; }
		@Override public void save(MapSession session) { observe(); stored.entrySet().removeIf(entry -> entry.getValue() == session && !entry.getKey().equals(session.getId())); stored.put(session.getId(), session); }
		@Override public MapSession findById(String id) { observe(); var session = stored.get(id); return session == null || session.isExpired() || !id.equals(session.getId()) ? null : session; }
		@Override public void deleteById(String id) { observe(); stored.remove(id); }
	}

	@RestController
	static class ProbeController {
		volatile String lastId;
		@GetMapping("/__request_id__/{action}")
		void boundary(HttpServletRequest request, HttpServletResponse response,
				@org.springframework.web.bind.annotation.PathVariable String action) throws IOException {
			lastId = request.getAttribute(RequestIdFilter.ATTRIBUTE).toString();
			assertThat(MDC.get("requestId")).isEqualTo(lastId);
			if (action.equals("error")) response.sendError(500);
			else if (action.equals("commit")) { response.setStatus(204); response.flushBuffer(); }
			else { response.reset(); response.getWriter().write("reset-ok"); }
		}
	}
}
