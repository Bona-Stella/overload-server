package com.overload.overload_server.auth.google;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.auth.email.OtpEmailSender;
import com.overload.overload_server.security.SessionMetadata;
import com.overload.overload_server.security.SessionPrincipal;
import com.overload.testfixture.EmailOtpFixtures;
import com.overload.testfixture.GoogleOidcFixtures;
import com.overload.testfixture.GoogleOidcFixtures.Fault;
import com.overload.testfixture.GoogleOidcFixtures.MockProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.overload.testfixture.EmailOtpFixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres")
@Testcontainers
class GoogleOidcIntegrationTests {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_google_oidc_test").withUsername("overload_test")
			.withPassword(UUID.randomUUID().toString());
	static final JsonMapper JSON = JsonMapper.builder().build();
	static ConfigurableApplicationContext application;
	static JdbcTemplate jdbc;
	static MockProvider provider;
	static String base;
	static final String HMAC = randomKey();
	Browser browser;

	@BeforeAll
	static void start() throws Exception {
		provider = new MockProvider(); GoogleOidcFixtures.provider = provider;
		CLOCK.reset(); startApplication(true, 0);
	}

	static void startApplication(boolean enabled, int port) {
		startApplication(enabled, port, "");
	}
	static void startApplication(boolean enabled, int port, String frontend) {
		var sources = enabled ? new Class<?>[]{OverloadServerApplication.class, EmailOtpFixtures.class, GoogleOidcFixtures.class}
				: new Class<?>[]{OverloadServerApplication.class, EmailOtpFixtures.class};
		application = new SpringApplicationBuilder(sources).web(WebApplicationType.SERVLET)
				.run("--spring.profiles.active=local,integration", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
						"--spring.datasource.username=" + POSTGRES.getUsername(), "--spring.datasource.password=" + POSTGRES.getPassword(),
						"--GOOGLE_OIDC_ENABLED=" + enabled, "--OTP_EMAIL_ENABLED=false", "--OTP_HMAC_KEY=" + HMAC, "--AUTH_FRONTEND_RETURN_URI=" + frontend, "--AUTH_LINK_FRONTEND_RETURN_URI=", "--AUTH_CONTACT_FRONTEND_RETURN_URI=",
						// Ephemeral credentials are accepted only by the loopback mock, never written to .env.
						"--GOOGLE_CLIENT_ID=" + provider.clientId, "--GOOGLE_CLIENT_SECRET=" + provider.clientSecret,
						"--server.address=127.0.0.1", "--server.port=" + port, "--spring.session.jdbc.cleanup-cron=-",
						"--logging.level.root=WARN", "--spring.main.banner-mode=off");
		jdbc = application.getBean(JdbcTemplate.class);
		com.overload.testfixture.MixedPersistenceAssertions.verify(application);
		base = "http://127.0.0.1:" + ((WebServerApplicationContext) application).getWebServer().getPort();
	}

	@BeforeEach
	void isolateOwnedDatabase() {
		CLOCK.reset(); provider.reset(); GoogleOidcFixtures.corruptMetadataRead = false;
		jdbc.execute("DROP TRIGGER IF EXISTS google_test_fail ON user_workout_settings");
		jdbc.execute("DROP TRIGGER IF EXISTS google_test_fail ON spring_session_attributes");
		// Only this test class's disposable database; never the Compose developer DB.
		jdbc.update("DELETE FROM spring_session"); jdbc.update("DELETE FROM email_otp_login_claims");
		jdbc.update("UPDATE email_otp_limits SET active_challenge_id=NULL");
		for (var table : List.of("email_otp_challenges", "email_otp_limits", "email_otp_rate_limits",
				"exercise_definitions", "user_workout_settings", "auth_identities", "users")) { jdbc.update("DELETE FROM " + table); }
		application.getBean(EmailOtpFixtures.FakeSender.class).reset();
		browser = new Browser();
	}

