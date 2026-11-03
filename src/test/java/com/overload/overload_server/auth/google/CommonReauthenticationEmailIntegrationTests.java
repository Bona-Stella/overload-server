package com.overload.overload_server.auth.google;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.auth.email.OtpEmailSender;
import com.overload.overload_server.auth.email.OtpPurpose;
import com.overload.overload_server.security.SessionMetadata;
import com.overload.testfixture.EmailOtpFixtures;
import com.overload.testfixture.GoogleOidcFixtures;
import com.overload.testfixture.GoogleReauthenticationFixtures;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
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

import static com.overload.testfixture.EmailOtpFixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("postgres") @Testcontainers
class CommonReauthenticationEmailIntegrationTests {
	@Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine")
			.withDatabaseName("overload_common_reauth_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString()).withLabel("overload.task","common-reauth-email");
	static final JsonMapper JSON=JsonMapper.builder().build();
	static ConfigurableApplicationContext app;
	static JdbcTemplate jdbc;
	static GoogleOidcFixtures.MockProvider provider;
	static EmailOtpFixtures.FakeSender sender;
	static String base;
	Browser browser;
	static final String CONTACT="/api/v1/auth/reauth/email/contact";
	static final String REAUTH="/api/v1/auth/reauth/email/";
	static final String ENROLL=REAUTH+"registration/";

	@BeforeAll static void start() throws Exception {
		provider=new GoogleOidcFixtures.MockProvider();GoogleOidcFixtures.provider=provider;CLOCK.reset();
		byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);
		app=new SpringApplicationBuilder(OverloadServerApplication.class,EmailOtpFixtures.class,GoogleOidcFixtures.class,GoogleReauthenticationFixtures.class)
				.web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration","--spring.datasource.url="+POSTGRES.getJdbcUrl(),
						"--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
						"--GOOGLE_OIDC_ENABLED=true","--AUTH_FRONTEND_RETURN_URI=","--AUTH_LINK_FRONTEND_RETURN_URI=", "--AUTH_CONTACT_FRONTEND_RETURN_URI=","--GOOGLE_REAUTH_ENABLED=false","--OTP_EMAIL_ENABLED=false","--OTP_HMAC_KEY="+Base64.getEncoder().encodeToString(bytes),
						"--GOOGLE_CLIENT_ID="+provider.clientId,"--GOOGLE_CLIENT_SECRET="+provider.clientSecret,"--server.address=127.0.0.1","--server.port=0",
						"--spring.session.jdbc.cleanup-cron=-","--logging.level.root=WARN","--spring.main.banner-mode=off");
		jdbc=app.getBean(JdbcTemplate.class);sender=app.getBean(EmailOtpFixtures.FakeSender.class);
		com.overload.testfixture.MixedPersistenceAssertions.verify(app);
		base="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
	}
	@BeforeEach void isolate(){
		CLOCK.reset();provider.reset();sender.reset();GoogleReauthenticationFixtures.reset();GoogleOidcFixtures.corruptMetadataRead=false;
		jdbc.execute("DROP TRIGGER IF EXISTS contact_fail ON user_reauthentication_emails");
		jdbc.execute("DROP TRIGGER IF EXISTS contact_fail ON spring_session_attributes");
		jdbc.update("DELETE FROM spring_session");jdbc.update("DELETE FROM email_otp_login_claims");jdbc.update("UPDATE email_otp_limits SET active_challenge_id=NULL");
		for(var table:List.of("email_otp_challenges","email_otp_limits","email_otp_rate_limits","reauth_email_candidates","user_reauthentication_emails","exercise_definitions","user_workout_settings","auth_identities","users")){jdbc.update("DELETE FROM "+table);}
		browser=new Browser();
	}
	@AfterEach void close(){GoogleReauthenticationFixtures.reset();browser.close();}
	@AfterAll static void stop(){if(app!=null)app.close();if(provider!=null)provider.close();}

	@Test void emailLoginOwnershipProofRegistersContactWithoutDuplicateSendingAndCommonReauthPreservesDeadline() throws Exception {
		String email="email-owner@example.test";emailLogin(browser,email);
		assertThat(sender.messages).hasSize(1);assertThat(status(browser).get("status").asString()).isEqualTo("VERIFIED");
		assertThat(get(browser,CONTACT).body()).doesNotContain(email);
		var before=metadata(browser);var old=browser.cookie();CLOCK.advance(Duration.ofMinutes(11));var csrf=csrf(browser);
		var message=issue(browser,REAUTH+"request",Map.of());assertThat(message.recipient().equals(email)).isTrue();
		assertThat(verify(browser,REAUTH+"verify",Map.of("challengeId",message.issuanceId(),"code",message.code())).statusCode()).isEqualTo(204);
		var after=metadata(browser);assertThat(after.userId().equals(before.userId())).isTrue();assertThat(after.loginStartedAt()).isEqualTo(before.loginStartedAt());
		assertThat(after.absoluteExpiresAt()).isEqualTo(before.absoluteExpiresAt());assertThat(after.reauthenticatedAt()).isEqualTo(CLOCK.instant());assertThat(browser.cookie().equals(old)).isFalse();
		assertThat(post(browser,"/__otp_test__/mutate",Map.of(),csrf).statusCode()).isEqualTo(403);
		assertThat(post(browser,"/__otp_test__/mutate",Map.of(),csrf(browser)).statusCode()).isEqualTo(204);
		CLOCK.advance(Duration.ofMinutes(10));assertThat(get(browser,"/__google_reauth_test__/recent").statusCode()).isEqualTo(204);
		CLOCK.advance(Duration.ofNanos(1));assertThat(get(browser,"/__google_reauth_test__/recent").statusCode()).isEqualTo(403);
	}

	@Test void googleOnlyRequiresBoundOidcAndActualEmailOtpButNeverCreatesAnEmailLoginIdentity() throws Exception {
		googleLogin(browser);var before=metadata(browser);assertThat(status(browser).get("status").asString()).isEqualTo("REQUIRED");
		assertThat(post(browser,REAUTH+"request",Map.of(),csrf(browser)).statusCode()).isEqualTo(409);assertThat(sender.messages).isEmpty();
		CLOCK.advance(Duration.ofMinutes(11));var candidate=candidate(browser);
		assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isZero();
		var message=issue(browser,ENROLL+"request",Map.of("candidateId",candidate));assertThat(message.purpose()).isEqualTo(OtpPurpose.CONTACT_ENROLL);
		assertThat(message.recipient().equals(provider.email)).isTrue();assertThat(status(browser).get("status").asString()).isEqualTo("REQUIRED");
		var result=verify(browser,ENROLL+"verify",enrollment(candidate,message));assertThat(result.statusCode()).isEqualTo(204);
		assertThat(status(browser).get("status").asString()).isEqualTo("VERIFIED");assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='email'")).isZero();
		assertThat(metadata(browser).userId().equals(before.userId())).isTrue();assertThat(metadata(browser).absoluteExpiresAt()).isEqualTo(before.absoluteExpiresAt());
		CLOCK.advance(Duration.ofMinutes(11));var reauth=issue(browser,REAUTH+"request",Map.of());
		assertThat(verify(browser,REAUTH+"verify",Map.of("challengeId",reauth.issuanceId(),"code",reauth.code())).statusCode()).isEqualTo(204);
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isEqualTo(1);
	}

	@Test void oldGoogleAccountWithNoStoredClaimsObtainsFreshSignedCandidateInsteadOfTrustingAnInputAddress() throws Exception {
		provider.email=null;provider.emailVerified=null;googleLogin(browser);assertThat(status(browser).get("status").asString()).isEqualTo("REQUIRED");
		provider.email="newly-confirmed@example.test";provider.emailVerified=true;var candidate=candidate(browser);
		var message=issue(browser,ENROLL+"request",Map.of("candidateId",candidate));assertThat(message.recipient().equals(provider.email)).isTrue();
		assertThat(verify(browser,ENROLL+"verify",enrollment(candidate,message)).statusCode()).isEqualTo(204);
	}

	@Test void loginEnabledReauthDisabledStillAllowsContactConfirmationWithoutAuthTimeOrMaxAge() throws Exception {
		assertThat(app.getBeansOfType(GoogleReauthenticationSecurity.class)).isEmpty();googleLogin(browser);
		var csrf=csrf(browser);var id=googleIdentity(browser);
		assertThat(start(browser,GoogleReauthenticationSecurity.START,id,csrf,null).statusCode()).isEqualTo(503);
		assertThat(get(browser,GoogleReauthenticationSecurity.CALLBACK+"?code=invalid&state=invalid").statusCode()).isEqualTo(503);
		assertThat(candidate(browser)).isNotNull();assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(200);
	}

	@ParameterizedTest @ValueSource(strings={"missing","false","string","missing-email","wrong-sub","wrong-issuer","bad-signature","wrong-nonce"})
	void unverifiedEmailOrWrongGoogleProofNeverCreatesCandidateOrContact(String fault) throws Exception {
		googleLogin(browser);var before=metadata(browser);
		switch(fault){case "missing"->provider.emailVerified=null;case "false"->provider.emailVerified=false;case "string"->provider.emailVerified="true";
			case "missing-email"->provider.email=null;case "wrong-sub"->provider.subject="another-google-sub";
			case "wrong-issuer"->provider.fault=GoogleOidcFixtures.Fault.WRONG_ISSUER;
			case "bad-signature"->provider.fault=GoogleOidcFixtures.Fault.BAD_SIGNATURE;default->provider.fault=GoogleOidcFixtures.Fault.WRONG_NONCE;}
		var flow=providerFlow(start(browser,GoogleContactSecurity.START,googleIdentity(browser),csrf(browser),null));
		assertFailure(complete(browser,flow));assertThat(count("SELECT count(*) FROM reauth_email_candidates")).isZero();
		assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isZero();assertThat(metadata(browser)).isEqualTo(before);assertThat(sender.messages).isEmpty();
	}

	@Test void clientAddressFieldsAreRejectedEverywhereAndNeverReachTheSender() throws Exception {
		googleLogin(browser);var candidate=candidate(browser);
		assertThat(post(browser,REAUTH+"request",Map.of("email","attacker@example.test"),csrf(browser)).statusCode()).isEqualTo(400);
		assertThat(post(browser,REAUTH+"request?email=attacker%40example.test",Map.of(),csrf(browser)).statusCode()).isEqualTo(400);
		assertThat(post(browser,ENROLL+"request",Map.of("candidateId",candidate,"email","attacker@example.test"),csrf(browser)).statusCode()).isEqualTo(400);
		assertThat(start(browser,GoogleContactSecurity.START,googleIdentity(browser),csrf(browser),"attacker@example.test").statusCode()).isEqualTo(400);
		assertThat(sender.messages).isEmpty();
	}

	@ParameterizedTest @ValueSource(strings={"cancel","expired"})
	void contactOidcCancellationOrFiveMinuteExpiryCannotCreateACandidateOrReplay(String reason) throws Exception {
		googleLogin(browser);var before=metadata(browser);
		var flow=providerFlow(start(browser,GoogleContactSecurity.START,googleIdentity(browser),csrf(browser),null));
		int exchanges=provider.exchanges.get();
		if(reason.equals("cancel")){
			var state=GoogleOidcFixtures.MockProvider.parameters(flow.getRawQuery()).get("state");
			assertFailure(get(browser,GoogleContactSecurity.CALLBACK+"?error=access_denied&state="+GoogleOidcFixtures.MockProvider.encode(state)));
		}else{CLOCK.advance(Duration.ofMinutes(5));assertFailure(complete(browser,flow));}
		assertFailure(complete(browser,flow));assertThat(provider.exchanges.get()).isEqualTo(exchanges);
		assertThat(count("SELECT count(*) FROM reauth_email_candidates")).isZero();assertThat(sender.messages).isEmpty();
		assertThat(metadata(browser)).isEqualTo(before);
	}

	@Test void expiredUnissuedCandidateCannotSendOrRegister() throws Exception {
		googleLogin(browser);var candidate=candidate(browser);CLOCK.advance(Duration.ofMinutes(5));
		assertThat(post(browser,ENROLL+"request",Map.of("candidateId",candidate),csrf(browser)).statusCode()).isEqualTo(401);
		assertThat(status(browser).has("candidate")).isFalse();assertThat(sender.messages).isEmpty();
		assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isZero();
	}

	@Test void loginOtpCannotBeConsumedAsContactEnrollmentEvenWithTheSameRecipient() throws Exception {
		googleLogin(browser);var candidate=candidate(browser);
		try(var anonymous=new Browser()){
			var login=issue(anonymous,"/api/v1/auth/email-otp/request",Map.of("email",provider.email));
			assertThat(verify(browser,ENROLL+"verify",enrollment(candidate,login)).statusCode()).isEqualTo(401);
			assertThat(jdbc.queryForObject("SELECT state FROM email_otp_challenges WHERE id=?",String.class,login.issuanceId())).isEqualTo("ACTIVE");
		}
		assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isZero();
		var actual=issue(browser,ENROLL+"request",Map.of("candidateId",candidate));
		assertThat(verify(browser,ENROLL+"verify",enrollment(candidate,actual)).statusCode()).isEqualTo(204);
	}

	@Test void cancellationExpiryAndUnverifiedCandidatesCannotBecomeRegisteredContacts() throws Exception {
		googleLogin(browser);var candidate=candidate(browser);var message=issue(browser,ENROLL+"request",Map.of("candidateId",candidate));
		assertThat(post(browser,ENROLL+"cancel",Map.of(),csrf(browser)).statusCode()).isEqualTo(204);
		assertThat(verify(browser,ENROLL+"verify",enrollment(candidate,message)).statusCode()).isEqualTo(401);
		assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isZero();
	}

	@ParameterizedTest @ValueSource(longs={299,300})
	void enrollmentOtpRetainsFiveMinuteBoundaryEvenIfIssuanceIsDelayed(long seconds) throws Exception {
		googleLogin(browser);var candidate=candidate(browser);CLOCK.advance(Duration.ofSeconds(240));
		var message=issue(browser,ENROLL+"request",Map.of("candidateId",candidate));CLOCK.advance(Duration.ofSeconds(seconds));
		assertThat(verify(browser,ENROLL+"verify",enrollment(candidate,message)).statusCode()).isEqualTo(seconds<300?204:401);
	}

	@Test void wrongAttemptBudgetSurvivesResendAndAnOidcCandidateRefresh() throws Exception {
		googleLogin(browser);var candidate=candidate(browser);var message=issue(browser,ENROLL+"request",Map.of("candidateId",candidate));
		for(int i=0;i<4;i++){assertThat(verify(browser,ENROLL+"verify",Map.of("candidateId",candidate,"challengeId",message.issuanceId(),"code",wrong(message.code()))).statusCode()).isEqualTo(401);}
		CLOCK.advance(Duration.ofSeconds(60));var freshCandidate=candidate(browser);var fresh=issue(browser,ENROLL+"request",Map.of("candidateId",freshCandidate));
		assertThat(verify(browser,ENROLL+"verify",Map.of("candidateId",freshCandidate,"challengeId",fresh.issuanceId(),"code",wrong(fresh.code()))).statusCode()).isEqualTo(401);
		assertThat(verify(browser,ENROLL+"verify",enrollment(freshCandidate,fresh)).statusCode()).isEqualTo(429);
	}

	@Test void concurrentEnrollmentRequestsSendOnceAndConcurrentVerificationRegistersOnce() throws Exception {
		googleLogin(browser);var candidate=candidate(browser);var csrf=csrf(browser);var cookie=browser.cookie();
		try(var one=new Browser(cookie);var two=new Browser(cookie);var executor=Executors.newFixedThreadPool(2)){
			var a=executor.submit(()->post(one,ENROLL+"request",Map.of("candidateId",candidate),csrf).statusCode());
			var b=executor.submit(()->post(two,ENROLL+"request",Map.of("candidateId",candidate),csrf).statusCode());
			assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(202,429);
			assertThat(sender.messages).hasSize(1);var message=sender.messages.values().iterator().next();
			var v1=executor.submit(()->post(one,ENROLL+"verify",enrollment(candidate,message),csrf).statusCode());
			var v2=executor.submit(()->post(two,ENROLL+"verify",enrollment(candidate,message),csrf).statusCode());
			var statuses=List.of(v1.get(20,TimeUnit.SECONDS),v2.get(20,TimeUnit.SECONDS));assertThat(statuses.stream().filter(s->s==204).count()).isEqualTo(1);
			assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isEqualTo(1);
		}
	}

	@Test void anotherSessionCannotUseCandidateOrItsOtp() throws Exception {
		googleLogin(browser);var candidate=candidate(browser);var message=issue(browser,ENROLL+"request",Map.of("candidateId",candidate));
		try(var other=new Browser()){googleLogin(other);
			assertThat(post(other,ENROLL+"request",Map.of("candidateId",candidate),csrf(other)).statusCode()).isEqualTo(401);
			assertThat(verify(other,ENROLL+"verify",enrollment(candidate,message)).statusCode()).isEqualTo(401);
		}
		assertThat(verify(browser,ENROLL+"verify",enrollment(candidate,message)).statusCode()).isEqualTo(204);
	}

	@Test void sameEmailAcrossDifferentGoogleAndEmailUsersDoesNotMergeOrShareOtpBudgets() throws Exception {
		googleLogin(browser);enroll(browser);var first=metadata(browser).userId();String address=provider.email;
		try(var other=new Browser();var email=new Browser()){
			provider.subject="second-google-sub";googleLogin(other);enroll(other);var second=metadata(other).userId();emailLogin(email,address);
			assertThat(first.equals(second)).isFalse();assertThat(metadata(email).userId().equals(first)).isFalse();
			var a=issue(browser,REAUTH+"request",Map.of());var b=issue(other,REAUTH+"request",Map.of());
			assertThat(verify(browser,REAUTH+"verify",Map.of("challengeId",b.issuanceId(),"code",b.code())).statusCode()).isEqualTo(401);
			assertThat(verify(other,REAUTH+"verify",Map.of("challengeId",b.issuanceId(),"code",b.code())).statusCode()).isEqualTo(204);
			assertThat(verify(browser,REAUTH+"verify",Map.of("challengeId",a.issuanceId(),"code",a.code())).statusCode()).isEqualTo(204);
			assertThat(count("SELECT count(*) FROM users")).isEqualTo(3);assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isEqualTo(3);
			assertThat(count("SELECT count(DISTINCT email) FROM user_reauthentication_emails")).isEqualTo(1);
		}
	}

	@Test void linkedProvidersSendOnlyToOneRepresentativeAndIdentitySelectionIsRejected() throws Exception {
		googleLogin(browser);var owner=metadata(browser).userId();String first="registered-first@example.test",second="another-login@example.test";
		var firstId=linkFixtureEmail(owner,first);var secondId=linkFixtureEmail(owner,second);
		try(var verified=new Browser()){emailLogin(verified,first);}
		try(var verified=new Browser()){emailLogin(verified,second);}
		int sent=sender.messages.size();assertThat(post(browser,REAUTH+"request",Map.of("identityId",secondId),csrf(browser)).statusCode()).isEqualTo(400);
		assertThat(sender.messages.size()).isEqualTo(sent);
		var message=issue(browser,REAUTH+"request",Map.of());assertThat(message.recipient().equals(first)).isTrue();
		assertThat(post(browser,REAUTH+"request",Map.of(),csrf(browser)).statusCode()).isEqualTo(429);assertThat(sender.messages.size()).isEqualTo(sent+1);
		provider.email="changed-google-email@example.test";
		assertFailure(start(browser,GoogleContactSecurity.START,googleIdentity(browser),csrf(browser),null));
		assertThat(jdbc.queryForObject("SELECT email FROM user_reauthentication_emails WHERE user_id=?",String.class,owner).equals(first)).isTrue();
	}

	@ParameterizedTest @ValueSource(strings={"contact","session"})
	void contactInsertOrSessionWriteFailureRollsBackOtpAndRegistrationTogether(String target) throws Exception {
		googleLogin(browser);var candidate=candidate(browser);var message=issue(browser,ENROLL+"request",Map.of("candidateId",candidate));var before=metadata(browser);
		jdbc.execute("CREATE OR REPLACE FUNCTION contact_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated contact fault' USING ERRCODE='23514'; END $$");
		String table=target.equals("contact")?"user_reauthentication_emails":"spring_session_attributes";
		jdbc.execute("CREATE TRIGGER contact_fail BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION contact_fail()");
		assertThat(verify(browser,ENROLL+"verify",enrollment(candidate,message)).statusCode()).isEqualTo(503);
		assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isZero();assertThat(metadata(browser)).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT state FROM email_otp_challenges WHERE id=?",String.class,message.issuanceId())).isEqualTo("ACTIVE");
		jdbc.execute("DROP TRIGGER contact_fail ON "+table);assertThat(verify(browser,ENROLL+"verify",enrollment(candidate,message)).statusCode()).isEqualTo(204);
	}

	@Test void newRoutesRequireAuthenticationAndCsrfAndNeverTreatLoginCodesAsEnrollmentCodes() throws Exception {
		for(var path:List.of(ENROLL+"request",ENROLL+"verify",ENROLL+"cancel")){assertThat(post(browser,path,Map.of(),null).statusCode()).isEqualTo(401);}
		assertThat(get(browser,CONTACT).statusCode()).isEqualTo(401);googleLogin(browser);var candidate=candidate(browser);
		for(var path:List.of(ENROLL+"request",ENROLL+"verify",ENROLL+"cancel")){assertThat(post(browser,path,Map.of(),null).statusCode()).isEqualTo(403);}
		var message=issue(browser,ENROLL+"request",Map.of("candidateId",candidate));
		try(var anonymous=new Browser()){
			assertThat(post(anonymous,"/api/v1/auth/email-otp/verify",Map.of("email",message.recipient(),"challengeId",message.issuanceId(),"code",message.code()),csrf(anonymous)).statusCode()).isEqualTo(401);
		}
		assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isZero();
	}

	@Test void concurrentCommonRequestsShareOnePurposeAndSendExactlyOneEmail() throws Exception {
		googleLogin(browser);enroll(browser);var csrf=csrf(browser);var cookie=browser.cookie();
		try(var one=new Browser(cookie);var two=new Browser(cookie);var executor=Executors.newFixedThreadPool(2)){
			var a=executor.submit(()->post(one,REAUTH+"request",Map.of(),csrf).statusCode());var b=executor.submit(()->post(two,REAUTH+"request",Map.of(),csrf).statusCode());
			assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(202,429);
			assertThat(sender.messages.values().stream().filter(m->m.purpose()==OtpPurpose.REAUTH).count()).isEqualTo(1);
		}
	}

	@Test void afterCommitSessionReadFailureKeepsVerifiedContactButConsumesProofAndRequiresLogin() throws Exception {
		googleLogin(browser);var candidate=candidate(browser);var message=issue(browser,ENROLL+"request",Map.of("candidateId",candidate));var old=browser.cookie();
		GoogleOidcFixtures.onSessionRead=session->{if(!session.getId().equals(old)){throw new org.springframework.dao.DataAccessResourceFailureException("isolated postcommit fault");}};
		assertThat(verify(browser,ENROLL+"verify",enrollment(candidate,message)).statusCode()).isEqualTo(503);GoogleReauthenticationFixtures.reset();
		assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT state FROM email_otp_challenges WHERE id=?",String.class,message.issuanceId())).isEqualTo("CONSUMED");
		assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(401);
	}

	@ParameterizedTest @ValueSource(strings={"before","after","readback","cookie","response"})
	void registrationCannotSucceedAcrossAbsoluteSessionExpiry(String point) throws Exception {
		googleLogin(browser);var candidate=candidate(browser);var message=issue(browser,ENROLL+"request",Map.of("candidateId",candidate));var old=browser.cookie();
		if(point.equals("before")){GoogleReauthenticationFixtures.beforeRotate=ignored->CLOCK.advance(Duration.ofDays(90));}
		if(point.equals("after")){GoogleReauthenticationFixtures.afterRotate=ignored->CLOCK.advance(Duration.ofDays(90));}
		var reads=new java.util.concurrent.atomic.AtomicInteger();int target=point.equals("readback")?1:point.equals("cookie")?2:3;
		if(!point.equals("before")&&!point.equals("after")){GoogleOidcFixtures.onSessionRead=session->{if(!session.getId().equals(old)&&reads.incrementAndGet()==target){CLOCK.advance(Duration.ofDays(90));}};}
		assertThat(verify(browser,ENROLL+"verify",enrollment(candidate,message)).statusCode()).isNotEqualTo(204);GoogleReauthenticationFixtures.reset();
		assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isEqualTo(point.equals("before")||point.equals("after")?0:1);
		assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(401);
	}

	private UUID linkFixtureEmail(UUID user,String email){var id=UUID.randomUUID();jdbc.update("INSERT INTO auth_identities(id,user_id,provider,issuer,subject) VALUES (?,?,'email','overload:email',?)",id,user,email);return id;}
	private void enroll(Browser current)throws Exception{var candidate=candidate(current);var message=issue(current,ENROLL+"request",Map.of("candidateId",candidate));assertThat(verify(current,ENROLL+"verify",enrollment(candidate,message)).statusCode()).isEqualTo(204);}
	private UUID candidate(Browser current)throws Exception{
		var flow=providerFlow(start(current,GoogleContactSecurity.START,googleIdentity(current),csrf(current),null));
		var result=complete(current,flow);assertThat(result.statusCode()).isEqualTo(302);assertThat(result.headers().firstValue("Location").orElse("").equals(CONTACT)).isTrue();
		return UUID.fromString(status(current).get("candidate").get("id").asString());
	}
	private void googleLogin(Browser current)throws Exception{var result=complete(current,providerFlow(get(current,"/oauth2/authorization/google")));assertThat(result.statusCode()).isEqualTo(302);assertThat(result.headers().firstValue("Location").orElse("").equals("/api/v1/auth/session")).isTrue();}
	private void emailLogin(Browser current,String email)throws Exception{var csrf=csrf(current);var issued=post(current,"/api/v1/auth/email-otp/request",Map.of("email",email),csrf);assertThat(issued.statusCode()).isEqualTo(202);var message=sender.messages.get(UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString()));assertThat(post(current,"/api/v1/auth/email-otp/verify",Map.of("email",email,"challengeId",message.issuanceId(),"code",message.code()),csrf).statusCode()).isEqualTo(204);}
	private OtpEmailSender.Message issue(Browser current,String path,Object body)throws Exception{var result=post(current,path,body,csrf(current));assertThat(result.statusCode()).isEqualTo(202);return sender.messages.get(UUID.fromString(JSON.readTree(result.body()).get("challengeId").asString()));}
	private HttpResponse<String> verify(Browser current,String path,Object body)throws Exception{return post(current,path,body,csrf(current));}
	private static Map<String,Object> enrollment(UUID candidate,OtpEmailSender.Message message){return Map.of("candidateId",candidate,"challengeId",message.issuanceId(),"code",message.code());}
	private UUID googleIdentity(Browser current){return jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=? AND provider='google'",UUID.class,metadata(current).userId());}
	private tools.jackson.databind.JsonNode status(Browser current)throws Exception{return JSON.readTree(get(current,CONTACT).body());}
	private String csrf(Browser current)throws Exception{return JSON.readTree(get(current,"/api/v1/auth/csrf").body()).get("token").asString();}
	private static SessionMetadata metadata(Browser current){Session session=app.getBean(SessionRepository.class).findById(current.cookie());assertThat(session!=null).isTrue();return session.getAttribute(SessionMetadata.ATTRIBUTE);}
	private long count(String sql){return jdbc.queryForObject(sql,Long.class);}
	private static String wrong(String code){return(code.startsWith("0")?"1":"0")+code.substring(1);}
	private HttpResponse<String> start(Browser current,String path,UUID identity,String csrf,String email)throws Exception{var request=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/x-www-form-urlencoded");if(csrf!=null)request.header("X-CSRF-TOKEN",csrf);return current.client.send(request.POST(HttpRequest.BodyPublishers.ofString("identityId="+identity+(email==null?"":"&email="+URLEncoder.encode(email,java.nio.charset.StandardCharsets.UTF_8)))).build(),HttpResponse.BodyHandlers.ofString());}
	private URI providerFlow(HttpResponse<String> result)throws Exception{assertThat(result.statusCode()).isEqualTo(302);URI uri=URI.create(result.headers().firstValue("Location").orElseThrow());assertThat(uri.getHost()).isEqualTo("127.0.0.1");if(uri.getRawQuery().contains("google-contact")){var params=GoogleOidcFixtures.MockProvider.parameters(uri.getRawQuery());assertThat(params).doesNotContainKeys("max_age","claims");}try(var client=HttpClient.newHttpClient()){var response=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString());return URI.create(response.headers().firstValue("Location").orElseThrow());}}
	private HttpResponse<String> complete(Browser current,URI uri)throws Exception{return current.client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());}
	private HttpResponse<String> get(Browser current,String path)throws Exception{return complete(current,URI.create(base+path));}
	private HttpResponse<String> post(Browser current,String path,Object body,String csrf)throws Exception{var request=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json");if(csrf!=null)request.header("X-CSRF-TOKEN",csrf);return current.client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
	private void assertFailure(HttpResponse<String> response){assertThat(response.statusCode()).isEqualTo(302);assertThat(response.headers().firstValue("Location").orElse("").equals(GoogleContactSecurity.FAILURE)).isTrue();}
	static final class Browser implements AutoCloseable{final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();Browser(){}Browser(String id){var cookie=new HttpCookie("SESSION",id);cookie.setPath("/");cookie.setVersion(0);cookies.getCookieStore().add(URI.create(base),cookie);}String cookie(){return cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("SESSION")).findFirst().orElseThrow().getValue();}@Override public void close(){client.close();}}
}
