package com.overload.overload_server.deload;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.account.*;
import com.overload.testfixture.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres") @Testcontainers
class DeloadAnalysisIntegrationTests {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String PATH="/api/v1/deload-analysis";
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_g4_settings_isolated").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext app;static JdbcTemplate jdbc;
    Browser browser;UUID owner;DeloadAnalysisService service;
    @BeforeAll static void start() {
        assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432,5433,12296);
        assertThat(POSTGRES.getDatabaseName()).isEqualTo("overload_g4_settings_isolated");
        var key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        app=new SpringApplicationBuilder(OverloadServerApplication.class,EmailOtpFixtures.class)
            .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
                "--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
                "--server.address=127.0.0.1","--server.port=0","--OTP_HMAC_KEY="+Base64.getEncoder().encodeToString(key),"--OTP_EMAIL_ENABLED=false",
                "--GOOGLE_OIDC_ENABLED=false","--GOOGLE_REAUTH_ENABLED=false","--spring.session.jdbc.cleanup-cron=-","--logging.level.root=WARN","--spring.main.banner-mode=off");
        jdbc=app.getBean(JdbcTemplate.class);MixedPersistenceAssertions.verify(app);
    }
    @BeforeEach void login() throws Exception {
        EmailOtpFixtures.CLOCK.reset();app.getBean(EmailOtpFixtures.FakeSender.class).reset();jdbc.update("DELETE FROM email_otp_rate_limits");
        browser=new Browser();browser.login();owner=UUID.fromString(JSON.readTree(browser.get("/__otp_test__/me").body()).get("id").asString());
        service=app.getBean(DeloadAnalysisService.class);
    }
    @AfterEach void close(){if(browser!=null)browser.close();}
    @AfterAll static void stop(){if(app!=null)app.close();}
    JsonNode node(HttpResponse<String> response)throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");return JSON.readTree(response.body());}
    HttpResponse<String> put(String version,boolean enabled,String zone)throws Exception{return browser.send("PUT",PATH+"/settings",JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion",version,"enabled",enabled,"timeZone",zone)),browser.csrf());}
    long count(){return jdbc.queryForObject("SELECT count(*) FROM deload_analysis_versions WHERE user_id=?",Long.class,owner);}
    @Test void defaultsAreAtomicAndReadsNeverPublishCalculatedZeroOrWrite()throws Exception {
        var before=jdbc.queryForList("SELECT * FROM deload_analysis_versions WHERE user_id=?",owner);
        var value=node(browser.get(PATH));assertThat(value.get("enabled").asBoolean()).isTrue();assertThat(value.get("version").asString()).isEqualTo("0");assertThat(value.get("epoch").asString()).isEqualTo("0");
        assertThat(value.get("timeZone").isNull()).isTrue();assertThat(value.get("calculationStatus").asString()).isEqualTo("AWAITING_TIME_ZONE");
        assertThat(value.get("epochInitialWeeks").get("numerator").asString()).isEqualTo("0");assertThat(value.get("confirmedWeeks").isNull()).isTrue();
        assertThat(Instant.parse(value.get("epochStartedAt").asString())).isEqualTo(jdbc.queryForObject("SELECT created_at FROM users WHERE id=?",java.sql.Timestamp.class,owner).toInstant());
        node(browser.get(PATH));assertThat(jdbc.queryForList("SELECT * FROM deload_analysis_versions WHERE user_id=?",owner)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_weekly_assessments WHERE user_id=?",Long.class,owner)).isZero();
    }
    @Test void reentryPreservesInitialEpochAndUserSelectedOff()throws Exception {
        node(put("0",false,"Asia/Seoul"));var before=jdbc.queryForList("SELECT * FROM deload_analysis_versions WHERE user_id=?",owner);
        String email=jdbc.queryForObject("SELECT subject FROM auth_identities WHERE user_id=?",String.class,owner);
        assertThat(app.getBean(AccountProvisioningService.class).provision(VerifiedIdentity.email(email),"ignored").id()).isEqualTo(owner);
        assertThat(jdbc.queryForList("SELECT * FROM deload_analysis_versions WHERE user_id=?",owner)).isEqualTo(before);
    }
    @Test void zoneBindingOffReactivationNoopAndLostAcknowledgement()throws Exception {
        var initial=node(browser.get(PATH));var bound=node(put("0",true,"Asia/Seoul"));
        assertThat(bound.get("epochStartedAt")).isEqualTo(initial.get("epochStartedAt"));assertThat(bound.get("epoch").asString()).isEqualTo("0");assertThat(bound.get("calculationStatus").asString()).isEqualTo("NOT_IMPLEMENTED");
        var off=node(put("1",false,"Asia/Seoul"));assertThat(off.get("epoch").asString()).isEqualTo("0");assertThat(off.get("calculationStatus").asString()).isEqualTo("DISABLED");
        var on=node(put("2",true,"Asia/Seoul"));assertThat(on.get("epoch").asString()).isEqualTo("3");
        var row=jdbc.queryForMap("SELECT * FROM deload_analysis_versions WHERE user_id=? AND version=3",owner);
        assertThat(row.get("epoch_started_at")).isEqualTo(row.get("recorded_at"));assertThat(row.get("reason")).isEqualTo("ENABLED");
        assertThat(node(put("3",true,"Asia/Seoul"))).isEqualTo(on);assertThat(count()).isEqualTo(4);
        error(put("2",true,"Asia/Seoul"),409,"DELOAD_VERSION_CONFLICT");assertThat(count()).isEqualTo(4);
    }
    @Test void twoDevicesCannotCreateTwoInitialTimeZonesOrEpochs()throws Exception {
        String token=browser.csrf();var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var tasks=new ArrayList<Future<HttpResponse<String>>>();
            for(String zone:List.of("Asia/Seoul","UTC"))tasks.add(pool.submit(()->{go.await();return browser.send("PUT",PATH+"/settings",JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion","0","enabled",true,"timeZone",zone)),token);}));
            go.countDown();var codes=new ArrayList<Integer>();for(var t:tasks)codes.add(t.get(20,TimeUnit.SECONDS).statusCode());
            assertThat(codes).containsExactlyInAnyOrder(200,409);assertThat(count()).isEqualTo(2);assertThat(node(browser.get(PATH)).get("epoch").asString()).isEqualTo("0");
        }
    }
    @Test void concurrentOffRequestsSerializeAndKeepOneEpoch()throws Exception {
        String token=browser.csrf(),body=JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion","0","enabled",false,"timeZone","UTC"));var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)){
            var a=pool.submit(()->{go.await();return browser.send("PUT",PATH+"/settings",body,token);});var b=pool.submit(()->{go.await();return browser.send("PUT",PATH+"/settings",body,token);});go.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS).statusCode(),b.get(20,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(200,409);
        }
        assertThat(count()).isEqualTo(2);assertThat(node(browser.get(PATH)).get("epoch").asString()).isEqualTo("0");
    }
    @Test void authenticationCsrfGenerationAndOwnerBoundaries()throws Exception {
        try(var anonymous=new Browser()){error(anonymous.get(PATH),401,"AUTHENTICATION_REQUIRED");}
        error(browser.send("PUT",PATH+"/settings","{}",null),403,"CSRF_INVALID");
        error(browser.send("PUT",PATH+"/settings","{\"dataGeneration\":\"1\",\"expectedVersion\":\"0\",\"enabled\":true,\"timeZone\":\"UTC\"}",browser.csrf()),409,"DATA_GENERATION_MISMATCH");
        error(browser.send("PUT",PATH+"/settings","{\"dataGeneration\":\"0\",\"expectedVersion\":\"0\",\"enabled\":true,\"timeZone\":\"UTC\",\"userId\":\""+UUID.randomUUID()+"\"}",browser.csrf()),400,"INVALID_REQUEST");
        assertThat(count()).isEqualTo(1);
    }
    @Test void rejectsInvalidQueriesBodiesZonesAndFixedZoneChanges()throws Exception {
        error(browser.get(PATH+"?userId="+owner),400,"INVALID_REQUEST");error(browser.send("GET",PATH,"{}",null),400,"INVALID_REQUEST");
        error(put("0",true,"bad/zone"),400,"INVALID_REQUEST");node(put("0",true,"Asia/Seoul"));
        error(put("1",false,"UTC"),409,"DELOAD_TIME_ZONE_CONFLICT");assertThat(count()).isEqualTo(2);
    }
    @Test void missingSettingsFailWithoutImplicitAdoption()throws Exception {
        jdbc.update("DELETE FROM deload_analysis_versions WHERE user_id=?",owner);
        var before=jdbc.queryForMap("SELECT * FROM users WHERE id=?",owner);
        error(browser.get(PATH),500,"DELOAD_SETTINGS_MISSING");error(put("0",true,"UTC"),500,"DELOAD_SETTINGS_MISSING");
        assertThat(count()).isZero();assertThat(jdbc.queryForMap("SELECT * FROM users WHERE id=?",owner)).isEqualTo(before);
    }
    UUID legacy() {UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,created_at,updated_at) VALUES (?,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')",id);return id;}
    @Test void explicitAdoptionUsesOnlyFixedBoundaryAndPreservesAnyExistingChoice() {
        UUID id=legacy();var at=Instant.parse("2026-02-01T00:00:00Z");
        assertThat(service.adoptExisting(id,0,at)).isTrue();var first=repository().current(id,0);
        assertThat(first.epochStartedAt()).isEqualTo(at);assertThat(first.reason()).isEqualTo("ADOPTED_INITIAL");
        service.save(id,new DeloadAnalysisRequests.Save(0,0,false,"UTC"));var before=repository().current(id,0);
        assertThat(service.adoptExisting(id,0,at)).isFalse();assertThat(repository().current(id,0)).isEqualTo(before);
        assertThatThrownBy(()->service.adoptExisting(legacy(),0,null)).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(()->service.adoptExisting(legacy(),0,Instant.parse("2025-01-01T00:00:00Z"))).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(()->service.adoptExisting(legacy(),0,Instant.parse("9999-01-01T00:00:00Z"))).hasMessage("INVALID_REQUEST");
    }
    @Test void adoptedScheduleTimeZoneIsReusedWithoutChangingSchedule()throws Exception {
        node(browser.send("PUT","/api/v1/weekly-schedule","{\"contractVersion\":\"immediate-v1\",\"dataGeneration\":\"0\",\"expectedVersion\":\"0\",\"timeZone\":\"Asia/Seoul\",\"assignments\":[]}",browser.csrf()));
        var before=jdbc.queryForList("SELECT * FROM weekly_schedule_versions WHERE user_id=?",owner);
        jdbc.update("DELETE FROM deload_analysis_versions WHERE user_id=?",owner);
        assertThat(service.adoptExisting(owner,0,jdbc.queryForObject("SELECT clock_timestamp()",java.sql.Timestamp.class).toInstant())).isTrue();
        assertThat(repository().current(owner,0).timeZone()).isEqualTo("Asia/Seoul");assertThat(jdbc.queryForList("SELECT * FROM weekly_schedule_versions WHERE user_id=?",owner)).isEqualTo(before);
    }
    @Test void concurrentExplicitAdoptionCreatesOnlyOneInitialEpoch()throws Exception {
        UUID id=legacy();var at=Instant.parse("2026-02-01T00:00:00Z");var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{go.await();return service.adoptExisting(id,0,at);});var b=pool.submit(()->{go.await();return service.adoptExisting(id,0,at);});go.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        } finally {go.countDown();}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_analysis_versions WHERE user_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void scheduleFailureRollsBackTimeZoneBinding()throws Exception {
        var failed=browser.send("PUT","/api/v1/weekly-schedule",JSON.writeValueAsString(Map.of("contractVersion","immediate-v1","dataGeneration","0","expectedVersion","0","timeZone","Asia/Seoul","assignments",List.of(Map.of("dayOfWeek",1,"routineId",UUID.randomUUID())))),browser.csrf());
        assertThat(failed.statusCode()).isEqualTo(404);assertThat(count()).isEqualTo(1);assertThat(repository().current(owner,0).timeZone()).isNull();
    }
    @Test void databaseConstraintsRejectInvalidEpochsHistoryAndWeeklyValues()throws Exception {
        node(put("0",false,"UTC"));
        assertThatThrownBy(()->jdbc.update("UPDATE deload_analysis_versions SET enabled=true WHERE user_id=?",owner)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO deload_analysis_versions SELECT user_id,data_generation,2,true,1,clock_timestamp(),clock_timestamp(),time_zone,'ENABLED' FROM deload_analysis_versions WHERE user_id=? AND version=1",owner)).isInstanceOf(DataIntegrityViolationException.class);
        for(String bad:List.of("'2026-11-03'","'2026-11-02'")){
            String epoch=bad.contains("03")?"0":"1";
            assertThatThrownBy(()->jdbc.update("INSERT INTO deload_weekly_assessments(user_id,data_generation,epoch,week_start,status,rule_version,revision,input_revision,updated_at) VALUES (?,0,"+epoch+","+bad+",'PENDING',1,0,'r',now())",owner)).isInstanceOf(DataIntegrityViolationException.class);
        }
        jdbc.update("INSERT INTO deload_weekly_assessments(user_id,data_generation,epoch,week_start,status,rule_version,revision,input_revision,updated_at) VALUES (?,0,0,'2026-11-02','PENDING',1,0,'r',now())",owner);
        assertThatThrownBy(()->jdbc.update("UPDATE deload_weekly_assessments SET e_denominator=0 WHERE user_id=?",owner)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE deload_weekly_assessments SET epoch=1 WHERE user_id=?",owner)).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE deload_weekly_assessments SET status='CONFIRMED',e_numerator=2,e_denominator=1,n=2,operation='ADD',contribution_numerator=1,contribution_denominator=1,confirmed_at=now(),updated_at=now() WHERE user_id=?",owner);
        assertThatThrownBy(()->jdbc.update("UPDATE deload_weekly_assessments SET contribution_numerator=2 WHERE user_id=?",owner)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE deload_weekly_assessments SET e_numerator=0.5 WHERE user_id=?",owner)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE deload_weekly_assessments SET contribution_numerator='NaN',contribution_denominator='NaN' WHERE user_id=?",owner)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE deload_weekly_assessments SET n=8 WHERE user_id=?",owner)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE deload_weekly_assessments SET data_generation=1 WHERE user_id=?",owner)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO deload_weekly_assessments SELECT * FROM deload_weekly_assessments WHERE user_id=?",owner)).isInstanceOf(DataIntegrityViolationException.class);
    }
    DeloadAnalysisRepository repository(){return app.getBean(DeloadAnalysisRepository.class);}
    void error(HttpResponse<String> response,int status,String code)throws Exception {assertThat(response.statusCode()).as(response.body()).isEqualTo(status);assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo(code);}
    static class Browser implements AutoCloseable {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        void login()throws Exception {
            String token=csrf(),email=UUID.randomUUID()+"@example.test";
            var issued=send("POST","/api/v1/auth/email-otp/request",JSON.writeValueAsString(Map.of("email",email)),token);assertThat(issued.statusCode()).isEqualTo(202);
            var message=app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString()));
            assertThat(send("POST","/api/v1/auth/email-otp/verify",JSON.writeValueAsString(Map.of("email",email,"challengeId",message.issuanceId(),"code",message.code())),token).statusCode()).isEqualTo(204);
        }
        String csrf()throws Exception{return JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString();}
        HttpResponse<String> get(String path)throws Exception{return send("GET",path,null,null);}
        HttpResponse<String> send(String method,String path,String body,String token)throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort()+path)).timeout(Duration.ofSeconds(15));
            if(token!=null)request.header("X-CSRF-TOKEN",token);if(body!=null)request.header("Content-Type","application/json");
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        public void close(){client.close();}
    }
}