	@Test
	void optInFrontendReturnUsesFixedRoutesAndRestoresTheSameJdbcPrincipal() throws Exception {
		int port = URI.create(base).getPort(); application.close(); startApplication(true, port, GoogleFrontendReturn.LOCAL);
		try {
			var flow = begin(browser);
			var callback = complete(browser, flow);
			assertThat(callback.statusCode()).isEqualTo(302);
			assertThat(callback.headers().firstValue("Location")).contains(GoogleFrontendReturn.LOCAL);
			assertThat(callback.headers().firstValue("Referrer-Policy")).contains("no-referrer");
			UUID id = userId(browser);
			assertThat(userId(browser)).isEqualTo(id);
			assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
			assertNoProviderStorage();
		} finally { application.close(); startApplication(true, port); }
	}

	@Test
	void optInFailureNeverReflectsRequestReturnUrlsOrCreatesAnAuthenticatedSession() throws Exception {
		int port = URI.create(base).getPort(); application.close(); startApplication(true, port, GoogleFrontendReturn.LOCAL);
		try {
			var flow = begin(browser);
			String state = MockProvider.parameters(flow.callback.getRawQuery()).get("state");
			var callback = get(browser, "/login/oauth2/code/google?error=access_denied&state=" + MockProvider.encode(state) + "&next=https://evil.example&returnUrl=https://evil.example");
			assertThat(callback.statusCode()).isEqualTo(302);
			assertThat(callback.headers().firstValue("Location")).contains("http://localhost:3000/auth/failed");
			assertAnonymousAndNoAccount(browser);
		} finally { application.close(); startApplication(true, port); }
	}

	@AfterEach
	void closeBrowser() { GoogleOidcFixtures.corruptMetadataRead = false; browser.close(); }

	@AfterAll
	static void stop() { if (application != null) { application.close(); } if (provider != null) { provider.close(); } }

	@Test
	void googleOnlyIdentityCannotRequestEmailReauthenticationOrBorrowAnOtpAccount() throws Exception {
		assertSuccess(complete(browser, begin(browser)));
		var google = stored(browser);
		var metadata = (SessionMetadata) google.getAttribute(SessionMetadata.ATTRIBUTE);
		var accounts = application.getBean(com.overload.overload_server.account.AccountProvisioningService.class);
		var email = accounts.provision(com.overload.overload_server.account.VerifiedIdentity.email(provider.email), null);
		var ownId = jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=?", UUID.class, metadata.userId());
		var foreignId = jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=?", UUID.class, email.id());
		var identities = get(browser, "/api/v1/auth/identities");
		assertThat(identities.statusCode()).isEqualTo(200);
		assertThat(identities.body()).contains("google", ownId.toString()).doesNotContain("@", provider.subject, foreignId.toString());
		for (var identity : List.of(ownId, foreignId)) {
			assertThat(post(browser, "/api/v1/auth/reauth/email/request", Map.of("identityId", identity), csrf(browser)).statusCode()).isEqualTo(400);
			assertThat(post(browser, "/api/v1/auth/reauth/email/verify", Map.of("identityId", identity,
					"challengeId", UUID.randomUUID(), "code", "000007"), csrf(browser)).statusCode()).isEqualTo(400);
		}
		assertThat(application.getBean(EmailOtpFixtures.FakeSender.class).messages).isEmpty();
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(2);
		assertThat(metadata.userId().equals(email.id())).isFalse();
		assertThat(metadata.absoluteExpiresAt()).isEqualTo(((SessionMetadata) stored(browser).getAttribute(SessionMetadata.ATTRIBUTE)).absoluteExpiresAt());
	}

