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
class EmailIdentityIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_email_identity_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString()).withLabel("overload.task","email-identity-3b-3a");
    static final JsonMapper JSON=JsonMapper.builder().build();
    static ConfigurableApplicationContext app;
    static GoogleOidcFixtures.MockProvider provider;
    static JdbcTemplate jdbc;
    static EmailOtpFixtures.FakeSender sender;
    static String base,key;
    static final String LINK="/api/v1/auth/identities/email/link",UNLINK="/api/v1/auth/identities/email/unlink",REAUTH="/api/v1/auth/reauth/email/";
    Browser browser;
    @BeforeAll static void start() throws Exception {
        provider=new GoogleOidcFixtures.MockProvider();GoogleOidcFixtures.provider=provider;CLOCK.reset();
        byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);key=Base64.getEncoder().encodeToString(secret);boot(true);
    }
    static void boot(boolean google){
        app=new SpringApplicationBuilder(OverloadServerApplication.class,EmailOtpFixtures.class,GoogleOidcFixtures.class,EmailIdentityFixtures.class)
                .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration","--spring.datasource.url="+POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),"--GOOGLE_OIDC_ENABLED="+google,"--AUTH_FRONTEND_RETURN_URI=","--AUTH_LINK_FRONTEND_RETURN_URI=", "--AUTH_CONTACT_FRONTEND_RETURN_URI=",
                        "--GOOGLE_REAUTH_ENABLED=false","--OTP_EMAIL_ENABLED=false","--OTP_HMAC_KEY="+key,"--GOOGLE_CLIENT_ID="+provider.clientId,
                        "--GOOGLE_CLIENT_SECRET="+provider.clientSecret,"--server.address=127.0.0.1","--server.port=0","--spring.session.jdbc.cleanup-cron=-",
                        "--logging.level.root=WARN","--spring.main.banner-mode=off");
        jdbc=app.getBean(JdbcTemplate.class);sender=app.getBean(EmailOtpFixtures.FakeSender.class);
        com.overload.testfixture.MixedPersistenceAssertions.verify(app);
        base="http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort();
    }
    @BeforeEach void isolate(){
        CLOCK.reset();provider.reset();sender.reset();ReauthenticationFixtures.reset();GoogleOidcFixtures.onSessionRead=null;GoogleOidcFixtures.corruptMetadataRead=false;
        jdbc.execute("DROP TRIGGER IF EXISTS identity_fail ON auth_identities");
        jdbc.execute("DROP TRIGGER IF EXISTS proof_fail ON spring_session_attributes");
        jdbc.update("DELETE FROM spring_session");jdbc.update("DELETE FROM email_otp_login_claims");jdbc.update("UPDATE email_otp_limits SET active_challenge_id=NULL");
        for(var table:List.of("email_otp_challenges","email_otp_limits","email_otp_rate_limits","reauth_email_candidates","user_reauthentication_emails","deload_weekly_assessments", "deload_analysis_versions", "exercise_definitions", "user_workout_settings", "auth_identities", "users")){jdbc.update("DELETE FROM "+table);}
        browser=new Browser();
    }
    @AfterEach void close(){ReauthenticationFixtures.reset();GoogleOidcFixtures.onSessionRead=null;browser.close();}
    @AfterAll static void stop(){if(app!=null)app.close();if(provider!=null)provider.close();}

    @Test void explicitLinkUsesContactEnrollmentProofAndEmailLoginReusesTheGoogleUserWithoutReseeding() throws Exception {
        googleLogin(browser);enroll(browser);var before=metadata(browser);var data=business();int sent=sender.messages.size();
        assertThat(proof(browser).source()).isEqualTo(EmailOwnershipProof.Source.CONTACT_ENROLL);
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='email'")).isZero();
        assertThat(post(browser,LINK,Map.of()).statusCode()).isEqualTo(201);
        assertThat(post(browser,LINK,Map.of()).statusCode()).isEqualTo(200);assertThat(sender.messages.size()).isEqualTo(sent);
        assertThat(metadata(browser)).isEqualTo(before);assertThat(business()).isEqualTo(data);
        try(var email=new Browser()){emailLogin(email,provider.email);assertThat(metadata(email).userId().equals(before.userId())).isTrue();assertThat(proof(email).source()).isEqualTo(EmailOwnershipProof.Source.EMAIL_LOGIN);}
        assertThat(business()).isEqualTo(data);assertThat(count("SELECT count(*) FROM users")).isEqualTo(1);
    }

    @Test void emailOnlyCannotUnlinkItsLastLoginAndAnEmailLoginProofIsAccepted() throws Exception {
        emailLogin(browser,"email-only@example.test");var before=metadata(browser);var data=business();var id=emailIdentity(browser);
        assertThat(post(browser,LINK,Map.of()).statusCode()).isEqualTo(200);
        assertError(post(browser,UNLINK,Map.of("identityId",id)),409,"LAST_LOGIN_IDENTITY");
        assertThat(metadata(browser)).isEqualTo(before);assertThat(business()).isEqualTo(data);assertThat(emailIdentity(browser)).isEqualTo(id);
    }

    @Test void unlinkKeepsContactDataAndSessionButLaterEmailLoginCannotAccessTheOldGoogleUser() throws Exception {
        googleLogin(browser);enroll(browser);post(browser,LINK,Map.of());var owner=metadata(browser).userId();var id=emailIdentity(browser);var before=metadata(browser);var data=business();
        assertThat(post(browser,UNLINK,Map.of("identityId",id)).statusCode()).isEqualTo(204);
        assertError(post(browser,UNLINK,Map.of("identityId",id)),404,"IDENTITY_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT status FROM user_reauthentication_emails WHERE user_id=?",String.class,owner)).isEqualTo("VERIFIED");
        assertThat(metadata(browser)).isEqualTo(before);assertThat(business()).isEqualTo(data);
        try(var fresh=new Browser()){emailLogin(fresh,provider.email);assertThat(metadata(fresh).userId().equals(owner)).isFalse();}
        assertError(post(browser,LINK,Map.of()),409,"IDENTITY_ALREADY_OWNED");assertThat(metadata(browser).userId().equals(owner)).isTrue();
    }

    @Test void sameEmailContactDoesNotMergeTheExistingIndependentEmailAndGoogleAccounts() throws Exception {
        try(var email=new Browser()){
            emailLogin(email,provider.email);var emailOwner=metadata(email).userId();googleLogin(browser);enroll(browser);var googleOwner=metadata(browser).userId();var data=business();
            assertThat(googleOwner.equals(emailOwner)).isFalse();assertError(post(browser,LINK,Map.of()),409,"IDENTITY_ALREADY_OWNED");
            assertThat(business()).isEqualTo(data);assertThat(count("SELECT count(*) FROM users")).isEqualTo(2);
            assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isEqualTo(2);
            assertError(post(browser,UNLINK,Map.of("identityId",emailIdentity(email))),404,"IDENTITY_NOT_FOUND");
        }
    }

    @Test void googleSsoTimestampAndLegacySessionWithoutProofCannotAuthorizeWrites() throws Exception {
        googleLogin(browser);assertError(post(browser,LINK,Map.of()),409,"REAUTH_EMAIL_REQUIRED");
        enroll(browser);dropProof(browser);assertThat(metadata(browser).reauthenticatedAt()).isEqualTo(CLOCK.instant());
        assertError(post(browser,LINK,Map.of()),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
        reauth(browser);assertThat(proof(browser).source()).isEqualTo(EmailOwnershipProof.Source.EMAIL_REAUTH);
        assertThat(post(browser,LINK,Map.of()).statusCode()).isEqualTo(201);
        dropProof(browser);assertError(post(browser,UNLINK,Map.of("identityId",emailIdentity(browser))),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
    }

    @Test void unverifiedGoogleEmailCandidateIsNeitherAContactNorAnEmailLogin() throws Exception {
        googleLogin(browser);candidate(browser);int sent=sender.messages.size();
        assertError(post(browser,LINK,Map.of()),409,"REAUTH_EMAIL_REQUIRED");
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='email'")).isZero();assertThat(sender.messages.size()).isEqualTo(sent);
    }

    @Test void loginThroughALegacyEmailDifferentFromTheRepresentativeDoesNotMintContactProof() throws Exception {
        String first="representative@example.test",second="legacy-login@example.test";emailLogin(browser,first);var user=metadata(browser).userId();
        jdbc.update("INSERT INTO auth_identities(id,user_id,provider,issuer,subject) VALUES (?,?,'email','overload:email',?)",UUID.randomUUID(),user,second);
        try(var other=new Browser()){
            emailLogin(other,second);assertThat(metadata(other).userId().equals(user)).isTrue();assertThat(proof(other)).isNull();
            assertError(post(other,LINK,Map.of()),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
        }
        assertThat(jdbc.queryForObject("SELECT email FROM user_reauthentication_emails WHERE user_id=?",String.class,user).equals(first)).isTrue();
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void unlinkArchivesLegacyV5OtpReferencesWithoutLosingTheConsumedHistory(boolean failFirst) throws Exception {
        googleLogin(browser);enroll(browser);post(browser,LINK,Map.of());var user=metadata(browser).userId();var identity=emailIdentity(browser);var challenge=UUID.randomUUID();var guard="e".repeat(64);
        jdbc.update("INSERT INTO email_otp_limits(email_key,purpose,failure_window_end) VALUES (?,'REAUTH',CURRENT_TIMESTAMP+INTERVAL '30 minutes')",guard);
        jdbc.update("""
                INSERT INTO email_otp_challenges(id,email_key,purpose,recipient,binding_hash,verifier,state,issued_at,expires_at,accepted_at,consumed_at,reauth_user_id,reauth_identity_id)
                VALUES (?,?,'REAUTH',?,?,?,'CONSUMED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '5 minutes',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,?,?)
                """,challenge,guard,provider.email,guard,new byte[32],user,identity);
        var history=jdbc.queryForMap("SELECT state,encode(verifier,'hex') AS verifier,consumed_at,recipient,reauth_user_id FROM email_otp_challenges WHERE id=?",challenge);
        if(failFirst){
            jdbc.execute("CREATE OR REPLACE FUNCTION identity_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated identity rollback fault' USING ERRCODE='23514'; END $$");
            jdbc.execute("CREATE TRIGGER identity_fail BEFORE DELETE ON auth_identities FOR EACH ROW EXECUTE FUNCTION identity_fail()");
            assertThat(post(browser,UNLINK,Map.of("identityId",identity)).statusCode()).isEqualTo(503);
            assertThat(jdbc.queryForObject("SELECT reauth_identity_id FROM email_otp_challenges WHERE id=?",UUID.class,challenge)).isEqualTo(identity);
            assertThat(jdbc.queryForObject("SELECT retired_reauth_identity_id FROM email_otp_challenges WHERE id=?",UUID.class,challenge)).isNull();
            jdbc.execute("DROP TRIGGER identity_fail ON auth_identities");
        }
        assertThat(post(browser,UNLINK,Map.of("identityId",identity)).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForMap("SELECT state,encode(verifier,'hex') AS verifier,consumed_at,recipient,reauth_user_id FROM email_otp_challenges WHERE id=?",challenge)).isEqualTo(history);
        assertThat(jdbc.queryForObject("SELECT retired_reauth_identity_id FROM email_otp_challenges WHERE id=?",UUID.class,challenge)).isEqualTo(identity);
        assertThat(jdbc.queryForObject("SELECT reauth_identity_id FROM email_otp_challenges WHERE id=?",UUID.class,challenge)).isNull();
    }

    @ParameterizedTest @ValueSource(strings={"LOGIN","REAUTH","CONTACT_ENROLL"})
    void silentlySuppressedProofWritesCannotReportAuthenticationSuccess(String source) throws Exception {
        if(!source.equals("LOGIN")){googleLogin(browser);if(source.equals("REAUTH")){enroll(browser);dropProof(browser);}}
        UUID candidate=source.equals("CONTACT_ENROLL")?candidate(browser):null;
        var message=source.equals("LOGIN")?issue(browser,"/api/v1/auth/email-otp/request",Map.of("email",provider.email))
                :issue(browser,source.equals("REAUTH")?REAUTH+"request":REAUTH+"registration/request",candidate==null?Map.of():Map.of("candidateId",candidate));
        var before=source.equals("LOGIN")?null:metadata(browser);
        jdbc.execute("CREATE OR REPLACE FUNCTION proof_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.attribute_name='com.overload.overload_server.security.EmailOwnershipProof' THEN RETURN NULL; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER proof_fail BEFORE INSERT ON spring_session_attributes FOR EACH ROW EXECUTE FUNCTION proof_fail()");
        var body=source.equals("LOGIN")?Map.of("email",provider.email,"challengeId",message.issuanceId(),"code",message.code())
                :source.equals("REAUTH")?Map.of("challengeId",message.issuanceId(),"code",message.code())
                :Map.of("candidateId",candidate,"challengeId",message.issuanceId(),"code",message.code());
        var path=source.equals("LOGIN")?"/api/v1/auth/email-otp/verify":source.equals("REAUTH")?REAUTH+"verify":REAUTH+"registration/verify";
        assertThat(post(browser,path,body).statusCode()).isIn(401,503);
        if(before!=null){assertThat(metadata(browser)).isEqualTo(before);assertThat(jdbc.queryForObject("SELECT state FROM email_otp_challenges WHERE id=?",String.class,message.issuanceId())).isEqualTo("ACTIVE");}
        if(source.equals("CONTACT_ENROLL")){assertThat(count("SELECT count(*) FROM user_reauthentication_emails")).isZero();}
        jdbc.execute("DROP TRIGGER proof_fail ON spring_session_attributes");
    }

    @ParameterizedTest @ValueSource(longs={600,601})
    void emailProofTenMinuteBoundaryIsIndependentOfGoogleTimestamp(long seconds) throws Exception {
        googleLogin(browser);enroll(browser);var at=proof(browser).verifiedAt();CLOCK.advance(Duration.ofSeconds(seconds));
        assertThat(post(browser,LINK,Map.of()).statusCode()).isEqualTo(seconds==600?201:403);
        assertThat(proof(browser).verifiedAt()).isEqualTo(at);
    }

    @ParameterizedTest @ValueSource(strings={"rotate","base-rotate"})
    void nonEmailRotationCarriesOnlyTheExistingProofAndNeverRefreshesItsTime(String path) throws Exception {
        googleLogin(browser);enroll(browser);var old=browser.cookie();var original=metadata(browser);var evidence=proof(browser);
        CLOCK.advance(Duration.ofMinutes(9));assertThat(post(browser,"/__email_identity_test__/"+path,Map.of()).statusCode()).isEqualTo(204);
        assertThat(browser.cookie().equals(old)).isFalse();assertThat(proof(browser).verifiedAt()).isEqualTo(evidence.verifiedAt());
        assertThat(proof(browser).sessionId().equals(browser.cookie())).isTrue();assertThat(metadata(browser).absoluteExpiresAt()).isEqualTo(original.absoluteExpiresAt());
        assertThat(post(browser,LINK,Map.of()).statusCode()).isEqualTo(201);
        CLOCK.advance(Duration.ofMinutes(1).plusNanos(1));assertError(post(browser,UNLINK,Map.of("identityId",emailIdentity(browser))),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
        try(var stale=new Browser(old)){assertThat(post(stale,LINK,Map.of()).statusCode()).isEqualTo(401);}
    }

    @Test void expiredProofIsDroppedDuringGenericRotationRatherThanRenewed() throws Exception {
        googleLogin(browser);enroll(browser);CLOCK.advance(Duration.ofMinutes(11));
        assertThat(post(browser,"/__email_identity_test__/rotate",Map.of()).statusCode()).isEqualTo(204);assertThat(proof(browser)).isNull();
        assertError(post(browser,LINK,Map.of()),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
    }

    @Test void proofCannotMoveToAnotherBrowserOrAnotherUserEvenIfCopiedInTheStore() throws Exception {
        googleLogin(browser);enroll(browser);var stolen=proof(browser);
        try(var same=new Browser();var other=new Browser()){
            googleLogin(same);setProof(same,stolen);assertError(post(same,LINK,Map.of()),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
            provider.subject="another-user";googleLogin(other);enroll(other);setProof(other,stolen);assertError(post(other,LINK,Map.of()),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
        }
    }

    @ParameterizedTest @ValueSource(strings={"future","wrong-contact","wrong-address","unknown-source"})
    void malformedOrMismatchedProofFailsClosed(String fault) throws Exception {
        googleLogin(browser);enroll(browser);var p=proof(browser);
        var changed=new EmailOwnershipProof(p.userId(),p.sessionId(),fault.equals("wrong-contact")?UUID.randomUUID():p.contactId(),
                fault.equals("wrong-address")?"0".repeat(64):p.emailHash(),fault.equals("future")?CLOCK.instant().plusSeconds(1):p.verifiedAt(),fault.equals("unknown-source")?null:p.source());
        setProof(browser,changed);assertError(post(browser,LINK,Map.of()),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
    }

    @Test void logoutRevokesTheSessionAndANewGoogleLoginDoesNotRecoverOldEmailProof() throws Exception {
        googleLogin(browser);enroll(browser);post(browser,LINK,Map.of());var identity=emailIdentity(browser);var old=browser.cookie();assertThat(post(browser,"/api/v1/auth/logout",Map.of()).statusCode()).isEqualTo(204);
        try(var stale=new Browser(old)){assertThat(post(stale,LINK,Map.of()).statusCode()).isEqualTo(401);}
        googleLogin(browser);assertThat(proof(browser)).isNull();assertError(post(browser,LINK,Map.of()),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
        assertError(post(browser,UNLINK,Map.of("identityId",identity)),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
    }

    @Test void restartRestoresSerializedEmailProofAndOldMetadataOnlySessionsStillRequireReauthentication() throws Exception {
        emailLogin(browser,"restart@example.test");var evidence=proof(browser);var before=metadata(browser);app.close();boot(true);
        assertThat(metadata(browser)).isEqualTo(before);assertThat(proof(browser)).isEqualTo(evidence);assertThat(post(browser,LINK,Map.of()).statusCode()).isEqualTo(200);
        dropProof(browser);app.close();boot(true);assertThat(get(browser,"/api/v1/auth/session").statusCode()).isEqualTo(200);
        assertError(post(browser,LINK,Map.of()),403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");
    }

    @Test void disabledGoogleLoginDoesNotCountAsAnAvailableLastAlternative() throws Exception {
        googleLogin(browser);enroll(browser);post(browser,LINK,Map.of());var id=emailIdentity(browser);app.close();boot(false);
        try{assertError(post(browser,UNLINK,Map.of("identityId",id)),409,"LAST_LOGIN_IDENTITY");}finally{app.close();boot(true);}
    }

    @Test void addressAndOwnerInputsAreRejectedAndAuthAndCsrfAreMandatory() throws Exception {
        assertThat(post(browser,LINK,Map.of()).statusCode()).isEqualTo(401);googleLogin(browser);enroll(browser);
        assertThat(postWithToken(browser,LINK,Map.of(),null).statusCode()).isEqualTo(403);
        for(var path:List.of(LINK,UNLINK)){
            assertThat(post(browser,path,Map.of("email","arbitrary@example.test")).statusCode()).isEqualTo(400);
            assertThat(post(browser,path+"?userId="+UUID.randomUUID(),Map.of()).statusCode()).isEqualTo(400);
        }
        assertThat(post(browser,UNLINK,Map.of()).statusCode()).isEqualTo(400);
    }

    @Test void simultaneousDuplicateLinksCreateOneIdentityAndSendNoExtraMail() throws Exception {
        googleLogin(browser);enroll(browser);int sent=sender.messages.size();var csrf=csrf(browser);var sid=browser.cookie();
        try(var one=new Browser(sid);var two=new Browser(sid);var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->postWithToken(one,LINK,Map.of(),csrf).statusCode());var b=executor.submit(()->postWithToken(two,LINK,Map.of(),csrf).statusCode());
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,200);
        }
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='email'")).isEqualTo(1);assertThat(sender.messages.size()).isEqualTo(sent);
    }

    @Test void differentOwnersRacingForSameAddressHaveExactlyOneWinner() throws Exception {
        googleLogin(browser);enroll(browser);
        try(var other=new Browser();var executor=Executors.newFixedThreadPool(2)){
            provider.subject="second-owner";googleLogin(other);enroll(other);var firstCsrf=csrf(browser);var secondCsrf=csrf(other);
            var a=executor.submit(()->postWithToken(browser,LINK,Map.of(),firstCsrf).statusCode());var b=executor.submit(()->postWithToken(other,LINK,Map.of(),secondCsrf).statusCode());
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
            assertThat(count("SELECT count(*) FROM users")).isEqualTo(2);assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='email'")).isEqualTo(1);
        }
    }

    @Test void linkAndUnlinkSerializeWithoutRemovingTheOtherLoginOrChangingData() throws Exception {
        googleLogin(browser);enroll(browser);post(browser,LINK,Map.of());var id=emailIdentity(browser);var data=business();var before=metadata(browser);var csrf=csrf(browser);
        try(var one=new Browser(browser.cookie());var two=new Browser(browser.cookie());var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->postWithToken(one,LINK,Map.of(),csrf).statusCode());var b=executor.submit(()->postWithToken(two,UNLINK,Map.of("identityId",id),csrf).statusCode());
            assertThat(a.get(20,TimeUnit.SECONDS)).isIn(200,201);assertThat(b.get(20,TimeUnit.SECONDS)).isEqualTo(204);
        }
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isEqualTo(1);assertThat(business()).isEqualTo(data);assertThat(metadata(browser)).isEqualTo(before);
    }

    @Test void simultaneousUnlinksDeleteOnlyTheOwnedEmailOnce() throws Exception {
        googleLogin(browser);enroll(browser);post(browser,LINK,Map.of());var id=emailIdentity(browser);var csrf=csrf(browser);
        try(var one=new Browser(browser.cookie());var two=new Browser(browser.cookie());var executor=Executors.newFixedThreadPool(2)){
            var a=executor.submit(()->postWithToken(one,UNLINK,Map.of("identityId",id),csrf).statusCode());
            var b=executor.submit(()->postWithToken(two,UNLINK,Map.of("identityId",id),csrf).statusCode());
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(204,404);
        }
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='google'")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"INSERT","DELETE"})
    void identityWriteErrorsRollbackAndPreserveSessionAndData(String operation) throws Exception {
        googleLogin(browser);enroll(browser);if(operation.equals("DELETE")){post(browser,LINK,Map.of());}
        var before=metadata(browser);var evidence=proof(browser);var data=business();var identities=jdbc.queryForList("SELECT * FROM auth_identities ORDER BY id");
        jdbc.execute("CREATE OR REPLACE FUNCTION identity_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'isolated identity fault' USING ERRCODE='23514'; END $$");
        jdbc.execute("CREATE TRIGGER identity_fail BEFORE "+operation+" ON auth_identities FOR EACH ROW EXECUTE FUNCTION identity_fail()");
        var result=operation.equals("INSERT")?post(browser,LINK,Map.of()):post(browser,UNLINK,Map.of("identityId",emailIdentity(browser)));
        assertThat(result.statusCode()).isEqualTo(503);assertThat(metadata(browser)).isEqualTo(before);assertThat(proof(browser)).isEqualTo(evidence);
        assertThat(business()).isEqualTo(data);assertThat(jdbc.queryForList("SELECT * FROM auth_identities ORDER BY id")).isEqualTo(identities);
        jdbc.execute("DROP TRIGGER identity_fail ON auth_identities");
        if(operation.equals("INSERT")){assertThat(post(browser,LINK,Map.of()).statusCode()).isEqualTo(201);}
        else{assertThat(post(browser,UNLINK,Map.of("identityId",emailIdentity(browser))).statusCode()).isEqualTo(204);}
    }

    @ParameterizedTest @ValueSource(strings={"email-proof","absolute"})
    void expiryDuringWriteRollsBackTheIdentityAtTheCommitBoundary(String boundary) throws Exception {
        googleLogin(browser);enroll(browser);var evidence=proof(browser);
        jdbc.execute("CREATE OR REPLACE FUNCTION identity_delay() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_advisory_xact_lock(730031); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER identity_fail BEFORE INSERT ON auth_identities FOR EACH ROW EXECUTE FUNCTION identity_delay()");
        var csrf=csrf(browser);
        try(var connection=jdbc.getDataSource().getConnection();var executor=Executors.newSingleThreadExecutor()){
            var gate=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
            gate.execute("SELECT pg_advisory_lock(730031)");
            var request=executor.submit(()->postWithToken(browser,LINK,Map.of(),csrf));
            try{
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")==0&&System.nanoTime()<deadline){Thread.sleep(10);}
                assertThat(count("SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted")).isPositive();
                CLOCK.advance(boundary.equals("absolute")?Duration.ofDays(90):Duration.ofMinutes(10).plusNanos(1));
            }finally{gate.execute("SELECT pg_advisory_unlock(730031)");}
            assertThat(request.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(boundary.equals("absolute")?401:403);
        }
        assertThat(count("SELECT count(*) FROM auth_identities WHERE provider='email'")).isZero();assertThat(proof(browser)).isEqualTo(evidence);
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
