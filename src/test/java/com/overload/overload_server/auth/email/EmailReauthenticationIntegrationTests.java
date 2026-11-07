package com.overload.overload_server.auth.email;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.overload_server.security.SessionMetadata;
import com.overload.overload_server.security.SessionPrincipal;
import com.overload.testfixture.EmailOtpFixtures;
import com.overload.testfixture.ReauthenticationFixtures;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.overload.testfixture.EmailOtpFixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres")
@Testcontainers
class EmailReauthenticationIntegrationTests {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_reauth_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
	static final String HMAC = OtpCryptoTests.randomKey();
	static final JsonMapper JSON = JsonMapper.builder().build();
	static ConfigurableApplicationContext app;
	static JdbcTemplate jdbc;
	static EmailOtpFixtures.FakeSender sender;
	Browser browser;
	UUID identity;

	@BeforeAll static void start() { CLOCK.reset(); startApplication(); }
	static void startApplication() {
		app = new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class, ReauthenticationFixtures.class)
				.web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
						"--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
						"--spring.datasource.password=" + POSTGRES.getPassword(), "--OTP_HMAC_KEY=" + HMAC,
						"--OTP_EMAIL_ENABLED=false", "--GOOGLE_OIDC_ENABLED=false", "--server.address=127.0.0.1", "--server.port=0",
						"--spring.session.jdbc.cleanup-cron=-", "--logging.level.root=WARN", "--spring.main.banner-mode=off");
		jdbc = app.getBean(JdbcTemplate.class); sender = app.getBean(EmailOtpFixtures.FakeSender.class);
		com.overload.testfixture.MixedPersistenceAssertions.verify(app);
	}
	@BeforeEach void isolate() {
		ReauthenticationFixtures.reset(); CLOCK.reset(); sender.reset();
		jdbc.execute("DROP TRIGGER IF EXISTS reauth_fail ON spring_session_attributes");
		jdbc.execute("DROP TRIGGER IF EXISTS reauth_fail ON email_otp_challenges");
		// Only the disposable container created by this test class. No Compose DB connections.
		jdbc.update("DELETE FROM spring_session"); jdbc.update("DELETE FROM email_otp_login_claims");
		jdbc.update("UPDATE email_otp_limits SET active_challenge_id = NULL");
		for (var table : List.of("email_otp_challenges", "email_otp_limits", "email_otp_rate_limits", "deload_weekly_assessments", "deload_analysis_versions", "exercise_definitions", "user_workout_settings", "auth_identities", "users")) { jdbc.update("DELETE FROM " + table); }
		browser = new Browser();
	}
	@AfterEach void close() { ReauthenticationFixtures.reset(); browser.close(); }
	@AfterAll static void stop() { if (app != null) { app.close(); } }

	@Test void identitiesExposeOnlyOwnedIdsAndProvidersAndNeverAddressOrSubject() throws Exception {
		assertThat(browser.get("/api/v1/auth/identities").statusCode()).isEqualTo(401);
		login(browser, "owner@example.test");
		var other = app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email("other@example.test"), null);
		var foreign = jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=?", UUID.class, other.id());
		var result = browser.get("/api/v1/auth/identities");
		assertThat(result.statusCode()).isEqualTo(200);
		assertThat(JSON.readTree(result.body()).get("identities").size()).isEqualTo(1);
		assertThat(JSON.readTree(result.body()).get("identities").get(0).size()).isEqualTo(2);
		assertThat(result.body()).contains(identity.toString(), "email").doesNotContain("@", "subject", foreign.toString());
		assertThat(result.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
		int sent = sender.messages.size();
		assertThat(browser.post(reauth("request"), Map.of("identityId",foreign), browser.csrf()).statusCode()).isEqualTo(400);
		assertThat(browser.post(reauth("verify"), Map.of("identityId",foreign,"challengeId",UUID.randomUUID(),"code","000007"), browser.csrf()).statusCode()).isEqualTo(400);
		assertThat(sender.messages.size()).isEqualTo(sent);
	}

	@Test void anonymousRequestsAndMissingCsrfAreRejected() throws Exception {
		for (var path : List.of("request", "verify")) {
			assertThat(browser.post(reauth(path), Map.of(), null).statusCode()).isEqualTo(401);
		}
		login(browser, "owner@example.test");
		for (var path : List.of("request", "verify")) {
			assertThat(browser.post(reauth(path), Map.of(), null).statusCode()).isEqualTo(403);
		}
		assertThat(sender.messages).hasSize(1);
	}

	@Test void successRotatesPrimaryKeyCookieAndCsrfPreservingOriginalUserDeadlineAndData() throws Exception {
		login(browser, "owner@example.test");
		var before = metadata(browser); var oldId = browser.cookie();
		var oldPrimary = jdbc.queryForObject("SELECT primary_id FROM spring_session WHERE session_id=?", String.class, oldId);
		var data = snapshotData();
		CLOCK.advance(Duration.ofMinutes(11));
		assertThat(browser.get("/__reauth_test__/recent").statusCode()).isEqualTo(403);
		var oldCsrf = browser.csrf(); var message = issue(browser);
		var result = verify(browser, message.issuanceId(), message.code());
		assertThat(result.statusCode()).isEqualTo(204);
		assertThat(browser.cookie().equals(oldId)).isFalse();
		var after = metadata(browser);
		assertThat(after.userId().equals(before.userId())).isTrue();
		assertThat(after.loginStartedAt()).isEqualTo(before.loginStartedAt());
		assertThat(after.absoluteExpiresAt()).isEqualTo(before.absoluteExpiresAt());
		assertThat(after.reauthenticatedAt()).isEqualTo(CLOCK.instant());
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE primary_id=?", Integer.class, oldPrimary)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session_attributes WHERE session_primary_id=?", Integer.class, oldPrimary)).isZero();
		assertThat(state(message)).isEqualTo("CONSUMED");
		assertThat(jdbc.queryForObject("SELECT verifier = decode(repeat('00',32),'hex') FROM email_otp_challenges WHERE id=?",
				Boolean.class, message.issuanceId())).isTrue();
		assertThat(snapshotData()).isEqualTo(data);
		assertThat(browser.get("/api/v1/auth/session").statusCode()).isEqualTo(200);
		assertThat(browser.post("/__otp_test__/mutate", Map.of(), oldCsrf).statusCode()).isEqualTo(403);
		assertThat(browser.post("/__otp_test__/mutate", Map.of(), browser.csrf()).statusCode()).isEqualTo(204);
		try (var obsolete = new Browser(oldId)) { assertThat(obsolete.get("/api/v1/auth/session").statusCode()).isEqualTo(401); }
		assertThat(browser.get("/__reauth_test__/recent").statusCode()).isEqualTo(204);
		CLOCK.advance(Duration.ofMinutes(10));
		assertThat(browser.get("/__reauth_test__/recent").statusCode()).isEqualTo(204);
		CLOCK.advance(Duration.ofNanos(1));
		assertThat(browser.get("/__reauth_test__/recent").statusCode()).isEqualTo(403);
		assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(401);
	}

	@Test void loginAndReauthCannotConsumeEachOthersCodesAndHaveSeparateLimitsAndClaims() throws Exception {
		login(browser, "owner@example.test");
		var login = app.getBean(EmailOtpService.class);
		CLOCK.advance(Duration.ofSeconds(60));
		var id = login.request("owner@example.test", "isolated-ip", "anonymous-browser");
		var loginMessage = sender.messages.get(id);
		var reauth = issue(browser);
		assertThat(loginMessage.purpose()).isEqualTo(OtpPurpose.LOGIN);
		assertThat(reauth.purpose()).isEqualTo(OtpPurpose.REAUTH);
		assertThat(verify(browser, id, loginMessage.code()).statusCode()).isEqualTo(401);
		try (var anonymous = new Browser()) {
			assertThat(anonymous.post("/api/v1/auth/email-otp/verify", Map.of("email", reauth.recipient(),
					"challengeId", reauth.issuanceId(), "code", reauth.code()), anonymous.csrf()).statusCode()).isEqualTo(401);
		}
		assertThat(state(loginMessage)).isEqualTo("ACTIVE"); assertThat(state(reauth)).isEqualTo("ACTIVE");
		assertThat(verify(browser, reauth.issuanceId(), reauth.code()).statusCode()).isEqualTo(204);
		assertThat(login.verify(loginMessage.recipient(), id, loginMessage.code(), "isolated-ip", "anonymous-browser").id()
				.equals(metadata(browser).userId())).isTrue();
		assertThat(count("SELECT count(*) FROM email_otp_login_claims")).isEqualTo(2);
	}

	@Test void anotherDeviceForSameUserCannotUseThisSessionsProof() throws Exception {
		login(browser, "owner@example.test");
		CLOCK.advance(Duration.ofSeconds(60));
		try (var device = new Browser()) {
			login(device, "owner@example.test");
			var message = issue(browser);
			assertThat(verify(device, message.issuanceId(), message.code()).statusCode()).isEqualTo(401);
			assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(204);
		}
	}

	@ParameterizedTest @ValueSource(strings = {"logout", "rotate", "user", "metadata", "expire"})
	void persistedSessionChangesBeforeConsumptionCannotReauthenticate(String change) throws Exception {
		login(browser, "owner@example.test"); var message = issue(browser); var oldId = browser.cookie();
		var csrf = browser.csrf();
		ReauthenticationFixtures.beforeValidate = snapshot -> {
			ReauthenticationFixtures.beforeValidate = null;
			// A different committed connection models another request; the OTP rollback must not undo that logout/change.
			var concurrent = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
					POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
			if (change.equals("logout")) { concurrent.update("DELETE FROM spring_session WHERE session_id=?", oldId); }
			if (change.equals("rotate")) { concurrent.update("UPDATE spring_session SET session_id=? WHERE session_id=?", UUID.randomUUID().toString(), oldId); }
			if (change.equals("expire")) { CLOCK.advance(Duration.ofDays(90)); }
			if (change.equals("user")) {
				writeAttribute(concurrent, oldId, "SPRING_SECURITY_CONTEXT", new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
						new SessionPrincipal(UUID.randomUUID()), null, AuthorityUtils.createAuthorityList("ROLE_USER"))));
			}
			if (change.equals("metadata")) {
				var m = snapshot.metadata(); writeAttribute(concurrent, oldId, SessionMetadata.ATTRIBUTE,
						new SessionMetadata(m.userId(), m.loginStartedAt(), m.absoluteExpiresAt().plusSeconds(1), m.reauthenticatedAt()));
			}
		};
		assertThat(browser.post(reauth("verify"), body(message.issuanceId(), message.code()), csrf).statusCode()).isEqualTo(401);
		assertThat(state(message)).isEqualTo("ACTIVE");
		if (change.equals("logout") || change.equals("rotate")) {
			assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE session_id=?", Integer.class, oldId)).isZero();
		}
	}

	@Test void logoutAndExpiryBeforeIssuanceActivationCannotCreateUsableChallenge() throws Exception {
		login(browser, "owner@example.test");
		sender.action = ignored -> jdbc.update("DELETE FROM spring_session WHERE session_id=?", browser.cookie());
		assertThat(request(browser).statusCode()).isEqualTo(401);
		assertThat(count("SELECT count(*) FROM email_otp_challenges WHERE purpose='REAUTH' AND state='ACTIVE'")).isZero();
	}

	@Test void resendAtSixtySecondsRevokesOldAndDoesNotResetAccumulatedFailureBudget() throws Exception {
		login(browser, "owner@example.test"); var first = issue(browser);
		for (int i = 0; i < 4; i++) { assertThat(verify(browser, first.issuanceId(), wrong(first.code())).statusCode()).isEqualTo(401); }
		CLOCK.advance(Duration.ofSeconds(60).minusMillis(1));
		assertThat(request(browser).statusCode()).isEqualTo(429);
		CLOCK.advance(Duration.ofMillis(1)); var second = issue(browser);
		assertThat(state(first)).isEqualTo("REVOKED");
		assertThat(verify(browser, first.issuanceId(), first.code()).statusCode()).isEqualTo(401);
		assertThat(verify(browser, second.issuanceId(), second.code()).statusCode()).isEqualTo(429);
		CLOCK.advance(Duration.ofSeconds(60)); assertThat(request(browser).statusCode()).isEqualTo(429);
		assertThat(count("SELECT failed_attempts FROM email_otp_limits WHERE purpose='REAUTH'")).isEqualTo(5);
	}

	@Test void fiveWrongAttemptsLockCurrentIssuanceAndWindowRecoveryRequiresFreshCode() throws Exception {
		login(browser, "owner@example.test"); var message = issue(browser);
		for (int i = 0; i < 5; i++) { assertThat(verify(browser, message.issuanceId(), wrong(message.code())).statusCode()).isEqualTo(401); }
		var blocked = verify(browser, message.issuanceId(), message.code());
		assertThat(blocked.statusCode()).isEqualTo(429); assertThat(blocked.headers().firstValue("Retry-After")).isPresent();
		CLOCK.advance(Duration.ofMinutes(30)); var fresh = issue(browser);
		assertThat(verify(browser, fresh.issuanceId(), fresh.code()).statusCode()).isEqualTo(204);
	}

	@ParameterizedTest @ValueSource(longs = {299999, 300000})
	void fiveMinuteExpiryBoundary(long milliseconds) throws Exception {
		login(browser, "owner@example.test"); var message = issue(browser); CLOCK.advance(Duration.ofMillis(milliseconds));
		assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(milliseconds < 300000 ? 204 : 401);
	}

	@ParameterizedTest @ValueSource(strings = {"7", "12345", "1234567", " 000007", "abcdef"})
	void malformedCodeIsRejected(String value) throws Exception {
		login(browser, "owner@example.test"); var message = issue(browser);
		assertThat(verify(browser, message.issuanceId(), value).statusCode()).isEqualTo(400);
		assertThat(state(message)).isEqualTo("ACTIVE");
	}

	@Test void leadingZeroIsVerifiedAsAStringAndNeverStoredPlaintext() throws Exception {
		login(browser, "owner@example.test"); var message = issue(browser); String code = "000007";
		var hash = jdbc.queryForObject("SELECT binding_hash FROM email_otp_challenges WHERE id=?", String.class, message.issuanceId());
		var verifier = new OtpCrypto(HMAC).verifier(OtpPurpose.REAUTH, message.recipient(), message.issuanceId(), code, hash);
		jdbc.update("UPDATE email_otp_challenges SET verifier=? WHERE id=?", verifier, message.issuanceId());
		assertThat(verify(browser, message.issuanceId(), code).statusCode()).isEqualTo(204);
		assertThat(new EmailReauthenticationController.Verification(message.issuanceId(), code).toString().contains(code)).isFalse();
	}

	@ParameterizedTest @ValueSource(strings = {"attributes", "consume"})
	void sqlFailureRollsBackConsumptionAndSessionTogetherAndCanBeRetried(String target) throws Exception {
		login(browser, "owner@example.test"); var before = metadata(browser); var old = browser.cookie(); var message = issue(browser);
		String table = target.equals("attributes") ? "spring_session_attributes" : "email_otp_challenges";
		jdbc.execute("CREATE OR REPLACE FUNCTION reauth_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated SQL fault' USING ERRCODE='23514'; END $$");
		jdbc.execute("CREATE TRIGGER reauth_fail BEFORE " + (target.equals("attributes") ? "INSERT" : "UPDATE") + " ON " + table
				+ " FOR EACH ROW " + (target.equals("consume") ? "WHEN (NEW.state='CONSUMED') " : "") + "EXECUTE FUNCTION reauth_fail()");
		assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(503);
		assertThat(state(message)).isEqualTo("ACTIVE"); assertThat(metadata(browser)).isEqualTo(before);
		assertThat(browser.cookie().equals(old)).isTrue();
		jdbc.execute("DROP TRIGGER reauth_fail ON " + table);
		assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(204);
	}

	@Test void senderFailureAndPendingDeliveryAreNeverSuccessfulProofs() throws Exception {
		login(browser, "owner@example.test"); sender.mode = "FAIL";
		assertThat(request(browser).statusCode()).isEqualTo(503);
		assertThat(count("SELECT count(*) FROM email_otp_challenges WHERE purpose='REAUTH' AND state='FAILED'")).isEqualTo(1);
		assertThat(browser.get("/api/v1/auth/session").statusCode()).isEqualTo(200);
	}

	@Test void pendingCodeCannotBeVerifiedBeforeSenderReturns() throws Exception {
		login(browser, "owner@example.test");
		sender.action = message -> {
			try { assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(401); }
			catch (Exception failure) { throw new AssertionError(failure); }
		};
		var message = issue(browser); sender.action = null;
		assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(204);
	}

	@ParameterizedTest @ValueSource(strings = {"before-write", "after-write", "readback", "cookie", "final-cookie"})
	void expiryDuringPersistenceOrBeforeSuccessNeverReturns204(String point) throws Exception {
		login(browser, "owner@example.test"); var message = issue(browser);
		if (point.equals("before-write")) { ReauthenticationFixtures.beforeRotate = ignored -> CLOCK.advance(Duration.ofDays(90)); }
		if (point.equals("after-write")) { ReauthenticationFixtures.afterRotate = ignored -> CLOCK.advance(Duration.ofDays(90)); }
		var old = browser.cookie(); var reads = new AtomicInteger();
		if (point.equals("readback") || point.equals("cookie") || point.equals("final-cookie")) {
			int target = point.equals("readback") ? 1 : point.equals("cookie") ? 2 : 3;
			ReauthenticationFixtures.afterRead = session -> {
				if (!session.getId().equals(old) && reads.incrementAndGet() == target) { CLOCK.advance(Duration.ofDays(90)); }
			};
		}
		assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(401);
		ReauthenticationFixtures.reset();
		assertThat(browser.get("/api/v1/auth/session").statusCode()).isEqualTo(401);
		assertThat(state(message)).isEqualTo(point.equals("before-write") || point.equals("after-write") ? "ACTIVE" : "CONSUMED");
	}

	@Test void storeReadbackFailureAfterCommitConsumesProofButDiscardsSessionAndRequiresLogin() throws Exception {
		login(browser, "owner@example.test"); var message = issue(browser); var old = browser.cookie();
		ReauthenticationFixtures.afterRead = session -> {
			if (!session.getId().equals(old)) { throw new DataAccessResourceFailureException("isolated store fault"); }
		};
		assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(503);
		ReauthenticationFixtures.reset();
		assertThat(state(message)).isEqualTo("CONSUMED");
		assertThat(browser.get("/api/v1/auth/session").statusCode()).isEqualTo(401);
		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL")).isZero();
	}

	@Test void concurrentVerificationHasExactlyOneSuccessAndCannotResurrectOldPrimaryKey() throws Exception {
		login(browser, "owner@example.test"); var message = issue(browser); var old = browser.cookie(); var csrf = browser.csrf();
		try (var first = new Browser(old); var second = new Browser(old); var executor = Executors.newFixedThreadPool(2)) {
			var gate = new CountDownLatch(1); var futures = new ArrayList<Future<Integer>>();
			for (var current : List.of(first, second)) {
				futures.add(executor.submit(() -> { gate.await(10, TimeUnit.SECONDS);
					return current.post(reauth("verify"), body(message.issuanceId(), message.code()), csrf).statusCode(); }));
			}
			gate.countDown(); var statuses = new ArrayList<Integer>();
			for (var future : futures) { statuses.add(future.get(20, TimeUnit.SECONDS)); }
			assertThat(statuses).containsExactlyInAnyOrder(204, 401);
			assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL")).isEqualTo(1);
			assertThat(jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE session_id=?", Integer.class, old)).isZero();
		}
	}

	@Test void emailRequestLimitAndForwardedHeaderCannotBeResetByRestart() throws Exception {
		login(browser, "owner@example.test");
		for (int i = 0; i < 5; i++) { issue(browser); CLOCK.advance(Duration.ofSeconds(60)); }
		assertThat(request(browser).statusCode()).isEqualTo(429);
		var cookie = browser.cookie(); var csrf = browser.csrf();
		app.close(); startApplication();
		try (var restored = new Browser(cookie)) {
			assertThat(restored.post(reauth("request"), Map.of(), csrf).statusCode()).isEqualTo(429);
			assertThat(restored.get("/api/v1/auth/session").statusCode()).isEqualTo(200);
		}
		CLOCK.advance(Duration.ofHours(1));
		assertThat(request(browser).statusCode()).isEqualTo(202);
	}

	@ParameterizedTest @ValueSource(strings = {"request", "verify"})
	void socketPeerRateLimitsIgnoreChangingForwardedHeaders(String operation) throws Exception {
		login(browser, "owner@example.test"); var message = issue(browser);
		var key = OtpCrypto.identifier(operation + "-ip", "127.0.0.1");
		jdbc.update("UPDATE email_otp_rate_limits SET hits=? WHERE bucket_key=? AND purpose='REAUTH'", operation.equals("request") ? 20 : 100, key);
		if (operation.equals("verify")) {
			jdbc.update("INSERT INTO email_otp_rate_limits VALUES (?,'REAUTH',?,100) ON CONFLICT (bucket_key,purpose) DO UPDATE SET hits=100",
					key, java.sql.Timestamp.from(CLOCK.instant()));
		}
		for (int i = 0; i < 2; i++) {
			var response = operation.equals("request") ? request(browser) : verify(browser, message.issuanceId(), message.code());
			assertThat(response.statusCode()).isEqualTo(429);
		}
		assertThat(state(message)).isEqualTo("ACTIVE");
	}

	@Test void concurrentResendAndVerificationCannotBothSucceed() throws Exception {
		login(browser, "owner@example.test"); var message = issue(browser); CLOCK.advance(Duration.ofSeconds(60));
		var csrf = browser.csrf(); var id = browser.cookie();
		try (var issuing = new Browser(id); var verifying = new Browser(id); var executor = Executors.newFixedThreadPool(2)) {
			var gate = new CountDownLatch(1);
			var request = executor.submit(() -> { gate.await(10, TimeUnit.SECONDS);
				return issuing.post(reauth("request"), Map.of(), csrf).statusCode(); });
			var proof = executor.submit(() -> { gate.await(10, TimeUnit.SECONDS);
				return verifying.post(reauth("verify"), body(message.issuanceId(), message.code()), csrf).statusCode(); });
			gate.countDown(); int issued = request.get(20, TimeUnit.SECONDS), verified = proof.get(20, TimeUnit.SECONDS);
			assertThat((issued == 202 && verified == 401) || (issued == 401 && verified == 204)).isTrue();
		}
	}

	@ParameterizedTest @ValueSource(strings = {"principal", "deadline"})
	void changedNewSessionAttributesBeforeCommitRollbackTheWholeProof(String fault) throws Exception {
		login(browser, "owner@example.test"); var before = metadata(browser); var message = issue(browser);
		ReauthenticationFixtures.afterRotate = rotation -> {
			if (fault.equals("principal")) {
				writeAttribute(rotation.sessionId(), "SPRING_SECURITY_CONTEXT", new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
						new SessionPrincipal(UUID.randomUUID()), null, AuthorityUtils.createAuthorityList("ROLE_USER"))));
			} else {
				var m = rotation.metadata(); writeAttribute(rotation.sessionId(), SessionMetadata.ATTRIBUTE,
						new SessionMetadata(m.userId(), m.loginStartedAt(), m.absoluteExpiresAt().plusSeconds(1), m.reauthenticatedAt()));
			}
		};
		assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(401);
		ReauthenticationFixtures.reset(); assertThat(metadata(browser)).isEqualTo(before); assertThat(state(message)).isEqualTo("ACTIVE");
	}

	@Test void successfulStoreReadbackMustMatchMetadataAndPrincipal() throws Exception {
		login(browser, "owner@example.test"); var message = issue(browser); var old = browser.cookie();
		ReauthenticationFixtures.afterRead = session -> {
			if (!session.getId().equals(old)) {
				var m = (SessionMetadata) session.getAttribute(SessionMetadata.ATTRIBUTE);
				session.setAttribute(SessionMetadata.ATTRIBUTE, new SessionMetadata(UUID.randomUUID(), m.loginStartedAt(), m.absoluteExpiresAt(), m.reauthenticatedAt()));
			}
		};
		assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(401);
		ReauthenticationFixtures.reset(); assertThat(state(message)).isEqualTo("CONSUMED");
		assertThat(browser.get("/api/v1/auth/session").statusCode()).isEqualTo(401);
	}

	@Test void originalNinetyDayDeadlineIsStillEnforcedAfterReauthentication() throws Exception {
		login(browser, "owner@example.test"); CLOCK.advance(Duration.ofDays(89)); var message = issue(browser);
		assertThat(verify(browser, message.issuanceId(), message.code()).statusCode()).isEqualTo(204);
		CLOCK.advance(Duration.ofDays(1).minusNanos(1)); assertThat(browser.get("/api/v1/auth/session").statusCode()).isEqualTo(200);
		CLOCK.advance(Duration.ofNanos(1)); assertThat(browser.get("/api/v1/auth/session").statusCode()).isEqualTo(401);
	}

	@Test void identityIdIsRejectedEvenWhenNullWithoutSendingOrConsumingProof() throws Exception {
		login(browser,"owner@example.test");var message=issue(browser);var original=metadata(browser);int sent=sender.messages.size();
		for(var value:Arrays.asList(identity,UUID.randomUUID(),null,"invalid",0)) {
			for(String operation:List.of("request","verify")) {
				var payload=new LinkedHashMap<String,Object>();
				if(operation.equals("verify"))payload.putAll(body(message.issuanceId(),message.code()));
				payload.put("identityId",value);
				var response=browser.post(reauth(operation),payload,browser.csrf());
				assertThat(response.statusCode()).isEqualTo(400);
				assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_REQUEST");
			}
		}
		assertThat(sender.messages.size()).isEqualTo(sent);assertThat(state(message)).isEqualTo("ACTIVE");
		assertThat(metadata(browser)).isEqualTo(original);
		assertThat(verify(browser,message.issuanceId(),message.code()).statusCode()).isEqualTo(204);
	}

	@Test void anotherUsersSessionCannotConsumeRepresentativeEmailProof() throws Exception {
		login(browser,"owner@example.test");var message=issue(browser);
		try(var other=new Browser()) {
			login(other,"other@example.test");
			assertThat(verify(other,message.issuanceId(),message.code()).statusCode()).isEqualTo(401);
		}
		assertThat(message.recipient()).isEqualTo("owner@example.test");assertThat(state(message)).isEqualTo("ACTIVE");
		assertThat(verify(browser,message.issuanceId(),message.code()).statusCode()).isEqualTo(204);
	}

	@Test void missingRepresentativeEmailRejectsIssuanceWithoutSending() throws Exception {
		login(browser,"owner@example.test");int sent=sender.messages.size();
		jdbc.update("DELETE FROM user_reauthentication_emails WHERE user_id=?",metadata(browser).userId());
		var response=request(browser);assertThat(response.statusCode()).isEqualTo(409);
		assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("REAUTH_EMAIL_REQUIRED");
		assertThat(sender.messages.size()).isEqualTo(sent);
	}

	@Test void representativeChangeDuringDeliveryCannotActivateProof() throws Exception {
		login(browser,"owner@example.test");var owner=metadata(browser).userId();var original=metadata(browser);
		sender.action=message->jdbc.update("UPDATE user_reauthentication_emails SET email='changed@example.test' WHERE user_id=?",owner);
		assertThat(request(browser).statusCode()).isEqualTo(401);sender.action=null;
		assertThat(count("SELECT count(*) FROM email_otp_challenges WHERE purpose='REAUTH' AND state='ACTIVE'")).isZero();
		assertThat(metadata(browser)).isEqualTo(original);
	}

	@Test void changedRepresentativeCannotConsumePreviouslyIssuedProof() throws Exception {
		login(browser,"owner@example.test");var message=issue(browser);var original=metadata(browser);
		jdbc.update("UPDATE user_reauthentication_emails SET email='changed@example.test' WHERE user_id=?",original.userId());
		assertThat(verify(browser,message.issuanceId(),message.code()).statusCode()).isEqualTo(401);
		assertThat(state(message)).isEqualTo("ACTIVE");assertThat(metadata(browser)).isEqualTo(original);
	}

	private static String reauth(String suffix) { return "/api/v1/auth/reauth/email/" + suffix; }
	private static String wrong(String code) { return (code.startsWith("0") ? "1" : "0") + code.substring(1); }
	private HttpResponse<String> request(Browser current) throws Exception { return current.post(reauth("request"), Map.of(), current.csrf()); }
	private OtpEmailSender.Message issue(Browser current) throws Exception {
		var response = request(current); assertThat(response.statusCode()).isEqualTo(202);
		return sender.messages.get(UUID.fromString(JSON.readTree(response.body()).get("challengeId").asString()));
	}
	private static Map<String, Object> body(UUID challenge, String code) { return Map.of("challengeId", challenge, "code", code); }
	private HttpResponse<String> verify(Browser current, UUID challenge, String code) throws Exception {
		return current.post(reauth("verify"), body(challenge, code), current.csrf());
	}
	private void login(Browser current, String email) throws Exception {
		var csrf = current.csrf();
		var request = current.post("/api/v1/auth/email-otp/request", Map.of("email", email), csrf);
		assertThat(request.statusCode()).isEqualTo(202);
		var id = UUID.fromString(JSON.readTree(request.body()).get("challengeId").asString()); var message = sender.messages.get(id);
		assertThat(current.post("/api/v1/auth/email-otp/verify", Map.of("email", email, "challengeId", id, "code", message.code()), csrf).statusCode()).isEqualTo(204);
		identity = jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=? AND provider='email'", UUID.class, metadata(current).userId());
	}
	private static SessionMetadata metadata(Browser current) {
		var session = (Session) app.getBean(SessionRepository.class).findById(current.cookie());
		assertThat(session != null).isTrue(); return session.getAttribute(SessionMetadata.ATTRIBUTE);
	}
	private static String state(OtpEmailSender.Message message) { return jdbc.queryForObject("SELECT state FROM email_otp_challenges WHERE id=?", String.class, message.issuanceId()); }
	private static int count(String sql) { return jdbc.queryForObject(sql, Integer.class); }
	private static Object snapshotData() {
		return List.of(jdbc.queryForList("SELECT * FROM users ORDER BY id"), jdbc.queryForList("SELECT * FROM auth_identities ORDER BY id"),
				jdbc.queryForList("SELECT * FROM exercise_definitions ORDER BY id"), jdbc.queryForList("SELECT * FROM user_workout_settings ORDER BY user_id"));
	}
	private static void writeAttribute(String session, String name, Object value) {
		writeAttribute(jdbc, session, name, value);
	}
	private static void writeAttribute(JdbcTemplate connection, String session, String name, Object value) {
		connection.update("UPDATE spring_session_attributes SET attribute_bytes=? WHERE attribute_name=? AND session_primary_id=(SELECT primary_id FROM spring_session WHERE session_id=?)",
				new org.springframework.core.serializer.support.SerializingConverter().convert(value), name, session);
	}
	static final class Browser implements AutoCloseable {
		final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
		Browser() { }
		Browser(String id) { var cookie = new HttpCookie("SESSION", id); cookie.setPath("/"); cookie.setVersion(0); cookies.getCookieStore().add(URI.create("http://127.0.0.1"), cookie); }
		String cookie() { return cookies.getCookieStore().getCookies().stream().filter(c -> c.getName().equals("SESSION")).findFirst().orElseThrow().getValue(); }
		String csrf() throws Exception { return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString(); }
		HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
		HttpResponse<String> post(String path, Object body, String csrf) throws Exception { return send("POST", path, body, csrf); }
		HttpResponse<String> send(String method, String path, Object body, String csrf) throws Exception {
			int port = ((WebServerApplicationContext) app).getWebServer().getPort();
			var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
			if (csrf != null) { request.header("X-CSRF-TOKEN", csrf); }
			// Every POST deliberately supplies untrusted forwarded input; limits must still use the socket peer.
			if (body != null) { request.header("Content-Type", "application/json").header("X-Forwarded-For", UUID.randomUUID().toString()); }
			return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
		}
		@Override public void close() { client.close(); }
	}
}
