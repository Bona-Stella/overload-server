package com.overload.overload_server.auth.email;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.testfixture.EmailOtpFixtures;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.overload.testfixture.EmailOtpFixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
@Testcontainers
class EmailOtpIntegrationTests {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_email_otp_test").withUsername("overload_test")
			.withPassword(UUID.randomUUID().toString());
	static final String HMAC_KEY = OtpCryptoTests.randomKey();
	static final JsonMapper JSON = JsonMapper.builder().build();
	static ConfigurableApplicationContext application;
	static JdbcTemplate jdbc;
	static EmailOtpService service;
	static EmailOtpFixtures.FakeSender sender;
	CookieManager cookies;
	HttpClient client;

	@BeforeAll
	static void start() { CLOCK.reset(); startApplication(); }

	static void startApplication() {
		application = new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class)
				.web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
						"--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
						"--spring.datasource.username=" + POSTGRES.getUsername(),
						"--spring.datasource.password=" + POSTGRES.getPassword(),
						"--OTP_HMAC_KEY=" + HMAC_KEY, "--OTP_EMAIL_ENABLED=false", "--server.address=127.0.0.1",
						"--server.port=0", "--spring.session.jdbc.cleanup-cron=-");
		jdbc = application.getBean(JdbcTemplate.class);
		com.overload.testfixture.MixedPersistenceAssertions.verify(application);
		service = application.getBean(EmailOtpService.class);
		sender = application.getBean(EmailOtpFixtures.FakeSender.class);
	}

	@BeforeEach
	void isolate() {
		CLOCK.reset(); sender.reset();
		// The database belongs exclusively to this class; never a developer's Compose database.
		jdbc.update("DELETE FROM spring_session");
		jdbc.update("DELETE FROM email_otp_login_claims");
		jdbc.update("UPDATE email_otp_limits SET active_challenge_id = NULL");
		for (var table : List.of("email_otp_challenges", "email_otp_limits", "email_otp_rate_limits",
				"exercise_definitions", "user_workout_settings", "auth_identities", "users")) {
			jdbc.update("DELETE FROM " + table);
		}
		cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
	}

	@AfterEach
	void closeClient() { client.close(); }

	@AfterAll
	static void stop() { if (application != null) { application.close(); } }

	@Test
	void csrfRequiredForBothPublicOtpOperationsAndPrivateApisRemainProtected() throws Exception {
		for (var path : List.of("request", "verify")) {
			assertThat(post(path, Map.of("email", "a@example.com"), null).statusCode()).isEqualTo(401);
		}
		assertThat(sender.messages).isEmpty(); assertCounts(0, 0, 0, 0);
		assertThat(get("/__otp_test__/me").statusCode()).isEqualTo(401);
		assertThat(get("/api/v1/health").body()).isEqualTo("{\"status\":\"UP\"}");
		assertThat(send("POST", "/api/v1/health", Map.of(), null, null).statusCode()).isEqualTo(405);
	}

	@Test
	void realOtpCreatesAccountOnlyAfterVerificationAndRestoresJdbcSession() throws Exception {
		var csrf = token(); var anonymousId = cookie();
		var request = post("request", Map.of("email", "First.Last+tag@EXAMPLE.COM"), csrf);
		assertThat(request.statusCode()).isEqualTo(202);
		assertCounts(0, 0, 0, 0);
		var id = UUID.fromString(JSON.readTree(request.body()).get("challengeId").asString());
		var message = sender.messages.get(id);
		assertThat(message.recipient()).isEqualTo("First.Last+tag@example.com");
		assertThat(request.body().contains(message.code())).isFalse();
		assertThat(jdbc.queryForObject("SELECT octet_length(verifier) FROM email_otp_challenges", Integer.class)).isEqualTo(32);
		var verified = post("verify", verification(message), csrf);
		assertThat(verified.statusCode()).isEqualTo(204);
		assertThat(cookie()).isNotEqualTo(anonymousId);
		assertCounts(1, 1, 0, 1);
		assertThat(get("/__otp_test__/me").statusCode()).isEqualTo(200);
		assertThat(jdbc.queryForObject("SELECT principal_name IS NOT NULL FROM spring_session", Boolean.class)).isTrue();
		assertThat(send("POST", "/__otp_test__/mutate", Map.of(), csrf, null).statusCode()).isEqualTo(403);
		assertThat(send("POST", "/__otp_test__/mutate", Map.of(), token(), null).statusCode()).isEqualTo(204);
		assertThat(post("request", Map.of("email", "other@example.com"), token()).statusCode()).isEqualTo(409);
		assertThat(post("verify", verification(message), token()).statusCode()).isEqualTo(409);
		assertThat(post("verify", verification(message), token()).body()).contains("LOGOUT_REQUIRED");
		assertThat(jdbc.queryForObject("SELECT state FROM email_otp_challenges", String.class)).isEqualTo("CONSUMED");
		assertThat(jdbc.queryForObject("SELECT verifier = decode(repeat('00', 32), 'hex') FROM email_otp_challenges",
				Boolean.class)).isTrue();
	}

	@Test
	void reloginPreservesAccountGenerationEditsAndNeverMergesGoogleByEmail() throws Exception {
		var accounts = application.getBean(AccountProvisioningService.class);
		var google = accounts.provision(VerifiedIdentity.google(VerifiedIdentity.GOOGLE_ISSUER, "a@example.com"), null);
		var email = accounts.provision(VerifiedIdentity.email("a@example.com"), "보존할 닉네임");
		jdbc.update("DELETE FROM exercise_definitions WHERE user_id = ?", email.id());
		jdbc.update("DELETE FROM user_workout_settings WHERE user_id = ?", email.id());
		jdbc.update("UPDATE users SET data_generation = 7 WHERE id = ?", email.id());
		var definition = UUID.randomUUID();
		jdbc.update("INSERT INTO exercise_definitions (id, user_id, data_generation, name) VALUES (?, ?, 7, '개인 종목')",
				definition, email.id());
		jdbc.update("""
				INSERT INTO user_workout_settings (user_id, data_generation, rest_low_seconds, rest_medium_seconds, rest_high_seconds)
				VALUES (?, 7, 77, 66, 55)
				""", email.id());
		var csrf = token();
		var response = post("request", Map.of("email", "a@EXAMPLE.COM"), csrf);
		var id = UUID.fromString(JSON.readTree(response.body()).get("challengeId").asString());
		assertThat(post("verify", verification(sender.messages.get(id)), csrf).statusCode()).isEqualTo(204);
		var me = JSON.readTree(get("/__otp_test__/me?userId=" + google.id()).body());
		assertThat(me.get("id").asString()).isEqualTo(email.id().toString());
		assertThat(me.get("data_generation").asLong()).isEqualTo(7);
		assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id = ?", String.class, email.id())).isEqualTo("보존할 닉네임");
		assertThat(jdbc.queryForObject("SELECT id FROM exercise_definitions WHERE user_id = ?", UUID.class, email.id())).isEqualTo(definition);
		assertThat(jdbc.queryForObject("SELECT rest_low_seconds FROM user_workout_settings WHERE user_id = ?", Integer.class, email.id()))
				.isEqualTo(77);
		assertCounts(2, 2, 1, 2);
	}

	@Test
	void fiveMinuteExpiryAndSuccessfulConsumptionAreExactAndIrreversible() {
		var first = issue("a@example.com", "ip", "browser-a");
		CLOCK.advance(Duration.ofMinutes(5).minusNanos(1));
		assertThat(verify(first, "ip", "browser-a").id()).isNotNull();
		assertInvalid(() -> verify(first, "ip", "browser-a"));
		var second = issue("b@example.com", "ip", "browser-b");
		CLOCK.advance(Duration.ofMinutes(5));
		assertInvalid(() -> verify(second, "ip", "browser-b"));
		assertInvalid(() -> verify(second, "ip", "browser-b"));
		assertCounts(1, 1, 0, 1);
	}

	@Test
	void sixtySecondResendRevokesOldCodeAndKeepsTheFailureBudget() {
		var first = issue("a@example.com", "ip", "browser");
		for (int index = 0; index < 3; index++) { assertInvalid(() -> wrong(first, "ip", "browser")); }
		CLOCK.advance(Duration.ofSeconds(60).minusMillis(1));
		assertLimited(() -> issue("a@example.com", "ip", "browser"));
		CLOCK.advance(Duration.ofMillis(1));
		var second = issue("a@example.com", "ip", "browser");
		assertThat(jdbc.queryForObject("SELECT failed_attempts FROM email_otp_limits", Integer.class)).isEqualTo(3);
		assertInvalid(() -> verify(first, "ip", "browser"));
		assertThat(verify(second, "ip", "browser").id()).isNotNull();
	}

	@Test
	void fifthWrongAttemptExhaustsTheBudgetAndResendCannotResetIt() {
		var message = issue("a@example.com", "ip", "browser");
		for (int index = 0; index < 5; index++) { assertInvalid(() -> wrong(message, "ip", "browser")); }
		assertLimited(() -> verify(message, "ip", "browser"));
		CLOCK.advance(Duration.ofSeconds(60));
		assertLimited(() -> issue("a@example.com", "ip", "browser"));
		CLOCK.advance(Duration.ofMinutes(29));
		var newMessage = issue("a@example.com", "ip", "browser");
		assertThat(verify(newMessage, "ip", "browser").id()).isNotNull();
	}

	@Test
	void oneIssuanceNeverGetsMoreThanFiveGuessesAcrossTheObservationWindowBoundary() {
		issue("a@example.com", "ip", "browser");
		CLOCK.advance(Duration.ofMinutes(29).plusSeconds(30));
		var message = issue("a@example.com", "ip", "browser");
		for (int index = 0; index < 4; index++) { assertInvalid(() -> wrong(message, "ip", "browser")); }
		CLOCK.advance(Duration.ofSeconds(30));
		assertInvalid(() -> wrong(message, "ip", "browser"));
		assertLimited(() -> verify(message, "ip", "browser"));
		assertThat(jdbc.queryForObject("SELECT failed_attempts FROM email_otp_challenges WHERE id = ?", Integer.class,
				message.issuanceId())).isEqualTo(5);
	}

	@Test
	void wrongMissingAndForeignBrowserChallengesHaveTheSameGenericRejection() throws Exception {
		var csrf = token();
		var response = post("request", Map.of("email", "a@example.com"), csrf);
		var message = sender.messages.get(UUID.fromString(JSON.readTree(response.body()).get("challengeId").asString()));
		var wrong = post("verify", Map.of("email", message.recipient(), "challengeId", message.issuanceId(), "code", different(message.code())), csrf);
		var missing = post("verify", Map.of("email", message.recipient(), "challengeId", UUID.randomUUID(), "code", message.code()), csrf);
		assertThat(wrong.statusCode()).isEqualTo(401);
		assertThat(missing.statusCode()).isEqualTo(401);
		assertThat(wrong.body()).isEqualTo(missing.body()).isEqualTo("{\"code\":\"OTP_INVALID\"}");
		assertThat(wrong.body().contains(message.code()) || wrong.body().contains(message.recipient())).isFalse();
		assertInvalid(() -> verify(message, "other-ip", "different-browser"));
		assertCounts(0, 0, 0, 0);
	}

	@Test
	void keyRotationInvalidatesIssuedCodesWithoutResettingFailureCounters() {
		var message = issue("a@example.com", "ip", "browser");
		assertInvalid(() -> wrong(message, "ip", "browser"));
		var rotated = new EmailOtpService(jdbc, application.getBean(PlatformTransactionManager.class),
				new OtpCrypto(OtpCryptoTests.randomKey()), sender, application.getBean(AccountProvisioningService.class), CLOCK);
		assertInvalid(() -> rotated.verify(message.recipient(), message.issuanceId(), message.code(), "ip", "browser"));
		assertThat(jdbc.queryForObject("SELECT failed_attempts FROM email_otp_limits", Integer.class)).isEqualTo(2);
	}

	@Test
	void emailRequestLimitPersistsAcrossServerRestartAndRecoversAtTheWindowBoundary() {
		for (int index = 0; index < 5; index++) {
			issue("a@example.com", "ip", "browser"); CLOCK.advance(Duration.ofSeconds(60));
		}
		assertLimited(() -> issue("a@example.com", "ip", "browser"));
		application.close(); startApplication();
		assertLimited(() -> issue("a@example.com", "ip", "browser"));
		CLOCK.advance(Duration.ofHours(1).minusSeconds(300));
		assertThat(issue("a@example.com", "ip", "browser").issuanceId()).isNotNull();
	}

	@Test
	void forwardedHeadersDoNotBypassThePersistentRequestIpLimit() throws Exception {
		var csrf = token();
		for (int index = 0; index < 20; index++) {
			var response = send("POST", "/api/v1/auth/email-otp/request", Map.of("email", "user" + index + "@example.com"),
					csrf, "198.51.100." + index);
			assertThat(response.statusCode()).isEqualTo(202);
		}
		var limited = send("POST", "/api/v1/auth/email-otp/request", Map.of("email", "another@example.com"), csrf, "203.0.113.7");
		assertThat(limited.statusCode()).isEqualTo(429);
		assertThat(limited.headers().firstValue("Retry-After")).isPresent();
		assertThat(sender.messages.size()).isEqualTo(20);
	}

	@Test
	void verificationIpLimitIsIndependentOfEmailAndPurposeIsLoginOnly() {
		for (int index = 0; index < 100; index++) {
			var email = "user" + index + "@example.com";
			assertInvalid(() -> service.verify(email, UUID.randomUUID(), "000000", "same-ip", "browser"));
		}
		assertLimited(() -> service.verify("next@example.com", UUID.randomUUID(), "000000", "same-ip", "browser"));
		CLOCK.advance(Duration.ofMinutes(15));
		assertInvalid(() -> service.verify("recovered@example.com", UUID.randomUUID(), "000000", "same-ip", "browser"));
		assertThat(jdbc.queryForList("SELECT DISTINCT purpose FROM email_otp_rate_limits", String.class)).containsExactly("LOGIN");
	}

	@Test
	void parallelVerifyProducesOnlyOneAuthenticatedSessionAndNoOldIdAuthentication() throws Exception {
		var csrf = token(); var anonymous = cookie();
		var response = post("request", Map.of("email", "a@example.com"), csrf);
		var message = sender.messages.get(UUID.fromString(JSON.readTree(response.body()).get("challengeId").asString()));
		try (var executor = Executors.newFixedThreadPool(4)) {
			var gate = new CountDownLatch(1);
			var futures = new ArrayList<Future<HttpResponse<String>>>();
			for (int index = 0; index < 4; index++) {
				futures.add(executor.submit(() -> { gate.await(); return post("verify", verification(message), csrf); }));
			}
			gate.countDown();
			var responses = new ArrayList<HttpResponse<String>>();
			for (var future : futures) { responses.add(future.get(20, TimeUnit.SECONDS)); }
			assertThat(responses.stream().filter(value -> value.statusCode() == 204).count()).isEqualTo(1);
			var winner = responses.stream().filter(value -> value.statusCode() == 204).findFirst().orElseThrow();
			var cookie = winner.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION=") && !value.startsWith("SESSION=;"))
					.reduce((first, second) -> second).orElseThrow().split(";", 2)[0];
			try (var browser = HttpClient.newHttpClient()) {
				assertThat(browser.send(HttpRequest.newBuilder(uri("/__otp_test__/me")).header("Cookie", cookie).GET().build(),
						HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
				assertThat(browser.send(HttpRequest.newBuilder(uri("/__otp_test__/me")).header("Cookie", "SESSION=" + anonymous).GET().build(),
						HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
			}
			assertCounts(1, 1, 0, 1);
		}
	}

	@Test
	void concurrentRequestsReserveOneIntentAndVerifyResendRaceNeverReactivatesOldCode() throws Exception {
		try (var executor = Executors.newFixedThreadPool(4)) {
			var gate = new CountDownLatch(1); var accepted = new AtomicInteger();
			var futures = new ArrayList<Future<?>>();
			for (int index = 0; index < 4; index++) {
				futures.add(executor.submit(() -> {
					await(gate);
					try { issue("a@example.com", "ip", "browser"); accepted.incrementAndGet(); }
					catch (OtpFailure failure) { assertThat(failure.status).isEqualTo(429); }
				}));
			}
			gate.countDown(); for (var future : futures) { future.get(15, TimeUnit.SECONDS); }
			assertThat(accepted.get()).isEqualTo(1); assertThat(sender.messages.size()).isEqualTo(1);
			var old = sender.messages.values().iterator().next();
			CLOCK.advance(Duration.ofSeconds(60));
			var race = new CountDownLatch(1);
			var verify = executor.submit(() -> {
				await(race); try { verify(old, "ip", "browser"); } catch (OtpFailure failure) { assertThat(failure.status).isEqualTo(401); }
			});
			var resend = executor.submit(() -> { await(race); issue("a@example.com", "ip", "browser"); });
			race.countDown(); verify.get(15, TimeUnit.SECONDS); resend.get(15, TimeUnit.SECONDS);
			assertInvalid(() -> verify(old, "ip", "browser"));
			assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isLessThanOrEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM email_otp_challenges WHERE state = 'ACTIVE'", Integer.class)).isEqualTo(1);
		}
	}

	@Test
	void lateAcceptedSendCannotActivateASupersededPendingIntent() throws Exception {
		var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var calls = new AtomicInteger();
		sender.action = message -> { if (calls.incrementAndGet() == 1) { entered.countDown(); await(release); } };
		try (var executor = Executors.newSingleThreadExecutor()) {
			var first = executor.submit(() -> service.request("a@example.com", "ip", "browser"));
			try {
				assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				var pending = sender.messages.values().iterator().next();
				assertInvalid(() -> verify(pending, "ip", "browser"));
				CLOCK.advance(Duration.ofSeconds(60));
				var second = issue("a@example.com", "ip", "browser");
				release.countDown();
				assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(OtpFailure.class);
				assertInvalid(() -> verify(pending, "ip", "browser"));
				assertThat(verify(second, "ip", "browser").id()).isNotNull();
			} finally { release.countDown(); }
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"FAIL", "TIMEOUT", "CRASH"})
	void failedOrUncertainMailIntentsNeverAuthenticateAndRecoverWithANewIssuance(String mode) {
		sender.mode = mode;
		assertThatThrownBy(() -> service.request("a@example.com", "ip", "browser")).isInstanceOf(
				"CRASH".equals(mode) ? EmailOtpFixtures.SimulatedCrash.class : OtpFailure.class);
		var old = sender.messages.values().iterator().next();
		assertInvalid(() -> verify(old, "ip", "browser"));
		assertCounts(0, 0, 0, 0);
		sender.mode = "ACCEPT"; CLOCK.advance(Duration.ofSeconds(60));
		var fresh = issue("a@example.com", "ip", "browser");
		assertThat(verify(fresh, "ip", "browser").id()).isNotNull();
	}

	@Test
	void acceptedMailWithDatabaseActivationFailureRemainsPendingAndRecoversSafely() {
		trigger("email_otp_challenges", "NEW.state = 'ACTIVE'");
		try {
			assertThatThrownBy(() -> service.request("a@example.com", "ip", "browser"))
					.isInstanceOf(org.springframework.dao.DataAccessException.class);
			var message = sender.messages.values().iterator().next();
			assertThat(jdbc.queryForObject("SELECT state FROM email_otp_challenges", String.class)).isEqualTo("PENDING");
			assertInvalid(() -> verify(message, "ip", "browser"));
		} finally { jdbc.execute("DROP TRIGGER otp_test_fail ON email_otp_challenges"); }
		CLOCK.advance(Duration.ofSeconds(60));
		assertThat(verify(issue("a@example.com", "ip", "browser"), "ip", "browser").id()).isNotNull();
	}

	@Test
	void consumeDatabaseFailureRollsBackAndAccountFailureConsumesWithoutGrantingSession() throws Exception {
		var csrf = token();
		var request = post("request", Map.of("email", "a@example.com"), csrf);
		var message = sender.messages.get(UUID.fromString(JSON.readTree(request.body()).get("challengeId").asString()));
		trigger("email_otp_challenges", "NEW.state = 'CONSUMED'");
		try { assertThat(post("verify", verification(message), csrf).statusCode()).isEqualTo(503); }
		finally { jdbc.execute("DROP TRIGGER otp_test_fail ON email_otp_challenges"); }
		assertCounts(0, 0, 0, 0);
		trigger("user_workout_settings", "TRUE");
		try { assertThat(post("verify", verification(message), csrf).statusCode()).isEqualTo(503); }
		finally { jdbc.execute("DROP TRIGGER otp_test_fail ON user_workout_settings"); }
		assertCounts(0, 0, 0, 0);
		assertThat(get("/__otp_test__/me").statusCode()).isEqualTo(401);
		assertThat(post("verify", verification(message), csrf).statusCode()).isEqualTo(401);
		CLOCK.advance(Duration.ofSeconds(60));
		var retry = post("request", Map.of("email", "a@example.com"), csrf);
		var fresh = sender.messages.get(UUID.fromString(JSON.readTree(retry.body()).get("challengeId").asString()));
		assertThat(post("verify", verification(fresh), csrf).statusCode()).isEqualTo(204);
		assertCounts(1, 1, 0, 1);
	}

	@Test
	void sessionDatabaseFailureCannotCreateAReusableAuthenticatedCookie() throws Exception {
		var csrf = token(); var requested = post("request", Map.of("email", "a@example.com"), csrf);
		var message = sender.messages.get(UUID.fromString(JSON.readTree(requested.body()).get("challengeId").asString()));
		trigger("spring_session_attributes", "NEW.attribute_name = 'SPRING_SECURITY_CONTEXT'");
		try {
			var response = post("verify", verification(message), csrf);
			assertThat(response.statusCode()).withFailMessage("Session failure response status %s; attribute names %s",
					response.statusCode(), jdbc.queryForList("SELECT attribute_name FROM spring_session_attributes", String.class))
					.isGreaterThanOrEqualTo(500);
		}
		finally { jdbc.execute("DROP TRIGGER otp_test_fail ON spring_session_attributes"); }
		assertCounts(1, 1, 0, 1);
		assertThat(get("/__otp_test__/me").statusCode()).isEqualTo(401);
		CLOCK.advance(Duration.ofSeconds(60));
		var newCsrf = token(); var retry = post("request", Map.of("email", "a@example.com"), newCsrf);
		var fresh = sender.messages.get(UUID.fromString(JSON.readTree(retry.body()).get("challengeId").asString()));
		assertThat(post("verify", verification(fresh), newCsrf).statusCode()).isEqualTo(204);
		assertCounts(1, 1, 0, 1);
	}

	@Test
	void missingConfigurationAndAmbientTransactionsFailClosed() {
		sender.available = false;
		assertThatThrownBy(() -> issue("a@example.com", "ip", "browser")).isInstanceOf(OtpFailure.class).hasMessage("OTP_UNAVAILABLE");
		assertThat(sender.messages).isEmpty();
		var weak = new EmailOtpService(jdbc, application.getBean(PlatformTransactionManager.class), new OtpCrypto(""), sender,
				application.getBean(AccountProvisioningService.class), CLOCK);
		assertThatThrownBy(() -> weak.verify("a@example.com", UUID.randomUUID(), "000000", "ip", "browser"))
				.isInstanceOf(OtpFailure.class).hasMessage("OTP_UNAVAILABLE");
		assertThatThrownBy(() -> new TransactionTemplate(application.getBean(PlatformTransactionManager.class))
				.execute(status -> service.request("a@example.com", "ip", "browser")))
				.isInstanceOf(IllegalStateException.class);
		assertCounts(0, 0, 0, 0);
	}

	@Test
	void leadingZeroCodeIsPreservedThroughHmacDatabaseAndVerification() {
		var crypto = new OtpCrypto(HMAC_KEY, new java.security.SecureRandom() {
			@Override public int nextInt(int bound) { return 7; }
		});
		var leading = new EmailOtpService(jdbc, application.getBean(PlatformTransactionManager.class), crypto,
				sender, application.getBean(AccountProvisioningService.class), CLOCK);
		var id = leading.request("a@example.com", "ip", "browser");
		var message = sender.messages.get(id);
		assertThat(message.code().length()).isEqualTo(6);
		assertThat(message.code().startsWith("00000")).isTrue();
		assertThat(leading.verify(message.recipient(), id, message.code(), "ip", "browser").id()).isNotNull();
		assertCounts(1, 1, 0, 1);
	}

	@Test
	void emailVerificationRateCapsEvenAlreadyProvenButPreviouslyClaimedBrowserRetries() {
		var first = issue("a@example.com", "ip", "browser");
		verify(first, "ip", "browser");
		CLOCK.advance(Duration.ofSeconds(60));
		var another = issue("a@example.com", "ip", "browser");
		for (int index = 0; index < 19; index++) { assertInvalid(() -> verify(another, "ip", "browser")); }
		assertLimited(() -> verify(another, "ip", "browser"));
		CLOCK.advance(Duration.ofMinutes(14));
		assertInvalid(() -> verify(another, "ip", "browser"));
		assertCounts(1, 1, 0, 1);
	}

	@Test
	void interruptionAfterConsumptionRequiresNewBrowserProofAndNeverReplaysTheCode() throws Exception {
		var csrf = token(); var requested = post("request", Map.of("email", "a@example.com"), csrf);
		var message = sender.messages.get(UUID.fromString(JSON.readTree(requested.body()).get("challengeId").asString()));
		var stored = application.getBean(org.springframework.session.SessionRepository.class).findById(cookie());
		String nonce = stored.getAttribute(EmailOtpController.class.getName() + ".browserNonce");
		var interruptedAccounts = org.mockito.Mockito.spy(application.getBean(AccountProvisioningService.class));
		org.mockito.Mockito.doThrow(new EmailOtpFixtures.SimulatedCrash()).when(interruptedAccounts)
				.provision(org.mockito.ArgumentMatchers.any(VerifiedIdentity.class), org.mockito.ArgumentMatchers.isNull());
		var interrupted = new EmailOtpService(jdbc, application.getBean(PlatformTransactionManager.class),
				new OtpCrypto(HMAC_KEY), sender, interruptedAccounts, CLOCK);
		assertThatThrownBy(() -> interrupted.verify(message.recipient(), message.issuanceId(), message.code(), "ip", nonce))
				.isInstanceOf(EmailOtpFixtures.SimulatedCrash.class);
		assertCounts(0, 0, 0, 0);
		assertThat(jdbc.queryForObject("SELECT state FROM email_otp_challenges", String.class)).isEqualTo("CONSUMED");
		assertThat(get("/__otp_test__/me").statusCode()).isEqualTo(401);
		assertThat(post("verify", verification(message), csrf).statusCode()).isEqualTo(401);
		assertThat(send("POST", "/api/v1/auth/logout", Map.of(), csrf, null).statusCode()).isEqualTo(204);
		CLOCK.advance(Duration.ofSeconds(60));
		var freshCsrf = token(); var retry = post("request", Map.of("email", "a@example.com"), freshCsrf);
		var fresh = sender.messages.get(UUID.fromString(JSON.readTree(retry.body()).get("challengeId").asString()));
		assertThat(post("verify", verification(fresh), freshCsrf).statusCode()).isEqualTo(204);
		assertCounts(1, 1, 0, 1);
	}

	@Test
	void twoDifferentEmailsInTheSameAnonymousBrowserCannotRaceIntoTwoAccounts() throws Exception {
		var first = issue("a@example.com", "ip", "browser");
		var second = issue("b@example.com", "ip", "browser");
		try (var executor = Executors.newFixedThreadPool(2)) {
			var gate = new CountDownLatch(1); var successes = new AtomicInteger();
			var futures = new ArrayList<Future<?>>();
			for (var message : List.of(first, second)) {
				futures.add(executor.submit(() -> {
					await(gate);
					try { verify(message, "ip", "browser"); successes.incrementAndGet(); }
					catch (OtpFailure failure) { assertThat(failure.status).isEqualTo(401); }
				}));
			}
			gate.countDown(); for (var future : futures) { future.get(15, TimeUnit.SECONDS); }
			assertThat(successes.get()).isEqualTo(1); assertCounts(1, 1, 0, 1);
		}
	}

	@Test
	void schemaHasDurableConstraintsAndIndexesAndMigrationIsNotRepeated() {
		assertThat(application.getBean(Flyway.class).info().current().getVersion().toString()).isEqualTo("18");
		assertThat(application.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
		assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'email_otp_challenges'", String.class))
				.contains("email_otp_challenges_pkey", "email_otp_challenges_expiry_index");
		assertThatThrownBy(() -> jdbc.update("INSERT INTO email_otp_rate_limits VALUES (?, 'LINK', CURRENT_TIMESTAMP, 0)", "0".repeat(64)))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	private OtpEmailSender.Message issue(String email, String ip, String browser) {
		return sender.messages.get(service.request(email, ip, browser));
	}
	private com.overload.overload_server.account.Account verify(OtpEmailSender.Message message, String ip, String browser) {
		return service.verify(message.recipient(), message.issuanceId(), message.code(), ip, browser);
	}
	private void wrong(OtpEmailSender.Message message, String ip, String browser) {
		service.verify(message.recipient(), message.issuanceId(), different(message.code()), ip, browser);
	}
	private static String different(String code) { return (code.charAt(0) == '0' ? "1" : "0") + code.substring(1); }
	private static void assertInvalid(Runnable operation) {
		assertThatThrownBy(operation::run).isInstanceOfSatisfying(OtpFailure.class, error -> assertThat(error.status).isEqualTo(401));
	}
	private static void assertLimited(Runnable operation) {
		assertThatThrownBy(operation::run).isInstanceOfSatisfying(OtpFailure.class, error -> {
			assertThat(error.status).isEqualTo(429); assertThat(error.retryAfter).isPositive();
		});
	}
	private static void await(CountDownLatch latch) {
		try { if (!latch.await(15, TimeUnit.SECONDS)) { throw new AssertionError("Test coordination timeout"); } }
		catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError("Test interrupted"); }
	}
	private static void assertCounts(int users, int identities, int exercises, int settings) {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(users);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identities", Integer.class)).isEqualTo(identities);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM exercise_definitions", Integer.class)).isEqualTo(exercises);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_workout_settings", Integer.class)).isEqualTo(settings);
	}
	private static void trigger(String table, String condition) {
		jdbc.execute("""
				CREATE OR REPLACE FUNCTION otp_test_fail() RETURNS trigger LANGUAGE plpgsql AS $$
				BEGIN RAISE EXCEPTION 'otp test SQL failure' USING ERRCODE = '23514'; END $$
				""");
		jdbc.execute("CREATE TRIGGER otp_test_fail BEFORE INSERT OR UPDATE ON " + table
				+ " FOR EACH ROW WHEN (" + condition + ") EXECUTE FUNCTION otp_test_fail()");
	}
	private static Map<String, Object> verification(OtpEmailSender.Message message) {
		return Map.of("email", message.recipient(), "challengeId", message.issuanceId(), "code", message.code());
	}
	private String token() throws Exception { return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString(); }
	private String cookie() {
		return cookies.getCookieStore().getCookies().stream().filter(value -> value.getName().equals("SESSION"))
				.findFirst().orElseThrow().getValue();
	}
	private HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null, null); }
	private HttpResponse<String> post(String suffix, Object body, String csrf) throws Exception {
		return send("POST", "/api/v1/auth/email-otp/" + suffix, body, csrf, null);
	}
	private HttpResponse<String> send(String method, String path, Object body, String csrf, String forwarded) throws Exception {
		var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20));
		if (csrf != null) { builder.header("X-CSRF-TOKEN", csrf); }
		if (forwarded != null) { builder.header("X-Forwarded-For", forwarded); }
		if (body != null) { builder.header("Content-Type", "application/json"); }
		return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
	}
	private static URI uri(String path) {
		return URI.create("http://127.0.0.1:" + ((WebServerApplicationContext) application).getWebServer().getPort() + path);
	}

}
