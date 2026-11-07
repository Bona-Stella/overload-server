package com.overload.overload_server.auth.google;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.overload_server.auth.email.OtpEmailSender;
import com.overload.overload_server.security.SessionMetadata;
import com.overload.overload_server.security.SessionPrincipal;
import com.overload.testfixture.EmailOtpFixtures;
import com.overload.testfixture.GoogleOidcFixtures;
import com.overload.testfixture.GoogleReauthenticationFixtures;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.serializer.support.SerializingConverter;
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
class GoogleReauthenticationIntegrationTests {
	@Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_google_reauth_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString())
			.withLabel("overload.task", "007-1c-3b-2");
	static final JsonMapper JSON = JsonMapper.builder().build();
	static final String HMAC = randomKey();
	static ConfigurableApplicationContext app;
	static JdbcTemplate jdbc;
	static GoogleOidcFixtures.MockProvider provider;
	static String base;
	Browser browser;
	UUID identity;

	@BeforeAll static void start() throws Exception { provider = new GoogleOidcFixtures.MockProvider(); GoogleOidcFixtures.provider = provider; CLOCK.reset(); startApplication(0); }
	static void startApplication(int port) {
		app = new SpringApplicationBuilder(OverloadServerApplication.class, EmailOtpFixtures.class, GoogleOidcFixtures.class, GoogleReauthenticationFixtures.class)
				.web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
						"--spring.datasource.url=" + POSTGRES.getJdbcUrl(), "--spring.datasource.username=" + POSTGRES.getUsername(),
						"--spring.datasource.password=" + POSTGRES.getPassword(), "--GOOGLE_OIDC_ENABLED=true", "--AUTH_FRONTEND_RETURN_URI=", "--AUTH_CONTACT_FRONTEND_RETURN_URI=", "--GOOGLE_REAUTH_ENABLED=true", "--OTP_EMAIL_ENABLED=false",
						"--GOOGLE_CLIENT_ID=" + provider.clientId, "--GOOGLE_CLIENT_SECRET=" + provider.clientSecret, "--OTP_HMAC_KEY=" + HMAC,
						"--server.address=127.0.0.1", "--server.port=" + port, "--spring.session.jdbc.cleanup-cron=-",
						"--spring.datasource.hikari.maximum-pool-size=2", "--spring.datasource.hikari.minimum-idle=1",
						"--logging.level.root=WARN", "--spring.main.banner-mode=off");
		jdbc = app.getBean(JdbcTemplate.class); base = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
		com.overload.testfixture.MixedPersistenceAssertions.verify(app);
		// Flyway migration needs its own startup connection; all protocol/session tests then run with one connection.
		var pool=app.getBean(com.zaxxer.hikari.HikariDataSource.class); pool.setMaximumPoolSize(1); pool.getHikariPoolMXBean().softEvictConnections();
	}
	@BeforeEach void isolate() {
		GoogleReauthenticationFixtures.reset(); GoogleOidcFixtures.corruptMetadataRead = false; provider.reset(); CLOCK.reset();
		jdbc.execute("DROP TRIGGER IF EXISTS google_reauth_fail ON spring_session_attributes");
		jdbc.update("DELETE FROM spring_session"); jdbc.update("DELETE FROM email_otp_login_claims"); jdbc.update("UPDATE email_otp_limits SET active_challenge_id=NULL");
		// Exclusively this class's disposable Testcontainer. Never the developer Compose DB.
		for (var table : List.of("email_otp_challenges", "email_otp_limits", "email_otp_rate_limits", "reauth_email_candidates", "user_reauthentication_emails", "deload_weekly_assessments", "deload_analysis_versions", "exercise_definitions", "user_workout_settings", "auth_identities", "users")) { jdbc.update("DELETE FROM " + table); }
		app.getBean(EmailOtpFixtures.FakeSender.class).reset(); browser = new Browser();
	}
	@AfterEach void close() { GoogleReauthenticationFixtures.reset(); browser.close(); }
	@AfterAll static void stop() { if (app != null) { app.close(); } if (provider != null) { provider.close(); } }

	@Test void verifiedFreshAuthTimeRotatesOnlySessionAndPreservesAccountAndOriginalDeadline() throws Exception {
		login(browser); var before = metadata(browser); var data = data(); var old = browser.cookie();
		CLOCK.advance(Duration.ofMinutes(11)); var csrf = csrf(browser);
		assertThat(get(browser, "/__google_reauth_test__/recent").statusCode()).isEqualTo(403);
		var flow = begin(browser); var result = complete(browser, flow);
		assertSuccess(result); assertThat(browser.cookie().equals(old)).isFalse();
		var after = metadata(browser);
		assertThat(after.userId().equals(before.userId())).isTrue(); assertThat(after.loginStartedAt()).isEqualTo(before.loginStartedAt());
		assertThat(after.absoluteExpiresAt()).isEqualTo(before.absoluteExpiresAt()); assertThat(after.reauthenticatedAt()).isEqualTo(CLOCK.instant());
		assertThat(data()).isEqualTo(data); assertThat(get(browser, "/api/v1/auth/session").statusCode()).isEqualTo(200);
		assertThat(post(browser, "/__otp_test__/mutate", Map.of(), csrf).statusCode()).isEqualTo(403);
		assertThat(post(browser, "/__otp_test__/mutate", Map.of(), csrf(browser)).statusCode()).isEqualTo(204);
		try (var stale = new Browser(old)) { assertThat(get(stale, "/api/v1/auth/session").statusCode()).isEqualTo(401); }
		assertNoProviderStorage();
		CLOCK.advance(Duration.ofMinutes(10)); assertThat(get(browser, "/__google_reauth_test__/recent").statusCode()).isEqualTo(204);
		CLOCK.advance(Duration.ofNanos(1)); assertThat(get(browser, "/__google_reauth_test__/recent").statusCode()).isEqualTo(403);
	}

	@Test void startIsPostCsrfProtectedAndAnonymousOrEmailOnlyOrForeignIdentityCannotStart() throws Exception {
		assertThat(start(browser, UUID.randomUUID(), null).statusCode()).isEqualTo(401);
		assertThat(start(browser, UUID.randomUUID(), csrf(browser)).statusCode()).isEqualTo(401);
		otpLogin(browser, "email-only@example.test");
		var other = app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.google(VerifiedIdentity.GOOGLE_ISSUER, "other-sub"), null);
		var foreign = jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=?", UUID.class, other.id());
		assertThat(start(browser, foreign, null).statusCode()).isEqualTo(403);
		assertThat(start(browser, foreign, csrf(browser)).statusCode()).isEqualTo(404);
		assertThat(start(browser, identity, csrf(browser)).statusCode()).isEqualTo(404);
		assertThat(get(browser, GoogleReauthenticationSecurity.START).statusCode()).isEqualTo(405);
		assertThat(provider.authorizations.get()).isZero(); assertThat(get(browser, "/api/v1/auth/session").statusCode()).isEqualTo(200);
	}

	@ParameterizedTest @ValueSource(strings={"missing","old","string","fractional","negative","future","previous-second"})
	void absentStaleOrInvalidAuthTimeCannotBeReplacedByFreshIat(String fault) throws Exception {
		login(browser); CLOCK.advance(Duration.ofMinutes(11)); var before = metadata(browser); var cookie = browser.cookie();
		long now = CLOCK.instant().getEpochSecond();
		provider.authenticationTime = switch(fault) {
			case "missing" -> null; case "old" -> now - 3600; case "previous-second" -> now - 1;
			case "string" -> Long.toString(now); case "fractional" -> now + 0.5; case "negative" -> -1L; default -> now + 61;
		};
		var flow = beginConfigured(browser); assertFailure(complete(browser, flow));
		assertThat(browser.cookie().equals(cookie)).isTrue(); assertThat(metadata(browser)).isEqualTo(before);
		assertThat(get(browser, "/__google_reauth_test__/recent").statusCode()).isEqualTo(403);
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(1); assertNoProviderStorage();
	}

	@ParameterizedTest @ValueSource(longs={0,60})
	void futureClockSkewAtMostSixtySecondsIsBoundedByServerNow(long skew) throws Exception {
		login(browser); CLOCK.advance(Duration.ofMinutes(11)); provider.authenticationTime=CLOCK.instant().getEpochSecond()+skew;
		assertSuccess(complete(browser, beginConfigured(browser)));
		assertThat(metadata(browser).reauthenticatedAt()).isEqualTo(CLOCK.instant());
	}

	@Test void delayedCallbackUsesActualAuthTimeInsteadOfMintingANewRecentWindow() throws Exception {
		login(browser); CLOCK.advance(Duration.ofMinutes(11)); var evidence = CLOCK.instant(); var flow=begin(browser);
		CLOCK.advance(Duration.ofMinutes(4)); assertSuccess(complete(browser, flow));
		assertThat(metadata(browser).reauthenticatedAt()).isEqualTo(evidence);
		CLOCK.advance(Duration.ofMinutes(6)); assertThat(get(browser,"/__google_reauth_test__/recent").statusCode()).isEqualTo(204);
		CLOCK.advance(Duration.ofNanos(1)); assertThat(get(browser,"/__google_reauth_test__/recent").statusCode()).isEqualTo(403);
	}

	@ParameterizedTest @EnumSource(value=GoogleOidcFixtures.Fault.class,names="NONE",mode=EnumSource.Mode.EXCLUDE)
	void standardOidcFailuresPreserveTheOriginalAccountAndSession(GoogleOidcFixtures.Fault fault) throws Exception {
		login(browser); csrf(browser); var bytes=attributes(browser); var before=metadata(browser); var data=data();
		provider.fault=fault; assertFailure(complete(browser,begin(browser)));
		assertThat(metadata(browser)).isEqualTo(before); assertThat(attributes(browser)).isEqualTo(bytes); assertThat(data()).isEqualTo(data);
		assertNoProviderStorage();
	}

	@Test void anotherGoogleSubjectWithSameEmailNeverCreatesOrSwitchesAnAccount() throws Exception {
		login(browser); var before=metadata(browser); var data=data(); provider.subject="unlinked-sub-same-email";
		assertFailure(complete(browser,begin(browser))); assertThat(metadata(browser)).isEqualTo(before); assertThat(data()).isEqualTo(data);
	}

	@Test void cancellationConsumesOnlyThePendingStateAndKeepsOriginalCsrfAndAuthentication() throws Exception {
		login(browser); var csrf=csrf(browser); var before=attributes(browser); var flow=begin(browser);
		var state=GoogleOidcFixtures.MockProvider.parameters(flow.callback().getRawQuery()).get("state");
		assertFailure(get(browser,GoogleReauthenticationSecurity.CALLBACK+"?error=access_denied&state="+GoogleOidcFixtures.MockProvider.encode(state)));
		assertThat(attributes(browser)).isEqualTo(before); assertThat(post(browser,"/__otp_test__/mutate",Map.of(),csrf).statusCode()).isEqualTo(204);
		int exchanged=provider.exchanges.get(); assertFailure(complete(browser,flow)); assertThat(provider.exchanges.get()).isEqualTo(exchanged);
	}

	@Test void invalidStateOtherBrowserAndPurposeCrossingCannotConsumeLegitimatePendingState() throws Exception {
		login(browser); var flow=begin(browser); int before=provider.exchanges.get();
		assertFailure(get(browser,GoogleReauthenticationSecurity.CALLBACK+"?code=invalid&state=invalid"));
		try(var stranger=new Browser()) { assertThat(complete(stranger,flow).statusCode()).isEqualTo(401); }
		assertThat(get(browser,"/login/oauth2/code/google?"+flow.callback().getRawQuery()).statusCode()).isEqualTo(409);
		assertThat(provider.exchanges.get()).isEqualTo(before); assertSuccess(complete(browser,flow));
	}

	@Test void concurrentCallbackConsumesStateExactlyOnceAndNeverDeletesSourceOnLoser() throws Exception {
		login(browser); var flow=begin(browser); int before=provider.exchanges.get(); var cookie=browser.cookie();
		try(var one=new Browser(cookie);var two=new Browser(cookie);var executor=Executors.newFixedThreadPool(2)) {
			var gate=new CountDownLatch(1); var futures=new ArrayList<Future<HttpResponse<String>>>();
			for(var current:List.of(one,two)) { futures.add(executor.submit(()->{gate.await(10,TimeUnit.SECONDS);return complete(current,flow);})); }
			gate.countDown(); var results=new ArrayList<HttpResponse<String>>(); for(var future:futures) {results.add(future.get(20,TimeUnit.SECONDS));}
			assertThat(results.stream().filter(this::success).count()).isEqualTo(1);
			assertThat(provider.exchanges.get()-before).isEqualTo(1);
			assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL")).isEqualTo(1);
		}
	}

	@Test void anotherAuthenticatedDeviceForSameUserCannotClaimThisBrowsersState() throws Exception {
		login(browser); var flow=begin(browser); var first=metadata(browser);
		try(var other=new Browser()) {
			login(other); assertThat(metadata(other).userId().equals(first.userId())).isTrue();
			assertFailure(complete(other,flow)); assertThat(get(other,"/api/v1/auth/session").statusCode()).isEqualTo(200);
			assertSuccess(complete(browser,flow));
		}
	}

	@Test void loginStateCannotBeUsedInReauthenticationCallbackAndOriginalLoginStillWorks() throws Exception {
		var loginFlow=providerFlow(get(browser,"/oauth2/authorization/google"));
		assertThat(get(browser,GoogleReauthenticationSecurity.CALLBACK+"?"+loginFlow.callback().getRawQuery()).statusCode()).isEqualTo(401);
		assertThat(provider.exchanges.get()).isZero(); assertSuccess(complete(browser,loginFlow));
	}

	@Test void startStorageFailureCannotRedirectToGoogleOrDamageOriginalAttributes() throws Exception {
		login(browser); var csrf=csrf(browser); var before=attributes(browser);
		jdbc.execute("CREATE OR REPLACE FUNCTION google_reauth_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated state fault' USING ERRCODE='23514'; END $$");
		jdbc.execute("CREATE TRIGGER google_reauth_fail BEFORE INSERT ON spring_session_attributes FOR EACH ROW EXECUTE FUNCTION google_reauth_fail()");
		assertFailure(start(browser,identity,csrf)); jdbc.execute("DROP TRIGGER google_reauth_fail ON spring_session_attributes");
		assertThat(attributes(browser)).isEqualTo(before); assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(200);
	}

	@Test void silentlySuppressedStateDeletionCannotAcknowledgeConsumptionOrExchangeACode() throws Exception {
		login(browser); var flow=begin(browser); var before=metadata(browser); int exchanges=provider.exchanges.get();
		jdbc.execute("CREATE OR REPLACE FUNCTION google_reauth_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$");
		jdbc.execute("CREATE TRIGGER google_reauth_fail BEFORE DELETE ON spring_session_attributes FOR EACH ROW EXECUTE FUNCTION google_reauth_fail()");
		assertFailure(complete(browser,flow)); assertThat(provider.exchanges.get()).isEqualTo(exchanges);
		assertThat(metadata(browser)).isEqualTo(before); jdbc.execute("DROP TRIGGER google_reauth_fail ON spring_session_attributes");
		// No deletion committed and no code was exchanged; recovery may finish this still-pending flow exactly once.
		assertSuccess(complete(browser,flow)); assertFailure(complete(browser,flow));
	}

	@Test void secondTabSupersedesOnlyOldPendingAndConsumedStateCannotReplay() throws Exception {
		login(browser); var first=begin(browser); var second=begin(browser);
		assertFailure(complete(browser,first)); assertSuccess(complete(browser,second)); int exchanges=provider.exchanges.get();
		assertFailure(complete(browser,second)); assertThat(provider.exchanges.get()).isEqualTo(exchanges);
	}

	@Test void pendingStateAndAuthenticationSurviveServerRestartWithPoolOfOne() throws Exception {
		login(browser); var before=metadata(browser); CLOCK.advance(Duration.ofMinutes(11)); var flow=begin(browser);
		int port=URI.create(base).getPort(); app.close(); startApplication(port); assertSuccess(complete(browser,flow));
		assertThat(metadata(browser).absoluteExpiresAt()).isEqualTo(before.absoluteExpiresAt());
		assertThat(app.getBean(com.zaxxer.hikari.HikariDataSource.class).getMaximumPoolSize()).isEqualTo(1);
	}

	@ParameterizedTest @ValueSource(longs={299,300})
	void pendingRequestTtlHasAnExactFiveMinuteBoundary(long seconds) throws Exception {
		login(browser); var flow=begin(browser); CLOCK.advance(Duration.ofSeconds(seconds)); var result=complete(browser,flow);
		if(seconds<300) {assertSuccess(result);} else {assertFailure(result); assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(200);}
	}

	@ParameterizedTest @ValueSource(strings={"logout","rotation","user","metadata","expiry"})
	void sourceSessionChangesDuringTokenExchangeAreNeverAcknowledged(String change) throws Exception {
		login(browser); var flow=begin(browser); var old=browser.cookie(); var m=metadata(browser);
		provider.onExchange=()->{
			if(change.equals("logout")){jdbc.update("DELETE FROM spring_session WHERE session_id=?",old);}
			if(change.equals("rotation")){jdbc.update("UPDATE spring_session SET session_id=? WHERE session_id=?",UUID.randomUUID().toString(),old);}
			if(change.equals("user")){attribute(old,"SPRING_SECURITY_CONTEXT",new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(
					new SessionPrincipal(UUID.randomUUID()),null,AuthorityUtils.createAuthorityList("ROLE_USER"))));}
			if(change.equals("metadata")){attribute(old,SessionMetadata.ATTRIBUTE,new SessionMetadata(m.userId(),m.loginStartedAt(),m.absoluteExpiresAt().plusSeconds(1),m.reauthenticatedAt()));}
			if(change.equals("expiry")){CLOCK.advance(Duration.ofDays(90));}
		};
		assertFailure(complete(browser,flow)); assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
	}

	@Test void sessionWriteFailurePreservesOriginalButNeverRestoresConsumedState() throws Exception {
		login(browser); var before=metadata(browser); var flow=begin(browser);
		jdbc.execute("CREATE OR REPLACE FUNCTION google_reauth_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated reauth write fault' USING ERRCODE='23514'; END $$");
		jdbc.execute("CREATE TRIGGER google_reauth_fail BEFORE INSERT ON spring_session_attributes FOR EACH ROW EXECUTE FUNCTION google_reauth_fail()");
		assertFailure(complete(browser,flow)); jdbc.execute("DROP TRIGGER google_reauth_fail ON spring_session_attributes");
		assertThat(metadata(browser)).isEqualTo(before); assertFailure(complete(browser,flow)); assertSuccess(complete(browser,begin(browser)));
	}

	@ParameterizedTest @ValueSource(strings={"before","after","readback","cookie","response"})
	void expiryAtEveryPersistenceBoundaryCannotSendSuccess(String point) throws Exception {
		login(browser); var flow=begin(browser); var old=browser.cookie(); var reads=new AtomicInteger();
		if(point.equals("before")){GoogleReauthenticationFixtures.beforeRotate=ignored->CLOCK.advance(Duration.ofDays(90));}
		if(point.equals("after")){GoogleReauthenticationFixtures.afterRotate=ignored->CLOCK.advance(Duration.ofDays(90));}
		int target=point.equals("readback")?1:point.equals("cookie")?2:3;
		if(!point.equals("before")&&!point.equals("after")){GoogleOidcFixtures.onSessionRead=session->{if(!session.getId().equals(old)&&reads.incrementAndGet()==target){CLOCK.advance(Duration.ofDays(90));}};}
		assertThat(success(complete(browser,flow))).isFalse(); GoogleReauthenticationFixtures.reset();
		assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(401);
	}

	@Test void committedStoreReadFailureDiscardsNewSessionInsteadOfReturningSuccess() throws Exception {
		login(browser); var flow=begin(browser); var old=browser.cookie();
		GoogleOidcFixtures.onSessionRead=session->{if(!session.getId().equals(old)){throw new DataAccessResourceFailureException("isolated read fault");}};
		assertFailure(complete(browser,flow)); GoogleReauthenticationFixtures.reset();
		assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(401);
		assertThat(count("SELECT count(*) FROM spring_session WHERE principal_name IS NOT NULL")).isZero();
	}

	@ParameterizedTest @ValueSource(strings={"before","after","readback","cookie","response"})
	void authenticationEvidenceExpiryCannotHideBehindAStillActiveNinetyDaySession(String point) throws Exception {
		login(browser); var before=metadata(browser); var flow=begin(browser); var old=browser.cookie(); var reads=new AtomicInteger();
		if(point.equals("before")){GoogleReauthenticationFixtures.beforeRotate=ignored->CLOCK.advance(Duration.ofMinutes(6));}
		if(point.equals("after")){GoogleReauthenticationFixtures.afterRotate=ignored->CLOCK.advance(Duration.ofMinutes(6));}
		int target=point.equals("readback")?1:point.equals("cookie")?2:3;
		if(!point.equals("before")&&!point.equals("after")){GoogleOidcFixtures.onSessionRead=session->{if(!session.getId().equals(old)&&reads.incrementAndGet()==target){CLOCK.advance(Duration.ofMinutes(6));}};}
		assertThat(success(complete(browser,flow))).isFalse(); GoogleReauthenticationFixtures.reset();
		if(point.equals("before")||point.equals("after")){assertThat(metadata(browser)).isEqualTo(before);}
		else{assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(401);}
	}

	@Test void emailAndGoogleReauthenticationShareRotationAndInvalidateEachOthersPendingProofs() throws Exception {
		login(browser); var user=metadata(browser).userId();
		// An already-linked identity is fixture data only. No production linking endpoint or automatic merge.
		var emailIdentity=UUID.randomUUID(); String email="linked-fixture@example.test";
		jdbc.update("INSERT INTO auth_identities(id,user_id,provider,issuer,subject) VALUES (?,?,'email','overload:email',?)",emailIdentity,user,email);
		var googleIdentity=identity;
		try(var emailProof=new Browser()){otpLogin(emailProof,email);}
		identity=googleIdentity;
		var flow=begin(browser); var csrf=csrf(browser);
		var issued=post(browser,"/api/v1/auth/reauth/email/request",Map.of(),csrf);
		assertThat(issued.statusCode()).isEqualTo(202); var challenge=UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString());
		var message=app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(challenge);
		assertThat(post(browser,"/api/v1/auth/reauth/email/verify",Map.of("challengeId",challenge,"code",message.code()),csrf).statusCode()).isEqualTo(204);
		assertFailure(complete(browser,flow)); assertSuccess(complete(browser,begin(browser)));
		assertThat(metadata(browser).userId().equals(user)).isTrue(); assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
	}

	@Test void originalNinetyDayDeadlineStillExpiresAfterGoogleReauthentication() throws Exception {
		login(browser); CLOCK.advance(Duration.ofDays(89)); assertSuccess(complete(browser,begin(browser)));
		CLOCK.advance(Duration.ofDays(1).minusNanos(1)); assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(200);
		CLOCK.advance(Duration.ofNanos(1)); assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(401);
	}

	@Test void noTemporaryGooglePrincipalIsEverWrittenAndFailuresUseOnlyTheFixedLocalContract() throws Exception {
		login(browser);
		jdbc.execute("CREATE TABLE google_reauth_audit (provider_context BOOLEAN NOT NULL)");
		jdbc.execute("""
				CREATE FUNCTION google_reauth_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
				IF NEW.attribute_name='SPRING_SECURITY_CONTEXT' THEN
				INSERT INTO google_reauth_audit VALUES (
				position(convert_to('OAuth2AuthenticationToken','UTF8') IN NEW.attribute_bytes)>0 OR
				position(convert_to('OidcIdToken','UTF8') IN NEW.attribute_bytes)>0 OR
				position(convert_to('DefaultOidcUser','UTF8') IN NEW.attribute_bytes)>0);
				END IF; RETURN NEW; END $$
				""");
		jdbc.execute("CREATE TRIGGER google_reauth_audit BEFORE INSERT OR UPDATE ON spring_session_attributes FOR EACH ROW EXECUTE FUNCTION google_reauth_audit()");
		try {
			assertSuccess(complete(browser,begin(browser)));
			assertThat(count("SELECT count(*) FROM google_reauth_audit WHERE provider_context")).isZero();
			assertThat(count("SELECT count(*) FROM google_reauth_audit WHERE NOT provider_context")).isPositive();
			var failure=get(browser,GoogleReauthenticationSecurity.FAILURE+"?next=https://untrusted.example.test&error_description=untrusted");
			assertThat(failure.statusCode()).isEqualTo(401); assertThat(failure.body()).isEqualTo("{\"code\":\"GOOGLE_REAUTH_FAILED\"}");
			assertThat(failure.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
			assertThat(failure.headers().firstValue("Referrer-Policy").orElse("")).isEqualTo("no-referrer");
			assertNoProviderStorage();
		} finally {
			jdbc.execute("DROP TRIGGER google_reauth_audit ON spring_session_attributes"); jdbc.execute("DROP FUNCTION google_reauth_audit()"); jdbc.execute("DROP TABLE google_reauth_audit");
		}
	}

	@Test void contactConfirmationAndStrictReauthenticationKeepSeparateFiltersAndOneTimeStatesWhenBothEnabled() throws Exception {
		login(browser);var flow=begin(browser);var identityId=identity;var before=metadata(browser);
		var started=postFormContact(browser,identityId,csrf(browser));
		assertThat(started.statusCode()).isEqualTo(302);
		URI callback;
		try(var providerBrowser=HttpClient.newHttpClient()){
			var authorization=URI.create(started.headers().firstValue("Location").orElseThrow());
			assertThat(authorization.getHost()).isEqualTo("127.0.0.1");
			var response=providerBrowser.send(HttpRequest.newBuilder(authorization).GET().build(),HttpResponse.BodyHandlers.ofString());
			callback=URI.create(response.headers().firstValue("Location").orElseThrow());
		}
		assertFailure(get(browser,GoogleReauthenticationSecurity.CALLBACK+"?"+callback.getRawQuery()));
		var confirmed=browser.client.send(HttpRequest.newBuilder(callback).GET().build(),HttpResponse.BodyHandlers.ofString());
		assertThat(confirmed.statusCode()).isEqualTo(302);assertThat(confirmed.headers().firstValue("Location").orElse("")).isEqualTo("/api/v1/auth/reauth/email/contact");
		assertThat(metadata(browser)).isEqualTo(before);assertSuccess(complete(browser,flow));
		assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isZero();
	}
	private HttpResponse<String> postFormContact(Browser current,UUID identity,String csrf)throws Exception{
		return current.client.send(HttpRequest.newBuilder(URI.create(base+GoogleContactSecurity.START)).header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf)
				.POST(HttpRequest.BodyPublishers.ofString("identityId="+identity)).build(),HttpResponse.BodyHandlers.ofString());
	}

	private void login(Browser current) throws Exception {
		var start=get(current,"/oauth2/authorization/google"); assertSuccess(complete(current,providerFlow(start)));
		identity=jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=? AND provider='google'",UUID.class,metadata(current).userId());
	}
	private Flow begin(Browser current) throws Exception { provider.authenticationTime=CLOCK.instant().getEpochSecond(); return beginConfigured(current); }
	private Flow beginConfigured(Browser current) throws Exception {
		var response=start(current,identity,csrf(current)); assertThat(response.statusCode()).isEqualTo(302);
		var parameters=GoogleOidcFixtures.MockProvider.parameters(URI.create(response.headers().firstValue("Location").orElseThrow()).getRawQuery());
		assertThat(parameters.get("claims")).isEqualTo("{\"id_token\":{\"auth_time\":{\"essential\":true}}}");
		assertThat(parameters.get("max_age")).isEqualTo("0"); assertThat(parameters.get("code_challenge_method")).isEqualTo("S256");
		assertThat(parameters.keySet()).doesNotContain("identityId","userId","sub","issuer");
		assertThat(parameters.get("redirect_uri").endsWith(GoogleReauthenticationSecurity.CALLBACK)).isTrue();
		return providerFlow(response);
	}
	private HttpResponse<String> start(Browser current,UUID identity,String csrf) throws Exception {
		var builder=HttpRequest.newBuilder(URI.create(base+GoogleReauthenticationSecurity.START)).header("Content-Type","application/x-www-form-urlencoded");
		if(csrf!=null){builder.header("X-CSRF-TOKEN",csrf);}
		return current.client.send(builder.POST(HttpRequest.BodyPublishers.ofString("identityId="+identity)).build(),HttpResponse.BodyHandlers.ofString());
	}
	private Flow providerFlow(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).isEqualTo(302);
		try(var client=HttpClient.newHttpClient()){
			var uri=URI.create(response.headers().firstValue("Location").orElseThrow()); assertThat(uri.getHost()).isEqualTo("127.0.0.1");
			var providerResponse=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());
			assertThat(providerResponse.statusCode()).isEqualTo(302); return new Flow(URI.create(providerResponse.headers().firstValue("Location").orElseThrow()));
		}
	}
	private HttpResponse<String> complete(Browser current,Flow flow) throws Exception {return current.client.send(HttpRequest.newBuilder(flow.callback()).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());}
	private HttpResponse<String> get(Browser current,String path) throws Exception {return current.client.send(HttpRequest.newBuilder(URI.create(base+path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
	private HttpResponse<String> post(Browser current,String path,Object body,String csrf) throws Exception {
		var request=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json"); if(csrf!=null){request.header("X-CSRF-TOKEN",csrf);}
		return current.client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
	}
	private String csrf(Browser current) throws Exception {return JSON.readTree(get(current,"/api/v1/auth/csrf").body()).get("token").asString();}
	private static Session stored(Browser current){return app.getBean(SessionRepository.class).findById(current.cookie());}
	private static SessionMetadata metadata(Browser current){var session=stored(current); assertThat(session!=null).isTrue();return session.getAttribute(SessionMetadata.ATTRIBUTE);}
	private long count(String sql){return jdbc.queryForObject(sql,Long.class);}
	private Object data(){return List.of(jdbc.queryForList("SELECT * FROM users ORDER BY id"),jdbc.queryForList("SELECT * FROM auth_identities ORDER BY id"),jdbc.queryForList("SELECT * FROM exercise_definitions ORDER BY id"),jdbc.queryForList("SELECT * FROM user_workout_settings ORDER BY user_id"));}
	private Map<String,String> attributes(Browser current){return jdbc.query("SELECT attribute_name,encode(attribute_bytes,'hex') FROM spring_session_attributes WHERE session_primary_id=(SELECT primary_id FROM spring_session WHERE session_id=?) AND attribute_name NOT LIKE '%GoogleReauthenticationRequests%'",rs->{var result=new HashMap<String,String>();while(rs.next()){result.put(rs.getString(1),rs.getString(2));}return result;},current.cookie());}
	private void attribute(String id,String name,Object value){jdbc.update("UPDATE spring_session_attributes SET attribute_bytes=? WHERE attribute_name=? AND session_primary_id=(SELECT primary_id FROM spring_session WHERE session_id=?)",new SerializingConverter().convert(value),name,id);}
	private boolean success(HttpResponse<String> response){return response.statusCode()==302&&response.headers().firstValue("Location").orElse("").equals("/api/v1/auth/session");}
	private void assertSuccess(HttpResponse<String> response){assertThat(success(response)).as("fixed success redirect only").isTrue();}
	private void assertFailure(HttpResponse<String> response){assertThat(response.statusCode()).isEqualTo(302);assertThat(response.headers().firstValue("Location").orElse("").equals(GoogleReauthenticationSecurity.FAILURE)).isTrue();}
	private void assertNoProviderStorage(){assertThat(count("SELECT count(*) FROM spring_session_attributes WHERE attribute_name LIKE '%GoogleReauthenticationRequests%' OR attribute_name LIKE '%JdbcAuthorizationRequestRepository%'")).isZero();for(var bytes:jdbc.queryForList("SELECT attribute_bytes FROM spring_session_attributes",byte[].class)){assertThat(provider.containsProviderToken(bytes)).isFalse();}}
	private void otpLogin(Browser current,String email) throws Exception {
		var csrf=csrf(current);var issued=post(current,"/api/v1/auth/email-otp/request",Map.of("email",email),csrf);assertThat(issued.statusCode()).isEqualTo(202);
		var id=UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString());OtpEmailSender.Message message=app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(id);
		assertThat(post(current,"/api/v1/auth/email-otp/verify",Map.of("email",email,"challengeId",id,"code",message.code()),csrf).statusCode()).isEqualTo(204);
		identity=jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=? AND provider='email'",UUID.class,metadata(current).userId());
	}
	private static String randomKey(){var bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);return Base64.getEncoder().encodeToString(bytes);}
	private record Flow(URI callback){@Override public String toString(){return "GoogleReauthenticationFlow[redacted]";}}
	private static final class Browser implements AutoCloseable {
		final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
		final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
		Browser(){} Browser(String id){var cookie=new HttpCookie("SESSION",id);cookie.setPath("/");cookie.setVersion(0);cookies.getCookieStore().add(URI.create(base),cookie);}
		String cookie(){return cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("SESSION")).findFirst().orElseThrow().getValue();}
		@Override public void close(){client.close();}
	}
}
