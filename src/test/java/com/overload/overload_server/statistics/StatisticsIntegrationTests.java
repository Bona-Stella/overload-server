package com.overload.overload_server.statistics;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.workoutsession.*;
import com.overload.testfixture.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres") @Testcontainers
class StatisticsIntegrationTests {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String PATH="/api/v1/statistics?fromDate=2026-10-26&toDateExclusive=2026-11-02";
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine")
            .withDatabaseName("overload_statistics_test").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext app; static JdbcTemplate jdbc;
    Browser browser; UUID owner;
    record Seed(UUID session, List<UUID> sets) { }
    @BeforeAll static void start() {
        assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432,5433);
        assertThat(POSTGRES.getDatabaseName()).isEqualTo("overload_statistics_test");
        var key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        app=new SpringApplicationBuilder(OverloadServerApplication.class,EmailOtpFixtures.class,WorkoutReadFixtures.class)
                .web(WebApplicationType.SERVLET).run("--spring.profiles.active=local,integration",
                        "--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
                        "--server.address=127.0.0.1","--server.port=0","--OTP_HMAC_KEY="+Base64.getEncoder().encodeToString(key),"--OTP_EMAIL_ENABLED=false",
                        "--GOOGLE_OIDC_ENABLED=false","--GOOGLE_REAUTH_ENABLED=false","--spring.session.jdbc.cleanup-cron=-","--logging.level.root=WARN","--spring.main.banner-mode=off");
        jdbc=app.getBean(JdbcTemplate.class);
    }
    @BeforeEach void login() throws Exception {
        WorkoutReadFixtures.observer=null;EmailOtpFixtures.CLOCK.reset();app.getBean(EmailOtpFixtures.FakeSender.class).reset();
        browser=new Browser();browser.login();owner=UUID.fromString(JSON.readTree(browser.send("GET","/__otp_test__/me",null,null).body()).get("id").asString());
    }
    @AfterEach void close() { WorkoutReadFixtures.observer=null;if(browser!=null)browser.close(); }
    @AfterAll static void stop() { if(app!=null)app.close(); }

    Seed seed(UUID user,String status,boolean deleted,String start,String date) {
        UUID routine=UUID.randomUUID(),session=UUID.randomUUID(),standard=UUID.randomUUID(),item=UUID.randomUUID();
        jdbc.update("INSERT INTO standard_exercise(id,name,is_active) VALUES (?,'historical standard',false)",standard);
        jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,0,'routine',now(),now())",routine,user);
        var initial=new InitialPlan(2,routine,"original routine","0",List.of(new InitialPlan.Weight(item,UUID.randomUUID(),standard,"historical standard",null,null,3,6,10,"80","80")),List.of());
        var applied=new WorkoutSnapshotData.Plan(2,List.of(new WorkoutSnapshotData.WeightPlan(item,1,6,10,null,null)),List.of());
        jdbc.update("""
                INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,
                initial_plan_snapshot,applied_plan_snapshot,last_workout_synced_at,created_at,updated_at)
                VALUES (?,?,0,?,?::timestamptz,?::date,'Asia/Seoul',?,?,?::jsonb,?::jsonb,now(),now(),now())
                """,session,user,routine,start,date,UUID.randomUUID(),UUID.randomUUID(),JSON.writeValueAsString(initial),JSON.writeValueAsString(applied));
        jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,standard_exercise_id) VALUES (?,?,?,0,'WEIGHT',?)",item,session,user,standard);
        var ids=new ArrayList<UUID>();
        for(int n=1;n<=3;n++) {
            UUID id=UUID.randomUUID();ids.add(id);
            jdbc.update("""
                    INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording)
                    VALUES (?,?,?,?,?,?,'2026-11-01T16:00:00Z','TODAY_ONLY','TODAY_ONLY')
                    """,id,session,item,n,n==3?70:80,n==2?8:10);
        }
        for(String name:List.of("quads","glutes","secondary")) {
            UUID muscle=UUID.randomUUID();jdbc.update("INSERT INTO muscle(id,group_code,name) VALUES (?,'LEGS',?)",muscle,name);
            jdbc.update("INSERT INTO exercise_muscle VALUES (?,?,?)",standard,muscle,name.equals("secondary")?"SECONDARY":"PRIMARY");
        }
        if(status.equals("FINISHED")) jdbc.update("UPDATE workout_sessions SET status='FINISHED',ended_at='2026-11-01T16:30:00Z',closed_at=now(),completion_assessment_snapshot='{\"schemaVersion\":2}' WHERE id=?",session);
        else if(status.equals("AUTO_CLOSED")) jdbc.update("UPDATE workout_sessions SET status='AUTO_CLOSED',closed_at=now() WHERE id=?",session);
        if(deleted)jdbc.update("UPDATE workout_sessions SET deleted_at=now() WHERE id=?",session);
        return new Seed(session,ids);
    }

    @Test void authenticatedReadUsesStartDateActualRepsAndRoleDedupWithoutChangingSourceFacts() throws Exception {
        var source=seed(owner,"FINISHED",false,"2026-11-01T14:30:00Z","2026-11-01");
        var before=jdbc.queryForMap("SELECT * FROM workout_sessions WHERE id=?",source.session());
        var data=success(browser.get(PATH));
        assertThat(data.get("summary").get("volumeLoadKg").asString()).isEqualTo("2140");
        assertThat(data.get("summary").get("completedSets").asString()).isEqualTo("3");
        assertThat(data.get("summary").get("plannedSets").asString()).isEqualTo("3");
        assertThat(data.get("plannedWorkoutDays").isNull()).isTrue();assertThat(data.get("routineAchievementPercent").isNull()).isTrue();
        assertThat(data.get("muscles").size()).isEqualTo(3);assertThat(data.get("groups").size()).isEqualTo(2);
        for(var group:data.get("groups"))assertThat(group.get("weights").get("completedSets").asString()).isEqualTo("3");
        assertThat(data.get("days").get(6).get("date").asString()).isEqualTo("2026-11-01");
        assertThat(data.get("days").get(6).get("weights").get("volumeLoadKg").asString()).isEqualTo("2140");
        assertThat(success(browser.get("/api/v1/statistics?fromDate=2026-11-02&toDateExclusive=2026-11-09")).get("summary").get("completedSets").asString()).isEqualTo("0");
        assertThat(jdbc.queryForMap("SELECT * FROM workout_sessions WHERE id=?",source.session())).isEqualTo(before);
    }
    @Test void scopeExcludesOtherOwnersActiveAndDeletedSessionsAndReadsCorrectionsAndMissingFactRecovery() throws Exception {
        UUID other=UUID.randomUUID();jdbc.update("INSERT INTO users(id) VALUES (?)",other);
        seed(other,"AUTO_CLOSED",false,"2026-11-01T14:30:00Z","2026-11-01");
        seed(owner,"AUTO_CLOSED",true,"2026-11-01T14:30:00Z","2026-11-01");
        var source=seed(owner,"AUTO_CLOSED",false,"2026-11-01T14:30:00Z","2026-11-01");
        seed(owner,"ACTIVE",false,"2026-11-01T14:30:00Z","2026-11-01");
        jdbc.update("UPDATE performed_sets SET weight=81,reps=9 WHERE id=?",source.sets().get(0));
        jdbc.update("UPDATE performed_sets SET deleted_at=now() WHERE id=?",source.sets().get(1));
        var data=success(browser.get(PATH));
        assertThat(data.get("summary").get("completedSets").asString()).isEqualTo("2");
        assertThat(data.get("summary").get("volumeLoadKg").asString()).isEqualTo("1429");
        assertThat(data.get("recentSessions").size()).isEqualTo(1);
        // Simulates a newly recovered missing UUID, never resurrection of the deleted UUID.
        jdbc.update("""
                INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording)
                SELECT ?,session_id,session_exercise_id,4,10,5,completed_at,'TODAY_ONLY','TODAY_ONLY' FROM performed_sets WHERE id=?
                """,UUID.randomUUID(),source.sets().get(0));
        assertThat(success(browser.get(PATH)).get("summary").get("volumeLoadKg").asString()).isEqualTo("1479");
        jdbc.update("UPDATE workout_sessions SET deleted_at=now() WHERE id=?",source.session());
        assertThat(success(browser.get(PATH)).get("summary").get("completedSets").asString()).isEqualTo("0");
    }
    @Test void readsAreReadOnlyRepeatableAndHaveFixedQueryCountWithoutWorkoutActivityUpdates() throws Exception {
        seed(owner,"AUTO_CLOSED",false,"2026-11-01T14:30:00Z","2026-11-01");
        var selects=new ArrayList<String>();
        WorkoutReadFixtures.observer=event->{if(event.before()&&event.sql().startsWith("SELECT")&&!event.sql().toLowerCase(Locale.ROOT).contains("spring_session")) {
            selects.add(event.sql());assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            try { assertThat(event.connection().isReadOnly()).isTrue();assertThat(event.connection().getTransactionIsolation()).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ); }
            catch(java.sql.SQLException failure){throw new AssertionError(failure);}
        }};
        success(browser.get(PATH));WorkoutReadFixtures.observer=null;
        assertThat(selects).hasSize(6);
    }
    @Test void authenticationAndStrictQueryContractRejectForgedScopeAndInvalidRanges() throws Exception {
        try(var anonymous=new Browser()){assertThat(anonymous.get(PATH).statusCode()).isEqualTo(401);}
        for(String query:List.of("", "?fromDate=2026-02-30&toDateExclusive=2026-03-01", "?fromDate=2026-11-02&toDateExclusive=2026-11-02",
                "?fromDate=2024-01-01&toDateExclusive=2026-11-02", "?fromDate=2026-10-26&toDateExclusive=2026-11-02&userId="+owner,
                "?fromDate=2026-10-26&fromDate=2026-10-26&toDateExclusive=2026-11-02")) {
            var response=browser.get("/api/v1/statistics"+query);assertThat(response.statusCode()).isEqualTo(400);assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(browser.send("GET",PATH,"{}",null).statusCode()).isEqualTo(400);
    }
    static JsonNode success(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("X-Request-ID")).isPresent();return JSON.readTree(response.body());
    }
    static class Browser implements AutoCloseable {
        final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        void login() throws Exception {
            String token=JSON.readTree(get("/api/v1/auth/csrf").body()).get("token").asString(),email=UUID.randomUUID()+"@example.test";
            var issued=send("POST","/api/v1/auth/email-otp/request",JSON.writeValueAsString(Map.of("email",email)),token);assertThat(issued.statusCode()).isEqualTo(202);
            var message=app.getBean(EmailOtpFixtures.FakeSender.class).messages.get(UUID.fromString(JSON.readTree(issued.body()).get("challengeId").asString()));
            assertThat(send("POST","/api/v1/auth/email-otp/verify",JSON.writeValueAsString(Map.of("email",email,"challengeId",message.issuanceId(),"code",message.code())),token).statusCode()).isEqualTo(204);
        }
        HttpResponse<String> get(String path) throws Exception{return send("GET",path,null,null);}
        HttpResponse<String> send(String method,String path,String body,String token) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+((WebServerApplicationContext)app).getWebServer().getPort()+path)).timeout(Duration.ofSeconds(15));
            if(token!=null)request.header("X-CSRF-TOKEN",token);if(body!=null)request.header("Content-Type","application/json");
            return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        public void close(){client.close();}
    }
}