	@Test
	void signedCodeFlowCreatesAccountAndDurableOwnPrincipalThenRestoresItOnTheNextRequest() throws Exception {
		assertThat(get(browser, "/api/v1/auth/session").statusCode()).isEqualTo(401);
		assertThat(get(browser, "/login").statusCode()).isEqualTo(401);
		var oldCsrf = csrf(browser);
		var flow = begin(browser);
		provider.onExchange = () -> assertThat(count("SELECT count(*) FROM spring_session_attributes WHERE attribute_name LIKE '%JdbcAuthorizationRequestRepository%'")).isZero();
		assertSuccess(complete(browser, flow));
		assertThat(flow.oldCookie.equals(browser.cookie())).isFalse();
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google' AND issuer='https://accounts.google.com'")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM exercise_definitions")).isZero();
		assertThat(count("SELECT count(*) FROM user_workout_settings WHERE rest_low_seconds=180 AND rest_medium_seconds=120 AND rest_high_seconds=90")).isEqualTo(1);
		var status = get(browser, "/api/v1/auth/session");
		assertThat(status.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(status.body()).size()).isEqualTo(1);
		assertThat(status.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
		var stored = stored(browser);
		var context = (SecurityContext) stored.getAttribute("SPRING_SECURITY_CONTEXT");
		assertThat(context.getAuthentication().getPrincipal() instanceof SessionPrincipal).isTrue();
		var metadata = (SessionMetadata) stored.getAttribute(SessionMetadata.ATTRIBUTE);
		assertThat(metadata.absoluteExpiresAt()).isEqualTo(metadata.loginStartedAt().plus(Duration.ofDays(90)));
		assertThat(metadata.reauthenticatedAt()).isEqualTo(metadata.loginStartedAt());
		assertThat(post(browser, "/__otp_test__/mutate", Map.of(), oldCsrf).statusCode()).isEqualTo(403);
		assertThat(post(browser, "/__otp_test__/mutate", Map.of(), csrf(browser)).statusCode()).isEqualTo(204);
		assertNoProviderStorage();
		try (var oldBrowser = new Browser(flow.oldCookie)) { assertThat(get(oldBrowser, "/api/v1/auth/session").statusCode()).isEqualTo(401); }
		var cookie = browser.cookie();
		assertThat(post(browser, "/api/v1/auth/logout", Map.of(), csrf(browser)).statusCode()).isEqualTo(204);
		try (var oldBrowser = new Browser(cookie)) { assertThat(get(oldBrowser, "/api/v1/auth/session").statusCode()).isEqualTo(401); }
	}

	@ParameterizedTest
	@EnumSource(value = Fault.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
	void invalidTokensProviderFailuresAndJwksFailuresNeverProvisionOrAuthenticate(Fault fault) throws Exception {
		provider.fault = fault;
		assertFailure(complete(browser, begin(browser)));
		assertAnonymousAndNoAccount(browser);
		assertNoProviderStorage();
	}

	@Test
	void noTemporaryGooglePrincipalIsWrittenEvenBeforeTheSuccessHandlerReplacesIt() throws Exception {
		jdbc.execute("CREATE TABLE google_context_audit (provider_principal BOOLEAN NOT NULL)");
		jdbc.execute("""
				CREATE FUNCTION google_context_audit() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN
				  IF NEW.attribute_name='SPRING_SECURITY_CONTEXT' THEN
				    INSERT INTO google_context_audit VALUES (
				      position(convert_to('OAuth2AuthenticationToken','UTF8') IN NEW.attribute_bytes)>0 OR
				      position(convert_to('OidcIdToken','UTF8') IN NEW.attribute_bytes)>0);
				  END IF;
				  RETURN NEW;
				END $$
				""");
		jdbc.execute("CREATE TRIGGER google_context_audit BEFORE INSERT OR UPDATE ON spring_session_attributes FOR EACH ROW EXECUTE FUNCTION google_context_audit()");
		try {
			assertSuccess(complete(browser, begin(browser)));
			assertThat(count("SELECT count(*) FROM google_context_audit WHERE provider_principal")).isZero();
			assertThat(count("SELECT count(*) FROM google_context_audit WHERE NOT provider_principal")).isGreaterThan(0);
			assertNoProviderStorage();
		} finally {
			jdbc.execute("DROP TRIGGER google_context_audit ON spring_session_attributes");
			jdbc.execute("DROP FUNCTION google_context_audit()"); jdbc.execute("DROP TABLE google_context_audit");
		}
	}

	@Test
	void startUsesStateNoncePkceFixedScopesAndIgnoresExternalReturnUrls() throws Exception {
		var response = get(browser, "/oauth2/authorization/google?next=https://external.example.test&returnUrl=https://other.example.test");
		assertThat(response.statusCode()).isEqualTo(302);
		var uri = URI.create(response.headers().firstValue("Location").orElseThrow());
		var query = MockProvider.parameters(uri.getRawQuery());
		assertThat((provider.base() + "/authorize").equals(uri.getScheme() + "://" + uri.getAuthority() + uri.getPath())).isTrue();
		assertThat((base + "/login/oauth2/code/google").equals(query.get("redirect_uri"))).isTrue();
		assertThat(List.of(query.get("scope").split(" "))).containsExactlyInAnyOrder("openid", "email", "profile");
		assertThat(query.containsKey("state") && query.containsKey("nonce") && query.containsKey("code_challenge")).isTrue();
		assertThat(query.get("code_challenge_method")).isEqualTo("S256");
		assertThat(query.containsKey("next") || query.containsKey("returnUrl") || query.containsKey("access_type") || query.containsKey("userId")).isFalse();
		assertThat(response.headers().firstValue("Referrer-Policy").orElse("")).isEqualTo("no-referrer");
		assertThat(count("SELECT count(*) FROM users")).isZero();
	}

	@Test
	void tamperedOrMissingStateNeverExchangesACodeAndClearsTheAttempt() throws Exception {
		var flow = begin(browser);
		var query = MockProvider.parameters(flow.callback.getRawQuery());
		assertFailure(get(browser, "/login/oauth2/code/google?code=" + MockProvider.encode(query.get("code")) + "&state=wrong-state"));
		assertThat(provider.exchanges.get()).isZero(); assertAnonymousAndNoAccount(browser); assertNoProviderStorage();
		assertFailure(get(browser, "/login/oauth2/code/google?code=unbound-code"));
		assertThat(provider.exchanges.get()).isZero();
	}

	@Test
	void anotherBrowserCannotConsumeTheOriginalBrowserState() throws Exception {
		var flow = begin(browser);
		try (var stranger = new Browser()) { assertFailure(complete(stranger, flow)); assertAnonymousAndNoAccount(stranger); }
		assertThat(provider.exchanges.get()).isZero();
		assertSuccess(complete(browser, flow));
	}

	@Test
	void cancellationIsAGenericFailureWithoutAnExchangeOrResidualLogin() throws Exception {
		var flow = begin(browser);
		var state = MockProvider.parameters(flow.callback.getRawQuery()).get("state");
		var response = get(browser, "/login/oauth2/code/google?error=access_denied&error_description=provider-detail&state=" + MockProvider.encode(state));
		assertFailure(response); assertAnonymousAndNoAccount(browser); assertNoProviderStorage();
		assertThat(provider.exchanges.get()).isZero();
		var failure = get(browser, "/auth/google/failure?error_description=provider-detail");
		assertThat(failure.statusCode()).isEqualTo(401);
		assertThat(failure.body()).isEqualTo("{\"code\":\"GOOGLE_LOGIN_FAILED\"}");
	}

	@Test
	void sameStateCallbackRaceHasOnlyOneExchangeAndOneSuccessfulLogin() throws Exception {
		var flow = begin(browser);
		try (var left = new Browser(flow.oldCookie); var right = new Browser(flow.oldCookie);
			 var executor = Executors.newFixedThreadPool(2)) {
			var ready = new CountDownLatch(1);
			var first = executor.submit(() -> { ready.await(); return complete(left, flow); });
			var second = executor.submit(() -> { ready.await(); return complete(right, flow); });
			ready.countDown();
			var responses = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
			assertThat(responses.stream().filter(this::success).count()).isEqualTo(1);
			assertThat(provider.exchanges.get()).isEqualTo(1);
			assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
			assertThat(count("SELECT count(*) FROM exercise_definitions")).isZero();
		}
		assertNoProviderStorage();
	}

	@Test
	void simultaneousFirstGoogleLoginsOfOneSubjectCreateOnlyOneAccount() throws Exception {
		try (var other = new Browser(); var executor = Executors.newFixedThreadPool(2)) {
			var left = begin(browser); var right = begin(other);
			var first = executor.submit(() -> complete(browser, left));
			var second = executor.submit(() -> complete(other, right));
			assertSuccess(first.get(20, TimeUnit.SECONDS)); assertSuccess(second.get(20, TimeUnit.SECONDS));
			assertThat(userId(browser)).isEqualTo(userId(other));
			assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
			assertThat(count("SELECT count(*) FROM exercise_definitions")).isZero();
		}
	}

	@Test
	void changedEmailWithTheSameSubjectPreservesUserGenerationAndEditedData() throws Exception {
		assertSuccess(complete(browser, begin(browser))); var user = userId(browser);
		var exercise = UUID.randomUUID();
		jdbc.update("DELETE FROM exercise_definitions WHERE user_id=?", user); jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?", user);
		jdbc.update("UPDATE users SET data_generation=7,nickname='edited nickname' WHERE id=?", user);
		jdbc.update("INSERT INTO exercise_definitions(id,user_id,data_generation,name) VALUES(?,?,7,'edited definition')", exercise, user);
		jdbc.update("INSERT INTO user_workout_settings VALUES(?,7,210,150,100,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user);
		post(browser, "/api/v1/auth/logout", Map.of(), csrf(browser));
		provider.email = "changed-address@example.test";
		assertSuccess(complete(browser, begin(browser)));
		assertThat(userId(browser)).isEqualTo(user);
		assertThat(jdbc.queryForObject("SELECT data_generation FROM users WHERE id=?", Long.class, user)).isEqualTo(7L);
		assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id=?", String.class, user)).isEqualTo("edited nickname");
		assertThat(jdbc.queryForObject("SELECT id FROM exercise_definitions WHERE user_id=?", UUID.class, user)).isEqualTo(exercise);
		assertThat(count("SELECT count(*) FROM user_workout_settings WHERE rest_low_seconds=210 AND rest_medium_seconds=150 AND rest_high_seconds=100")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
	}

	@Test
	void equalEmailNeverMergesOtpAndGoogleAndAuthenticatedBrowsersCannotSwitchAccounts() throws Exception {
		otpLogin(browser, provider.email); var otpUser = userId(browser); var originalCookie = browser.cookie();
		assertThat(get(browser, "/oauth2/authorization/google").statusCode()).isEqualTo(409);
		try (var google = new Browser()) {
			var flow = begin(google);
			assertThat(complete(browser, flow).statusCode()).isEqualTo(409);
			assertThat(provider.exchanges.get()).isZero();
			assertThat(originalCookie.equals(browser.cookie())).isTrue(); assertThat(userId(browser)).isEqualTo(otpUser);
			assertSuccess(complete(google, flow)); var googleUser = userId(google);
			assertThat(googleUser.equals(otpUser)).isFalse();
			assertThat(get(google, "/oauth2/authorization/google").statusCode()).isEqualTo(409);
			assertThat(complete(google, flow).statusCode()).isEqualTo(409);
			assertThat(userId(google)).isEqualTo(googleUser);
			var forged = get(browser, "/api/v1/auth/session?userId=" + googleUser);
			assertThat(JSON.readTree(forged.body()).get("userId").asString()).isEqualTo(otpUser.toString());
		}
		provider.subject = "different-google-sub";
		try (var another = new Browser()) { assertSuccess(complete(another, begin(another))); }
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM exercise_definitions")).isZero();
		assertThat(count("SELECT count(*) FROM user_workout_settings")).isEqualTo(3);
	}

	@Test
	void reusedCodeInANewStateFailsAndOldCallbackCannotReplaceAnAuthenticatedSession() throws Exception {
		var first = begin(browser); assertSuccess(complete(browser, first));
		assertThat(complete(browser, first).statusCode()).isEqualTo(409);
		var oldCode = MockProvider.parameters(first.callback.getRawQuery()).get("code");
		try (var fresh = new Browser()) {
			var next = begin(fresh); var state = MockProvider.parameters(next.callback.getRawQuery()).get("state");
			assertFailure(get(fresh, "/login/oauth2/code/google?code=" + MockProvider.encode(oldCode) + "&state=" + MockProvider.encode(state)));
			assertThat(get(fresh, "/api/v1/auth/session").statusCode()).isEqualTo(401);
		}
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
	}

	@Test
	void provisioningFailureRollsBackSeedAndFreshFlowRecovers() throws Exception {
		failureTrigger("user_workout_settings", "TRUE");
		assertFailure(complete(browser, begin(browser))); assertAnonymousAndNoAccount(browser);
		jdbc.execute("DROP TRIGGER google_test_fail ON user_workout_settings");
		assertSuccess(complete(browser, begin(browser)));
		assertThat(count("SELECT count(*) FROM exercise_definitions")).isZero();
	}

	@Test
	void swallowedJdbcAttributeFailureCannotBecomeASuccessRedirect() throws Exception {
		failureTrigger("spring_session_attributes", "NEW.attribute_name='SPRING_SECURITY_CONTEXT'");
		assertFailure(complete(browser, begin(browser)));
		assertThat(get(browser, "/api/v1/auth/session").statusCode()).isEqualTo(401);
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM exercise_definitions")).isZero();
		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL")).isZero();
		jdbc.execute("DROP TRIGGER google_test_fail ON spring_session_attributes");
		assertSuccess(complete(browser, begin(browser))); assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
	}

	@Test
	void mismatchedStoredMetadataCannotBecomeASuccessRedirect() throws Exception {
		GoogleOidcFixtures.corruptMetadataRead = true;
		assertFailure(complete(browser, begin(browser)));
		GoogleOidcFixtures.corruptMetadataRead = false;
		assertThat(get(browser, "/api/v1/auth/session").statusCode()).isEqualTo(401);
		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL")).isZero();
		assertSuccess(complete(browser, begin(browser))); assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
	}

	@Test
	void pendingRequestPersistenceFailureDoesNotRedirectToGoogle() throws Exception {
		failureTrigger("spring_session_attributes", "NEW.attribute_name LIKE '%JdbcAuthorizationRequestRepository%'");
		assertFailure(get(browser, "/oauth2/authorization/google"));
		assertThat(provider.authorizations.get()).isZero(); assertAnonymousAndNoAccount(browser);
	}

	@Test
	void oneConnectionPoolCanConsumeStateWithoutNestingTheSessionRepositoryTransaction() throws Exception {
		// Migrations/bootstrap complete normally first; constrain only the runtime login operation.
		var pool = application.getBean(com.zaxxer.hikari.HikariDataSource.class);
		int maximum = pool.getMaximumPoolSize(); int minimum = pool.getMinimumIdle();
		pool.setMinimumIdle(0); pool.setMaximumPoolSize(1); pool.getHikariPoolMXBean().softEvictConnections();
		try {
			for (int attempt = 0; attempt < 100 && pool.getHikariPoolMXBean().getTotalConnections() > 1; attempt++) { Thread.sleep(10); }
			assertThat(pool.getMaximumPoolSize()).isEqualTo(1);
			assertThat(pool.getHikariPoolMXBean().getTotalConnections()).isLessThanOrEqualTo(1);
			assertSuccess(complete(browser, begin(browser)));
			assertThat(get(browser, "/api/v1/auth/session").statusCode()).isEqualTo(200);
		} finally { pool.setMaximumPoolSize(maximum); pool.setMinimumIdle(minimum); }
	}

	@Test
	void pendingStateAndOwnLoginSurviveServerRestartAndAbsoluteLifetimeDoesNotSlide() throws Exception {
		var flow = begin(browser); int port = URI.create(base).getPort();
		application.close(); startApplication(true, port);
		assertSuccess(complete(browser, flow)); var id = userId(browser); var metadata = (SessionMetadata) stored(browser).getAttribute(SessionMetadata.ATTRIBUTE);
		application.close(); startApplication(true, port);
		assertThat(userId(browser)).isEqualTo(id);
		CLOCK.advance(Duration.ofDays(89)); assertThat(get(browser, "/api/v1/auth/session").statusCode()).isEqualTo(200);
		assertThat(((SessionMetadata) stored(browser).getAttribute(SessionMetadata.ATTRIBUTE)).absoluteExpiresAt()).isEqualTo(metadata.absoluteExpiresAt());
		CLOCK.advance(Duration.ofDays(1)); assertThat(get(browser, "/api/v1/auth/session").statusCode()).isEqualTo(401);
	}

	@Test
	void disabledGoogleKeepsOtpHealthAndSessionServicesWorkingWithoutACredentialFallback() throws Exception {
		int port = URI.create(base).getPort(); application.close(); startApplication(false, port);
		try {
			assertThat(application.getBeansOfType(ClientRegistrationRepository.class)).isEmpty();
			assertThat(get(browser, "/oauth2/authorization/google").statusCode()).isEqualTo(503);
			assertThat(get(browser, "/login/oauth2/code/google?code=invalid&state=invalid").statusCode()).isEqualTo(503);
			assertThat(get(browser, "/api/v1/auth/session").statusCode()).isEqualTo(401);
			assertThat(get(browser, "/api/v1/health").body()).isEqualTo("{\"status\":\"UP\"}");
			assertThat(post(browser, "/api/v1/health", Map.of(), null).statusCode()).isEqualTo(405);
			assertThat(post(browser, "/api/v1/auth/email-otp/request", Map.of("email", provider.email), null).statusCode()).isEqualTo(401);
			otpLogin(browser, provider.email); assertThat(get(browser, "/api/v1/auth/session").statusCode()).isEqualTo(200);
		} finally { application.close(); CLOCK.reset(); startApplication(true, port); }
	}

	private Flow begin(Browser current) throws Exception {
		var start = get(current, "/oauth2/authorization/google"); assertThat(start.statusCode()).isEqualTo(302);
		var old = current.cookie();
		try (var providerBrowser = HttpClient.newHttpClient()) {
			var authorization = URI.create(start.headers().firstValue("Location").orElseThrow());
			var response = providerBrowser.send(HttpRequest.newBuilder(authorization).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(302);
			return new Flow(URI.create(response.headers().firstValue("Location").orElseThrow()), old);
		}
	}
	private HttpResponse<String> complete(Browser current, Flow flow) throws Exception { return send(current, HttpRequest.newBuilder(flow.callback).GET()); }
	private HttpResponse<String> get(Browser current, String path) throws Exception { return send(current, HttpRequest.newBuilder(URI.create(base + path)).GET()); }
	private HttpResponse<String> post(Browser current, String path, Object body, String csrf) throws Exception {
		var builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json");
		if (csrf != null) { builder.header("X-CSRF-TOKEN", csrf); }
		return send(current, builder.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))));
	}
	private HttpResponse<String> send(Browser current, HttpRequest.Builder builder) throws Exception { return current.client.send(builder.timeout(Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofString()); }
	private String csrf(Browser current) throws Exception { var response = get(current, "/api/v1/auth/csrf"); assertThat(response.statusCode()).isEqualTo(200); return JSON.readTree(response.body()).get("token").asString(); }
	private UUID userId(Browser current) throws Exception { var response = get(current, "/api/v1/auth/session"); assertThat(response.statusCode()).isEqualTo(200); return UUID.fromString(JSON.readTree(response.body()).get("userId").asString()); }
	private long count(String sql) { return jdbc.queryForObject(sql, Long.class); }
	private boolean success(HttpResponse<String> response) { return response.statusCode() == 302 && response.headers().firstValue("Location").orElse("").equals("/api/v1/auth/session"); }
	private void assertSuccess(HttpResponse<String> response) { assertThat(success(response)).as("only the fixed success redirect is allowed").isTrue(); }
	private void assertFailure(HttpResponse<String> response) { assertThat(response.statusCode()).isEqualTo(302); assertThat(response.headers().firstValue("Location").orElse("").equals("/auth/google/failure")).as("only the fixed failure redirect is allowed").isTrue(); }
	private void assertAnonymousAndNoAccount(Browser current) throws Exception {
		assertThat(get(current, "/api/v1/auth/session").statusCode()).isEqualTo(401);
		for (var table : List.of("users", "auth_identities", "exercise_definitions", "user_workout_settings")) { assertThat(count("SELECT count(*) FROM " + table)).isZero(); }
		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL")).isZero();
	}
	private Session stored(Browser current) { return application.getBean(SessionRepository.class).findById(current.cookie()); }
	private void assertNoProviderStorage() {
		assertThat(count("SELECT count(*) FROM spring_session_attributes WHERE attribute_name LIKE '%OAuth2%' OR attribute_name LIKE '%JdbcAuthorizationRequestRepository%'")).isZero();
		for (var bytes : jdbc.queryForList("SELECT attribute_bytes FROM spring_session_attributes", byte[].class)) { assertThat(provider.containsProviderToken(bytes)).isFalse(); }
		var services = application.getBeansOfType(OAuth2AuthorizedClientService.class).values();
		for (var service : services) { assertThat(service.loadAuthorizedClient("google", provider.subject) == null).isTrue(); }
	}
	private void otpLogin(Browser current, String email) throws Exception {
		String csrf = csrf(current);
		var issued = post(current, "/api/v1/auth/email-otp/request", Map.of("email", email), csrf); assertThat(issued.statusCode()).isEqualTo(202);
		var id = UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString());
		OtpEmailSender.Message message = application.getBean(EmailOtpFixtures.FakeSender.class).messages.get(id);
		assertThat(post(current, "/api/v1/auth/email-otp/verify", Map.of("email", email, "challengeId", id, "code", message.code()), csrf).statusCode()).isEqualTo(204);
	}
	private void failureTrigger(String table, String condition) {
		jdbc.execute("CREATE OR REPLACE FUNCTION google_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF " + condition + " THEN RAISE EXCEPTION 'injected storage failure'; END IF; RETURN NEW; END $$");
		jdbc.execute("CREATE TRIGGER google_test_fail BEFORE INSERT OR UPDATE ON " + table + " FOR EACH ROW EXECUTE FUNCTION google_test_fail()");
	}
	private static String randomKey() { byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes); return Base64.getEncoder().encodeToString(bytes); }
	private record Flow(URI callback, String oldCookie) { @Override public String toString() { return "OidcFlow[redacted]"; } }
	private static final class Browser implements AutoCloseable {
		final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
		Browser() { }
		Browser(String cookie) { var value = new HttpCookie("SESSION", cookie); value.setPath("/"); value.setVersion(0); cookies.getCookieStore().add(URI.create(base), value); }
		String cookie() { return cookies.getCookieStore().getCookies().stream().filter(c -> "SESSION".equals(c.getName())).findFirst().orElseThrow().getValue(); }
		@Override public void close() { client.close(); }
	}

}
