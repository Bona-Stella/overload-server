package com.overload.overload_server.auth.google;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.auth.email.OtpEmailSender;
import com.overload.overload_server.security.EmailOwnershipProof;
import com.overload.overload_server.security.SessionMetadata;
import com.overload.testfixture.*;
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
import org.testcontainers.junit.jupiter.*;
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
class GoogleIdentityIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_google_identity_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString()).withLabel("overload.task","google-identity-3b-3b");
    static final JsonMapper JSON=JsonMapper.builder().build();
    static ConfigurableApplicationContext app;
    static GoogleOidcFixtures.MockProvider provider;
    static JdbcTemplate jdbc;
    static EmailOtpFixtures.FakeSender sender;
    static String base,key;
    static final String LINK="/oauth2/link/google",UNLINK="/api/v1/auth/identities/google/unlink",ELINK="/api/v1/auth/identities/email/link",REAUTH="/api/v1/auth/reauth/email/";
    Browser browser;
    @BeforeAll static void start() throws Exception {
        provider=new GoogleOidcFixtures.MockProvider();GoogleOidcFixtures.provider=provider;CLOCK.reset();
        byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);key=Base64.getEncoder().encodeToString(secret);boot(true);
    }
    static void boot(boolean google){boot(google,true);}
    static void boot(boolean google,boolean email){
        app=new SpringApplicationBuilder(OverloadServerApplication.class,EmailOtpFixtures.class,GoogleOidcFixtures.class,EmailIdentityFixtures.class)
                .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration","--spring.datasource.url="+POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),"--GOOGLE_OIDC_ENABLED="+google,"--AUTH_FRONTEND_RETURN_URI=","--AUTH_LINK_FRONTEND_RETURN_URI=", "--AUTH_CONTACT_FRONTEND_RETURN_URI=",
                        "--EMAIL_LOGIN_ENABLED="+email,"--GOOGLE_REAUTH_ENABLED=false","--OTP_EMAIL_ENABLED=false","--OTP_HMAC_KEY="+key,"--GOOGLE_CLIENT_ID="+provider.clientId,
                        "--GOOGLE_CLIENT_SECRET="+provider.clientSecret,"--server.address=127.0.0.1","--server.port="+(base==null?0:URI.create(base).getPort()),"--spring.session.jdbc.cleanup-cron=-",
                        "--logging.level.root=WARN","--spring.main.banner-mode=off");
        jdbc=app.getBean(JdbcTemplate.class);sender=app.getBean(EmailOtpFixtures.FakeSender.class);
        com.overload.testfixture.MixedPersistenceAssertions.verify(app);
        base="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
    }
    @BeforeEach void isolate(){
        CLOCK.reset();provider.reset();sender.reset();ReauthenticationFixtures.reset();GoogleOidcFixtures.onSessionRead=null;GoogleOidcFixtures.corruptMetadataRead=false;
        jdbc.execute("DROP TRIGGER IF EXISTS identity_fail ON auth_identities");
        jdbc.execute("DROP TRIGGER IF EXISTS version_fail ON google_identity_change_versions");
        jdbc.execute("DROP TRIGGER IF EXISTS proof_fail ON spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");jdbc.update("DELETE FROM email_otp_login_claims");jdbc.update("UPDATE email_otp_limits SET active_challenge_id=NULL");
        for(var table:List.of("google_identity_change_versions","email_otp_challenges","email_otp_limits","email_otp_rate_limits","reauth_email_candidates","user_reauthentication_emails","deload_weekly_assessments", "deload_analysis_versions", "exercise_definitions", "user_workout_settings", "auth_identities", "users")){jdbc.update("DELETE FROM "+table);}
        browser=new Browser();
    }
    @AfterEach void close(){ReauthenticationFixtures.reset();GoogleOidcFixtures.onSessionRead=null;browser.close();}
    @AfterAll static void stop(){if(app!=null)app.close();if(provider!=null)provider.close();}


    @Test void emailProofLinksGoogleWithoutProvisioningAndBothLoginsReuseTheSameUser() throws Exception {
        emailLogin(browser,"owner@example.test");var data=business();var before=metadata(browser);var proof=proof(browser);var sid=browser.cookie();int sent=sender.messages.size();
        assertSuccess(callback(browser,begin(browser)));
        assertThat(business()).isEqualTo(data);assertThat(metadata(browser)).isEqualTo(before);assertThat(proof(browser)).isEqualTo(proof);
        assertThat(browser.cookie().equals(sid)).isTrue();assertThat(sender.messages.size()).isEqualTo(sent);
        assertSuccess(callback(browser,begin(browser)));assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isEqualTo(1);
        try(var google=new Browser();var email=new Browser()){
            googleLogin(google);assertThat(metadata(google).userId().equals(before.userId())).isTrue();
            CLOCK.advance(Duration.ofSeconds(60));emailLogin(email,"owner@example.test");assertThat(metadata(email).userId().equals(before.userId())).isTrue();
        }
        assertThat(business()).isEqualTo(data);assertNoTokens();
        assertThat(JSON.readTree(get(browser,"/api/v1/auth/identities").body()).get("identities").size()).isEqualTo(2);
    }

    @Test void sameEmailNeverTransfersAnExistingOtherUsersGoogleSubject() throws Exception {
        googleLogin(browser);var googleOwner=metadata(browser).userId();
        try(var email=new Browser()){
            emailLogin(email,provider.email);var data=business();var original=metadata(email);var evidence=proof(email);
            assertError(callback(email,begin(email)),409,"IDENTITY_ALREADY_OWNED");
            assertThat(business()).isEqualTo(data);assertThat(metadata(email)).isEqualTo(original);assertThat(proof(email)).isEqualTo(evidence);
            assertThat(metadata(email).userId().equals(googleOwner)).isFalse();assertThat(count("SELECT count(*) FROM users")).isEqualTo(2);
        }
    }

    @Test void linkUsesSubjectNotEmailAndNeverReplacesRepresentativeOrAddsEmailLogin() throws Exception {
        googleLogin(browser);enroll(browser);var owner=metadata(browser).userId();var data=business();var contact=jdbc.queryForMap("SELECT * FROM user_reauthentication_emails WHERE user_id=?",owner);
        provider.subject="second-google-sub";provider.email="different-display@example.test";provider.emailVerified=null;
        assertSuccess(callback(browser,begin(browser)));
        assertThat(business()).isEqualTo(data);assertThat(jdbc.queryForMap("SELECT * FROM user_reauthentication_emails WHERE user_id=?",owner)).isEqualTo(contact);
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='email'")).isZero();assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isEqualTo(2);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.EnumSource(value=GoogleOidcFixtures.Fault.class,names="NONE",mode=org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
    void officialOidcFailuresPreserveTheOriginalAccountAndProof(GoogleOidcFixtures.Fault fault) throws Exception {
        emailLogin(browser,"owner@example.test");var data=business();var before=metadata(browser);var evidence=proof(browser);provider.fault=fault;
        assertFailure(callback(browser,begin(browser)));assertThat(business()).isEqualTo(data);assertThat(metadata(browser)).isEqualTo(before);assertThat(proof(browser)).isEqualTo(evidence);assertNoTokens();
    }

    @Test void tamperedPkceCannotLinkARealSignedSubject() throws Exception {
        emailLogin(browser,"owner@example.test");var started=start(browser,true);var uri=URI.create(started.headers().firstValue("Location").orElseThrow());
        var tampered=URI.create(uri.toString().replaceFirst("code_challenge=[^&]+","code_challenge=invalid-challenge"));
        try(var client=HttpClient.newHttpClient()){
            var location=client.send(HttpRequest.newBuilder(tampered).GET().build(),HttpResponse.BodyHandlers.ofString()).headers().firstValue("Location").orElseThrow();
            assertFailure(callback(browser,URI.create(location)));
        }
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isZero();
    }

    @Test void stateAndPurposeCrossingCannotConsumeTheLegitimateLinkPending() throws Exception {
        emailLogin(browser,"owner@example.test");var flow=begin(browser);int exchanged=provider.exchanges.get();
        assertFailure(get(browser,"/login/oauth2/code/google-link?code=invalid&state=invalid"));
        assertThat(callback(browser,URI.create(flow.toString().replace("/google-link?","/google?"))).statusCode()).isEqualTo(409);
        var contact=callback(browser,URI.create(flow.toString().replace("/google-link?","/google-contact?")));
        assertThat(contact.headers().firstValue("Location").orElse("")).isEqualTo(GoogleContactSecurity.FAILURE);
        assertThat(provider.exchanges.get()).isEqualTo(exchanged);assertSuccess(callback(browser,flow));
    }

    @Test void cancelledLinkConsumesStateButKeepsAuthenticationAndCsrf() throws Exception {
        emailLogin(browser,"owner@example.test");var before=metadata(browser);var token=csrf(browser);var flow=begin(browser);
        var state=GoogleOidcFixtures.MockProvider.parameters(flow.getRawQuery()).get("state");
        assertFailure(get(browser,"/login/oauth2/code/google-link?error=access_denied&state="+GoogleOidcFixtures.MockProvider.encode(state)));
        int exchanged=provider.exchanges.get();assertFailure(callback(browser,flow));assertThat(provider.exchanges.get()).isEqualTo(exchanged);
        assertThat(metadata(browser)).isEqualTo(before);assertThat(postWithToken(browser,ELINK,Map.of(),token).statusCode()).isEqualTo(200);
    }

    @ParameterizedTest @ValueSource(strings={"same-user","other-user"})
    void pendingCannotBeUsedFromAnotherBrowserOrUser(String target) throws Exception {
        emailLogin(browser,"owner@example.test");var flow=begin(browser);
        try(var other=new Browser()){
            if(target.equals("same-user")){CLOCK.advance(Duration.ofSeconds(60));}emailLogin(other,target.equals("same-user")?"owner@example.test":"other@example.test");
            assertFailure(callback(other,flow));
        }
        assertSuccess(callback(browser,flow));
    }

    @ParameterizedTest @ValueSource(longs={600,601})
    void proofBoundaryAtLinkStartIsTenMinutes(long seconds) throws Exception {
        emailLogin(browser,"owner@example.test");CLOCK.advance(Duration.ofSeconds(seconds));
        if(seconds==600){assertSuccess(callback(browser,begin(browser)));}else{assertError(start(browser,true),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");}
    }

    @ParameterizedTest @ValueSource(longs={299,300})
    void pendingHasStrictFiveMinuteLifetime(long seconds) throws Exception {
        emailLogin(browser,"owner@example.test");var flow=begin(browser);CLOCK.advance(Duration.ofSeconds(seconds));
        if(seconds==299){assertSuccess(callback(browser,flow));}else{assertThat(callback(browser,flow).statusCode()).isIn(401,302);assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isZero();}
    }

    @ParameterizedTest @ValueSource(strings={"proof","absolute","rotate","logout"})
    void changesDuringProviderExchangeCannotAuthorizeTheLateCallback(String change) throws Exception {
        emailLogin(browser,"owner@example.test");if(change.equals("proof")){CLOCK.advance(Duration.ofSeconds(590));}
        var flow=begin(browser);var data=business();
        provider.onExchange=()->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try{switch(change){case "proof"->CLOCK.advance(Duration.ofSeconds(11));case "absolute"->CLOCK.advance(Duration.ofDays(90));case "rotate"-> {assertThat(post(browser,"/__email_identity_test__/rotate",Map.of()).statusCode()).isEqualTo(204);}default->{assertThat(post(browser,"/api/v1/auth/logout",Map.of()).statusCode()).isEqualTo(204);}}}catch(Exception e){throw new AssertionError(e);}
        };
        var result=callback(browser,flow);assertThat(result.statusCode()).isEqualTo(change.equals("proof")?403:401);
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isZero();assertThat(business()).isEqualTo(data);
    }

    @Test void ssoTimestampAloneAndUnknownInputsCannotStartLinkOrUnlink() throws Exception {
        assertThat(start(browser,false).statusCode()).isEqualTo(401);googleLogin(browser);enroll(browser);var id=googleIdentity(browser);dropProof(browser);
        assertError(start(browser,true),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");assertError(post(browser,UNLINK,Map.of("identityId",id)),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
        reauth(browser);assertThat(start(browser,false).statusCode()).isEqualTo(403);
        assertThat(post(browser,LINK,Map.of("email","untrusted@example.test")).statusCode()).isEqualTo(400);
        assertThat(post(browser,UNLINK,Map.of("identityId",id,"userId",UUID.randomUUID())).statusCode()).isEqualTo(400);
        assertThat(get(browser,"/oauth2/link/google").statusCode()).isEqualTo(405);
    }

    @Test void unlinkPreservesContactHistoryAndLaterGoogleLoginCannotAccessTheOldAccount() throws Exception {
        googleLogin(browser);enroll(browser);var user=metadata(browser).userId();var id=googleIdentity(browser);assertThat(post(browser,ELINK,Map.of()).statusCode()).isEqualTo(201);
        var before=metadata(browser);var evidence=proof(browser);var data=business();
        assertThat(post(browser,UNLINK,Map.of("identityId",id)).statusCode()).isEqualTo(204);
        assertError(post(browser,UNLINK,Map.of("identityId",id)),404,"IDENTITY_NOT_FOUND");
        assertThat(business()).isEqualTo(data);assertThat(metadata(browser)).isEqualTo(before);assertThat(proof(browser)).isEqualTo(evidence);
        assertThat(count("SELECT count(*) FROM reauth_email_candidates WHERE google_identity_id IS NULL AND retired_google_identity_id IS NOT NULL AND invalidated_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM email_otp_challenges WHERE purpose='CONTACT_ENROLL' AND reauth_identity_id IS NULL AND retired_reauth_identity_id IS NOT NULL AND state='CONSUMED'")).isEqualTo(1);
        try(var google=new Browser()){googleLogin(google);assertThat(metadata(google).userId().equals(user)).isFalse();}
    }

    @Test void lastGoogleAndForeignIdentityUnlinkAreRejected() throws Exception {
        googleLogin(browser);enroll(browser);var id=googleIdentity(browser);
        assertError(post(browser,UNLINK,Map.of("identityId",id)),409,"LAST_LOGIN_IDENTITY");
        try(var other=new Browser()){emailLogin(other,"other@example.test");assertError(post(other,UNLINK,Map.of("identityId",id)),404,"IDENTITY_NOT_FOUND");}
    }

    @ParameterizedTest @ValueSource(strings={"email-disabled","sender-disabled","google-disabled"})
    void disabledLoginCapabilitiesAreIncludedInLastMethodProtection(String mode)throws Exception{
        emailLogin(browser,"owner@example.test");assertSuccess(callback(browser,begin(browser)));var id=googleIdentity(browser);
        if(mode.equals("sender-disabled")){sender.available=false;assertError(post(browser,UNLINK,Map.of("identityId",id)),409,"LAST_LOGIN_IDENTITY");return;}
        app.close();boot(!mode.equals("google-disabled"),!mode.equals("email-disabled"));
        try{
            if(mode.equals("email-disabled")){assertError(post(browser,UNLINK,Map.of("identityId",id)),409,"LAST_LOGIN_IDENTITY");try(var anon=new Browser()){assertError(post(anon,"/api/v1/auth/email-otp/request",Map.of("email","owner@example.test")),503,"EMAIL_LOGIN_UNAVAILABLE");}}
            else{assertError(start(browser,true),503,"GOOGLE_LINK_UNAVAILABLE");assertThat(post(browser,UNLINK,Map.of("identityId",id)).statusCode()).isEqualTo(204);}
        }finally{app.close();boot(true,true);}
    }

    @Test void parallelCallbacksConsumeStateAndExchangeTheCodeOnlyOnce() throws Exception {
        emailLogin(browser,"owner@example.test");var flow=begin(browser);int before=provider.exchanges.get();
        try(var one=new Browser(browser.cookie());var two=new Browser(browser.cookie());var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->callback(one,flow));var b=executor.submit(()->callback(two,flow));
            var results=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertThat(results.stream().filter(r->r.headers().firstValue("Location").orElse("").equals("/api/v1/auth/identities")).count()).isEqualTo(1);
        }
        assertThat(provider.exchanges.get()).isEqualTo(before+1);assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isEqualTo(1);
    }

    @Test void twoOwnersRacingForSameGoogleSubjectHaveOneOwner() throws Exception {
        emailLogin(browser,"owner@example.test");var first=begin(browser);
        try(var other=new Browser();var executor=Executors.newFixedThreadPool(2)){
            emailLogin(other,"other@example.test");var second=begin(other);
            var a=executor.submit(()->callback(browser,first));var b=executor.submit(()->callback(other,second));
            assertThat(List.of(a.get(20,TimeUnit.SECONDS).statusCode(),b.get(20,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(302,409);
        }
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isEqualTo(1);assertThat(count("SELECT count(*) FROM users")).isEqualTo(2);
    }

    @Test void unlinkInvalidatesLinkEvenAfterStateWasConsumedBeforeNetworkExchange() throws Exception {
        emailLogin(browser,"owner@example.test");assertSuccess(callback(browser,begin(browser)));var id=googleIdentity(browser);var flow=begin(browser);
        provider.onExchange=()->{try{assertThat(post(browser,UNLINK,Map.of("identityId",id)).statusCode()).isEqualTo(204);}catch(Exception e){throw new AssertionError(e);}};
        assertError(callback(browser,flow),409,"IDENTITY_CHANGE_SUPERSEDED");
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isZero();
    }

    @Test void simultaneousUnlinksDeleteOneCredentialAndPreserveTheOtherLogin() throws Exception {
        emailLogin(browser,"owner@example.test");assertSuccess(callback(browser,begin(browser)));var id=googleIdentity(browser);var token=csrf(browser);
        try(var one=new Browser(browser.cookie());var two=new Browser(browser.cookie());var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->postWithToken(one,UNLINK,Map.of("identityId",id),token).statusCode());var b=executor.submit(()->postWithToken(two,UNLINK,Map.of("identityId",id),token).statusCode());
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(204,404);
        }
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='email'")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"INSERT","DELETE"})
    void sqlErrorsRollbackIdentityAndHistoryAndKeepOriginalSession(String operation)throws Exception{
        googleLogin(browser);enroll(browser);post(browser,ELINK,Map.of());var id=googleIdentity(browser);var data=business();var evidence=proof(browser);var before=metadata(browser);
        var candidates=jdbc.queryForList("SELECT * FROM reauth_email_candidates ORDER BY id");
        jdbc.execute("CREATE OR REPLACE FUNCTION identity_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated Google identity fault' USING ERRCODE='23514'; END $$");
        jdbc.execute("CREATE TRIGGER identity_fail BEFORE "+operation+" ON auth_identities FOR EACH ROW EXECUTE FUNCTION identity_fail()");
        if(operation.equals("INSERT")){provider.subject="unclaimed-sub";assertError(callback(browser,begin(browser)),503,"IDENTITY_CHANGE_UNAVAILABLE");}
        else{assertError(post(browser,UNLINK,Map.of("identityId",id)),503,"IDENTITY_CHANGE_UNAVAILABLE");}
        assertThat(business()).isEqualTo(data);assertThat(metadata(browser)).isEqualTo(before);assertThat(proof(browser)).isEqualTo(evidence);
        assertThat(jdbc.queryForList("SELECT * FROM reauth_email_candidates ORDER BY id")).isEqualTo(candidates);assertThat(googleIdentity(browser)).isEqualTo(id);
    }

    @Test void restartPreservesPendingOriginalSessionAndExactEmailProof() throws Exception {
        emailLogin(browser,"owner@example.test");var flow=begin(browser);var evidence=proof(browser);var before=metadata(browser);app.close();boot(true);
        assertSuccess(callback(browser,flow));assertThat(metadata(browser)).isEqualTo(before);assertThat(proof(browser)).isEqualTo(evidence);
    }

    @Test void silentlyLostUnlinkVersionRollsBackCredentialHistoryAndPendingInvalidation()throws Exception{
        googleLogin(browser);enroll(browser);post(browser,ELINK,Map.of());var id=googleIdentity(browser);var flow=begin(browser);var before=metadata(browser);
        jdbc.execute("CREATE OR REPLACE FUNCTION version_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NULL; END $$");
        jdbc.execute("CREATE TRIGGER version_fail BEFORE INSERT ON google_identity_change_versions FOR EACH ROW EXECUTE FUNCTION version_fail()");
        assertError(post(browser,UNLINK,Map.of("identityId",id)),503,"IDENTITY_CHANGE_UNAVAILABLE");
        assertThat(googleIdentity(browser)).isEqualTo(id);assertThat(metadata(browser)).isEqualTo(before);
        assertThat(count("SELECT count(*) FROM reauth_email_candidates WHERE google_identity_id IS NOT NULL AND invalidated_at IS NULL")).isEqualTo(1);
        jdbc.execute("DROP TRIGGER version_fail ON google_identity_change_versions");assertSuccess(callback(browser,flow));
    }

    @ParameterizedTest @ValueSource(strings={"pending","candidate","exchange"})
    void unlinkInvalidatesOtherSessionContactFlowsAndPreservesTheirHistory(String phase)throws Exception{
        googleLogin(browser);var user=metadata(browser).userId();var identity=googleIdentity(browser);
        jdbc.update("INSERT INTO auth_identities(id,user_id,provider,issuer,subject) VALUES (?,?,'email','overload:email','legacy@example.test')",UUID.randomUUID(),user);
        var request=HttpRequest.newBuilder(URI.create(base+"/oauth2/contact/google")).header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf(browser)).POST(HttpRequest.BodyPublishers.ofString("identityId="+identity)).build();
        var flow=flow(browser.client.send(request,HttpResponse.BodyHandlers.ofString()));
        UUID candidate=null;OtpEmailSender.Message message=null;
        if(phase.equals("candidate")){
            assertThat(callback(browser,flow).statusCode()).isEqualTo(302);
            candidate=UUID.fromString(JSON.readTree(get(browser,REAUTH+"contact").body()).get("candidate").get("id").asString());
            message=issue(browser,REAUTH+"registration/request",Map.of("candidateId",candidate));
        }
        try(var email=new Browser()){
            emailLogin(email,"legacy@example.test");
            if(phase.equals("exchange")){
                provider.onExchange=()->{try{assertThat(post(email,UNLINK,Map.of("identityId",identity)).statusCode()).isEqualTo(204);}catch(Exception e){throw new AssertionError(e);}};
                var result=callback(browser,flow);assertThat(result.headers().firstValue("Location").orElse("")).isEqualTo(GoogleContactSecurity.FAILURE);
            }else{
                assertThat(post(email,UNLINK,Map.of("identityId",identity)).statusCode()).isEqualTo(204);
                if(candidate==null){assertThat(callback(browser,flow).headers().firstValue("Location").orElse("")).isEqualTo(GoogleContactSecurity.FAILURE);}
                else{
                    assertThat(post(browser,REAUTH+"registration/verify",Map.of("candidateId",candidate,"challengeId",message.issuanceId(),"code",message.code())).statusCode()).isEqualTo(401);
                    assertThat(count("SELECT count(*) FROM reauth_email_candidates WHERE retired_google_identity_id IS NOT NULL AND invalidated_at IS NOT NULL")).isEqualTo(1);
                    assertThat(jdbc.queryForObject("SELECT state FROM email_otp_challenges WHERE id=?",String.class,message.issuanceId())).isEqualTo("ACTIVE");
                }
            }
            assertThat(metadata(email).userId().equals(user)).isTrue();assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(200);
        }
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isZero();assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"proof","pending","absolute"})
    void actualLinkCommitRejectsEvidenceExpiringWhileIdentityInsertIsBlocked(String limit)throws Exception{
        emailLogin(browser,"owner@example.test");if(limit.equals("proof")){CLOCK.advance(Duration.ofSeconds(590));}var flow=begin(browser);
        jdbc.execute("CREATE OR REPLACE FUNCTION identity_delay() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(730032); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER identity_fail BEFORE INSERT ON auth_identities FOR EACH ROW EXECUTE FUNCTION identity_delay()");
        try(var connection=jdbc.getDataSource().getConnection();var executor=Executors.newSingleThreadExecutor()){
            var gate=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));gate.execute("SELECT pg_advisory_lock(730032)");
            var result=executor.submit(()->callback(browser,flow));
            try{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")==0&&System.nanoTime()<deadline){Thread.sleep(10);}
                assertThat(count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")).isPositive();
                CLOCK.advance(limit.equals("absolute")?Duration.ofDays(90):limit.equals("pending")?Duration.ofSeconds(300):Duration.ofSeconds(11));
            }finally{gate.execute("SELECT pg_advisory_unlock(730032)");}
            assertThat(result.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(limit.equals("proof")?403:401);
        }
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isZero();assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
    }

    @Test void unverifiedContactCandidateCannotAuthorizeLinkAndPendingCannotUseReplacedProof()throws Exception{
        googleLogin(browser);assertError(start(browser,true),409,"REAUTH_EMAIL_REQUIRED");candidate(browser);assertError(start(browser,true),409,"REAUTH_EMAIL_REQUIRED");
        // Complete the existing initial registration through the normal OTP endpoint.
        var contact=JSON.readTree(get(browser,REAUTH+"contact").body());var id=UUID.fromString(contact.get("candidate").get("id").asString());
        var message=issue(browser,REAUTH+"registration/request",Map.of("candidateId",id));assertThat(post(browser,REAUTH+"registration/verify",Map.of("candidateId",id,"challengeId",message.issuanceId(),"code",message.code())).statusCode()).isEqualTo(204);
        provider.subject="new-sub";var flow=begin(browser);var old=proof(browser);CLOCK.advance(Duration.ofSeconds(1));
        setProof(browser,new EmailOwnershipProof(old.userId(),old.sessionId(),old.contactId(),old.emailHash(),CLOCK.instant(),old.source()));
        assertError(callback(browser,flow),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isEqualTo(1);
    }

    private UUID googleIdentity(Browser current){return jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=? AND provider='google'",UUID.class,metadata(current).userId());}
    private HttpResponse<String> start(Browser current,boolean csrf)throws Exception{
        var request=HttpRequest.newBuilder(URI.create(base+LINK)).header("Content-Type","application/x-www-form-urlencoded");if(csrf)request.header("X-CSRF-TOKEN",csrf(current));
        return current.client.send(request.POST(HttpRequest.BodyPublishers.ofString("")).build(),HttpResponse.BodyHandlers.ofString());
    }
    private URI begin(Browser current)throws Exception{return flow(start(current,true));}
    private void assertSuccess(HttpResponse<String> r){assertThat(r.statusCode()).isEqualTo(302);assertThat(r.headers().firstValue("Location").orElse("")).isEqualTo("/api/v1/auth/identities");}
    private void assertFailure(HttpResponse<String> r){assertThat(r.statusCode()).isEqualTo(302);assertThat(r.headers().firstValue("Location").orElse("")).isEqualTo("/auth/google/link/failure");}
    private void assertNoTokens(){
        var values=jdbc.queryForList("SELECT attribute_bytes FROM spring_session_attributes",byte[].class);
        for(var value:values){var bytes=new String(value,java.nio.charset.StandardCharsets.ISO_8859_1);assertThat(bytes).doesNotContain("OidcUser","OidcIdToken","OAuth2AccessToken","OAuth2AuthorizedClient");}
    }
    private Map<String,Object> business(){return Map.of("users",jdbc.queryForList("SELECT * FROM users ORDER BY id"),"exercises",jdbc.queryForList("SELECT * FROM exercise_definitions ORDER BY id"),"settings",jdbc.queryForList("SELECT * FROM user_workout_settings ORDER BY user_id"));}
    private void dropProof(Browser current){setProof(current,null);}
    private static void setProof(Browser current,EmailOwnershipProof value){SessionRepository repository=app.getBean(SessionRepository.class);Session s=(Session)repository.findById(current.cookie());if(value==null)s.removeAttribute(EmailOwnershipProof.ATTRIBUTE);else s.setAttribute(EmailOwnershipProof.ATTRIBUTE,value);repository.save(s);}
    private static EmailOwnershipProof proof(Browser current){Session s=(Session)app.getBean(SessionRepository.class).findById(current.cookie());return s.getAttribute(EmailOwnershipProof.ATTRIBUTE);}
    private static SessionMetadata metadata(Browser current){Session s=(Session)app.getBean(SessionRepository.class).findById(current.cookie());assertThat(s!=null).isTrue();return s.getAttribute(SessionMetadata.ATTRIBUTE);}
    private UUID emailIdentity(Browser current){return jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=? AND provider='email'",UUID.class,metadata(current).userId());}
    private long count(String query){return jdbc.queryForObject(query,Long.class);}
    private void emailLogin(Browser current,String email)throws Exception{var message=issue(current,"/api/v1/auth/email-otp/request",Map.of("email",email));assertThat(post(current,"/api/v1/auth/email-otp/verify",Map.of("email",email,"challengeId",message.issuanceId(),"code",message.code())).statusCode()).isEqualTo(204);}
    private void googleLogin(Browser current)throws Exception{assertThat(callback(current,flow(get(current,"/oauth2/authorization/google"))).statusCode()).isEqualTo(302);assertThat(get(current,"/api/v1/auth/session").statusCode()).isEqualTo(200);}
    private UUID candidate(Browser current)throws Exception{
        var id=jdbc.queryForObject("SELECT id FROM auth_identities WHERE user_id=? AND provider='google'",UUID.class,metadata(current).userId());
        var request=HttpRequest.newBuilder(URI.create(base+"/oauth2/contact/google")).header("Content-Type","application/x-www-form-urlencoded").header("X-CSRF-TOKEN",csrf(current)).POST(HttpRequest.BodyPublishers.ofString("identityId="+id)).build();
        assertThat(callback(current,flow(current.client.send(request,HttpResponse.BodyHandlers.ofString()))).statusCode()).isEqualTo(302);
        var contact=JSON.readTree(get(current,REAUTH+"contact").body());return UUID.fromString(contact.get("candidate").get("id").asString());
    }
    private void enroll(Browser current)throws Exception{
        var candidate=candidate(current);
        var m=issue(current,REAUTH+"registration/request",Map.of("candidateId",candidate));assertThat(post(current,REAUTH+"registration/verify",Map.of("candidateId",candidate,"challengeId",m.issuanceId(),"code",m.code())).statusCode()).isEqualTo(204);
    }
    private void reauth(Browser current)throws Exception{var m=issue(current,REAUTH+"request",Map.of());assertThat(post(current,REAUTH+"verify",Map.of("challengeId",m.issuanceId(),"code",m.code())).statusCode()).isEqualTo(204);}
    private OtpEmailSender.Message issue(Browser current,String path,Object body)throws Exception{var r=post(current,path,body);assertThat(r.statusCode()).isEqualTo(202);return sender.messages.get(UUID.fromString(JSON.readTree(r.body()).get("challengeId").asString()));}
    private String csrf(Browser current)throws Exception{return JSON.readTree(get(current,"/api/v1/auth/csrf").body()).get("token").asString();}
    private URI flow(HttpResponse<String> r)throws Exception{assertThat(r.statusCode()).isEqualTo(302);var uri=URI.create(r.headers().firstValue("Location").orElseThrow());assertThat(uri.getHost()).isEqualTo("127.0.0.1");try(var client=HttpClient.newHttpClient()){return URI.create(client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofString()).headers().firstValue("Location").orElseThrow());}}
    private HttpResponse<String> callback(Browser current,URI uri)throws Exception{return current.client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> get(Browser current,String path)throws Exception{return callback(current,URI.create(base+path));}
    private HttpResponse<String> post(Browser current,String path,Object body)throws Exception{return postWithToken(current,path,body,csrf(current));}
    private HttpResponse<String> postWithToken(Browser current,String path,Object body,String csrf)throws Exception{var request=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json");if(csrf!=null)request.header("X-CSRF-TOKEN",csrf);return current.client.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    private void assertError(HttpResponse<String> r,int status,String code)throws Exception{assertThat(r.statusCode()).isEqualTo(status);assertThat(JSON.readTree(r.body()).get("code").asString()).isEqualTo(code);}
    static class Browser implements AutoCloseable {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        Browser(){} Browser(String id){var cookie=new HttpCookie("SESSION",id);cookie.setPath("/");cookie.setVersion(0);cookies.getCookieStore().add(URI.create(base),cookie);}
        String cookie(){return cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("SESSION")).findFirst().orElseThrow().getValue();}
        public void close(){client.close();}
    }
}
