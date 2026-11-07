package com.overload.overload_server.statistics;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.workoutsession.*;
import com.overload.testfixture.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    static final String PATH="/api/v1/statistics/overview?fromDate=2026-10-26&toDateExclusive=2026-11-02";
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
        WorkoutReadFixtures.observer=null;EmailOtpFixtures.CLOCK.reset();
        EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(),Instant.parse("2026-10-26T00:00:00Z")));
        app.getBean(EmailOtpFixtures.FakeSender.class).reset();jdbc.update("DELETE FROM email_otp_rate_limits");
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
        var data=success(browser.get(PATH)).get("statistics");
        assertThat(data.get("summary").get("volumeLoadKg").asString()).isEqualTo("2140");
        assertThat(data.get("summary").get("completedSets").asString()).isEqualTo("3");
        assertThat(data.get("summary").get("plannedSets").asString()).isEqualTo("0");
        assertThat(data.get("planCoverage").asString()).isEqualTo("PARTIAL_SCHEDULE_HISTORY");
        assertThat(data.get("plannedWorkoutDays").isNull()).isTrue();assertThat(data.get("routineAchievementPercent").isNull()).isTrue();
        assertThat(data.get("muscles").size()).isEqualTo(3);assertThat(data.get("groups").size()).isEqualTo(2);
        for(var group:data.get("groups"))assertThat(group.get("weights").get("completedSets").asString()).isEqualTo("3");
        assertThat(data.get("days").get(6).get("date").asString()).isEqualTo("2026-11-01");
        assertThat(data.get("days").get(6).get("weights").get("volumeLoadKg").asString()).isEqualTo("2140");
        assertThat(success(browser.get("/api/v1/statistics/overview?fromDate=2026-11-02&toDateExclusive=2026-11-09")).get("statistics").get("summary").get("completedSets").asString()).isEqualTo("0");
        // A session without a schedule origin is not retroactively classified as a planned START.
        EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(),Instant.parse("2026-11-01T00:00:00Z")));
        schedule(JSON.valueToTree(Map.of("id",before.get("source_routine_id"))),"0",List.of(7));
        var planned=success(browser.get(PATH));
        assertThat(planned.get("statistics").get("summary").get("plannedSets").asString()).isEqualTo("0");
        assertThat(planned.get("statistics").get("summary").get("completedSets").asString()).isEqualTo("3");
        assertThat(planned.get("statistics").get("planCoverage").asString()).isEqualTo("FULL_SCHEDULE_HISTORY");
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
        var data=success(browser.get(PATH)).get("statistics");
        assertThat(data.get("summary").get("completedSets").asString()).isEqualTo("2");
        assertThat(data.get("summary").get("volumeLoadKg").asString()).isEqualTo("1429");
        assertThat(data.get("recentSessions").size()).isEqualTo(1);
        // Simulates a newly recovered missing UUID, never resurrection of the deleted UUID.
        jdbc.update("""
                INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording)
                SELECT ?,session_id,session_exercise_id,4,10,5,completed_at,'TODAY_ONLY','TODAY_ONLY' FROM performed_sets WHERE id=?
                """,UUID.randomUUID(),source.sets().get(0));
        assertThat(success(browser.get(PATH)).get("statistics").get("summary").get("volumeLoadKg").asString()).isEqualTo("1479");
        jdbc.update("UPDATE workout_sessions SET deleted_at=now() WHERE id=?",source.session());
        assertThat(success(browser.get(PATH)).get("statistics").get("summary").get("completedSets").asString()).isEqualTo("0");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void readsAreReadOnlyRepeatableAndHaveFixedQueryCountWithoutWorkoutActivityUpdates(boolean scheduled) throws Exception {
        if(scheduled){
            EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(),Instant.parse("2026-11-01T00:00:00Z")));
            schedule(routine(1),"0",List.of(7));
        }
        seed(owner,"AUTO_CLOSED",false,"2026-11-01T14:30:00Z","2026-11-01");
        var selects=new ArrayList<String>();
        WorkoutReadFixtures.observer=event->{if(event.before()&&(event.sql().stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT")||event.sql().stripLeading().toUpperCase(Locale.ROOT).startsWith("WITH"))&&!event.sql().toLowerCase(Locale.ROOT).contains("spring_session")) {
            selects.add(event.sql());assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            try { assertThat(event.connection().isReadOnly()).isTrue();assertThat(event.connection().getTransactionIsolation()).isEqualTo(java.sql.Connection.TRANSACTION_REPEATABLE_READ); }
            catch(java.sql.SQLException failure){throw new AssertionError(failure);}
        }};
        success(browser.get(PATH));WorkoutReadFixtures.observer=null;
        assertThat(selects).hasSize(scheduled?12:11);
    }
    @Test void authenticationAndStrictQueryContractRejectForgedScopeAndInvalidRanges() throws Exception {
        try(var anonymous=new Browser()){assertThat(anonymous.get(PATH).statusCode()).isEqualTo(401);}
        for(String query:List.of("", "?fromDate=2026-02-30&toDateExclusive=2026-03-01", "?fromDate=2026-11-02&toDateExclusive=2026-11-02",
                "?fromDate=2024-01-01&toDateExclusive=2026-11-02", "?fromDate=2026-10-26&toDateExclusive=2026-11-02&userId="+owner,
                "?fromDate=2026-10-26&fromDate=2026-10-26&toDateExclusive=2026-11-02",
                "?fromDate=2026-10-26&toDateExclusive=2026-11-02&dataGeneration=1")) {
            var response=browser.get("/api/v1/statistics/overview"+query);assertThat(response.statusCode()).isEqualTo(400);assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_REQUEST");
        }
        assertThat(browser.send("GET",PATH,"{}",null).statusCode()).isEqualTo(400);
    }
    @Test void overviewUsesCurrentOwnerAndExactLargeGeneration() throws Exception {
        long generation=9007199254740993L;
        jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?",owner);
        jdbc.update("DELETE FROM deload_analysis_versions WHERE user_id=?", owner); jdbc.update("UPDATE users SET data_generation=? WHERE id=?",generation,owner);
        var response=success(browser.get(PATH));var data=response.get("statistics");
        assertThat(data.get("userId").asString()).isEqualTo(owner.toString());
        assertThat(data.get("dataGeneration").asString()).isEqualTo(Long.toString(generation));
        assertThat(data.get("summary").get("completedSets").asString()).isEqualTo("0");
        assertThat(data.get("plannedWorkoutDays").isNull()).isTrue();
    }
    @Test void retiredStatisticsEndpointHasNoHandler() {
        var mappings=app.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class);
        assertThat(mappings.getHandlerMethods().keySet().stream().flatMap(mapping->mapping.getPatternValues().stream()))
                .doesNotContain("/api/v1/statistics").contains("/api/v1/statistics/overview");
    }
    String csrf() throws Exception{return JSON.readTree(browser.get("/api/v1/auth/csrf").body()).get("token").asString();}
    @Test void unregisteredDatesRemainUnknownWithTheNewEvidenceQueries(){
        var result=app.getBean(StatisticsService.class).overview(owner,new StatisticsQuery(LocalDate.of(2026,11,2),LocalDate.of(2026,11,9)));
        assertThat(result.unknownPlanDays()).isEqualTo(7);assertThat(result.statistics().plannedWorkoutDays()).isNull();
    }
    JsonNode routine(int setCount) throws Exception {
        return routine(setCount,CatalogFixtures.exercise(jdbc));
    }
    JsonNode routine(int setCount,UUID standard) throws Exception {
        UUID id=UUID.randomUUID();var weights=new ArrayList<Map<String,Object>>();
        for(int n=0;n<4;n++){var w=new LinkedHashMap<String,Object>();w.put("id",UUID.randomUUID());w.put("standardExerciseId",standard);w.put("manufacturerId",null);w.put("loadType",null);w.put("setCount",setCount);w.put("minReps",6);w.put("maxReps",10);w.put("initialWeightKg","80");weights.add(w);}
        var body=Map.of("id",id,"dataGeneration","0","name","scheduled routine","weightPlacements",weights,"cardioPlacements",List.of());
        var response=browser.send("POST","/api/v1/routines",JSON.writeValueAsString(body),csrf());assertThat(response.statusCode()).isEqualTo(201);return JSON.readTree(response.body());
    }
    JsonNode schedule(JsonNode routine,String expected,List<Integer> days) throws Exception {
        var body=Map.of("contractVersion","immediate-v1","dataGeneration","0","expectedVersion",expected,"timeZone","Asia/Seoul","assignments",days.stream().map(d->Map.of("dayOfWeek",d,"routineId",routine.get("id").asString())).toList());
        return success(browser.send("PUT","/api/v1/weekly-schedule",JSON.writeValueAsString(body),csrf()));
    }
    JsonNode overview(LocalDate from) throws Exception{return success(browser.get("/api/v1/statistics/overview?fromDate="+from+"&toDateExclusive="+from.plusDays(7)));}
    void perform(JsonNode routine,LocalDate day,int completed) throws Exception {
        Instant at=day.atTime(10,0).toInstant(ZoneOffset.UTC);UUID id=UUID.randomUUID(),device=UUID.randomUUID();
        var start=Map.of("id",id,"dataGeneration","0","routineId",routine.get("id").asString(),"editorDeviceId",device,"startedAt",at,"workoutDate",day.toString(),"startedTimeZone","Asia/Seoul");
        var started=browser.send("POST","/api/v1/workout-sessions",JSON.writeValueAsString(start),csrf());assertThat(started.statusCode()).isEqualTo(201);
        var detail=JSON.readTree(started.body());var initial=detail.get("initialPlan");
        var registry=new ArrayList<Map<String,Object>>();var plans=new ArrayList<Map<String,Object>>();var sets=new ArrayList<Map<String,Object>>();int n=0;
        for(var w:initial.get("weightExercises")) {
            String exercise=w.get("sessionExerciseId").asString();registry.add(Map.of("id",exercise,"kind","WEIGHT","standardExerciseId",w.get("standardExerciseId").asString()));
            var p=new LinkedHashMap<String,Object>();p.put("sessionExerciseId",exercise);p.put("setCount",1);p.put("minReps",6);p.put("maxReps",10);p.put("manufacturerId",null);p.put("loadType",null);plans.add(p);
            if(n++<completed)for(int set=1;set<=w.get("setCount").asInt();set++) {
                var value=new LinkedHashMap<String,Object>();value.put("id",UUID.randomUUID());value.put("sessionExerciseId",exercise);value.put("ordinal",set);value.put("weightKg","80");value.put("reps",8);value.put("completedAt",at.plusSeconds(10));
                var context=new LinkedHashMap<String,Object>();context.put("kind","INITIAL_APPROVED");context.put("requestId",null);value.put("weightContext",context);value.put("manufacturerId",null);value.put("loadType",null);sets.add(value);
            }
        }
        var snapshot=Map.of("sessionExercises",registry,"appliedPlan",Map.of("schemaVersion",2,"weightExercises",plans,"cardioActivities",List.of()),"performedSets",sets,"cardioResults",List.of(),"deletedPerformedSetIds",List.of(),"deletedCardioResultIds",List.of(),"approvalRequests",List.of());
        var finish=Map.of("dataGeneration","0","editorDeviceId",device,"writerEpoch","0","localRevision","1","snapshot",snapshot,"endedAt",at.plusSeconds(600));
        assertThat(browser.send("POST","/api/v1/workout-sessions/"+id+"/finish",JSON.writeValueAsString(finish),csrf()).statusCode()).isEqualTo(200);
    }
    @Test void weeklyScheduleCountsMissedDaysAndBestSingleInitialPlanSession() throws Exception {
        var routine=routine(1);var saved=schedule(routine,"0",List.of(1,2,4,5));var monday=LocalDate.parse(saved.get("appliedAt").asString().substring(0,10));
        assertThat(saved.get("version").asString()).isEqualTo("1");assertThat(saved.has("pending")).isFalse();
        perform(routine,monday,4);perform(routine,monday.plusDays(1),2);perform(routine,monday.plusDays(3),3);
        perform(routine,monday.plusDays(1),1);perform(routine,monday.plusDays(2),4);
        var result=overview(monday);var stats=result.get("statistics");
        assertThat(stats.get("plannedWorkoutDays").asInt()).isEqualTo(4);assertThat(stats.get("routineAchievementPercent").asString()).isEqualTo("56.25");
        assertThat(result.get("effectiveWorkoutDays").asString()).isEqualTo("2.25");assertThat(stats.get("summary").get("plannedSets").asString()).isEqualTo("16");
        assertThat(stats.get("summary").get("completedSets").asString()).isEqualTo("14");
        assertThat(result.get("plannedDays").get(4).get("effectiveDay").asString()).isEqualTo("0");
        assertThat(overview(monday.minusDays(14)).get("statistics").get("plannedWorkoutDays").isNull()).isTrue();
    }
    @Test void sharedJudgmentKeepsScheduledBestSingleResultAndStoredFactsUnchanged() throws Exception {
        var r=routine(4);var saved=schedule(r,"0",List.of(1,2));var monday=LocalDate.parse(saved.get("appliedAt").asString().substring(0,10));
        perform(r,monday,2);perform(r,monday,3);
        var extra=routine(1);perform(extra,monday,4);perform(r,monday.plusDays(2),4);
        var before=jdbc.queryForList("SELECT to_jsonb(s)::text FROM workout_sessions s WHERE user_id=? ORDER BY id",String.class,owner);
        var setsBefore=jdbc.queryForList("SELECT to_jsonb(p)::text FROM performed_sets p WHERE session_id IN(SELECT id FROM workout_sessions WHERE user_id=?) ORDER BY id",String.class,owner);
        var facts=app.getBean(StatisticsRepository.class).read(owner,0,new StatisticsQuery(monday,monday.plusDays(7)));
        var fractions=new ArrayList<com.overload.overload_server.completion.ExactFraction>();
        for(var s:facts.sessions())if(s.date().equals(monday)&&s.initial().routineId().toString().equals(r.get("id").asString())) {
            var counts=new HashMap<UUID,Integer>();facts.sets().stream().filter(p->p.sessionId().equals(s.id())).forEach(p->counts.merge(p.exerciseId(),1,Integer::sum));
            fractions.add(com.overload.overload_server.completion.InitialPlanCompletion.judge(s.id(),s.initial(),counts,Map.of()).lower());
        }
        assertThat(fractions).containsExactlyInAnyOrder(com.overload.overload_server.completion.ExactFraction.of(1,2),com.overload.overload_server.completion.ExactFraction.of(3,4));
        var result=overview(monday);
        assertThat(result.get("effectiveWorkoutDays").asString()).isEqualTo("0.75");
        assertThat(result.get("statistics").get("plannedWorkoutDays").asInt()).isEqualTo(2);
        assertThat(result.get("statistics").get("routineAchievementPercent").asString()).isEqualTo("37.5");
        assertThat(result.get("plannedDays").get(2).get("status").asString()).isEqualTo("REST");
        assertThat(jdbc.queryForList("SELECT to_jsonb(s)::text FROM workout_sessions s WHERE user_id=? ORDER BY id",String.class,owner)).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT to_jsonb(p)::text FROM performed_sets p WHERE session_id IN(SELECT id FROM workout_sessions WHERE user_id=?) ORDER BY id",String.class,owner)).isEqualTo(setsBefore);
    }
    @Test void firstThursdayRegistrationStartsTodayAndEditsApplyImmediatelyWithoutChangingStartedPlans() throws Exception {
        var thursday=LocalDate.of(2026,11,12);var monday=thursday.minusDays(3);
        EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(),thursday.atTime(5,0).toInstant(ZoneOffset.UTC)));
        assertThat(success(browser.get("/api/v1/weekly-schedule?timeZone=Asia/Seoul")).get("version").asString()).isEqualTo("0");
        var r=routine(1);var saved=schedule(r,"0",List.of(1,4,5,6));
        assertThat(saved.get("appliedAt").asString().substring(0,10)).isEqualTo(thursday.toString());assertThat(saved.has("pending")).isFalse();
        var original=jdbc.queryForMap("SELECT * FROM weekly_schedule_versions WHERE user_id=? AND version=1",owner);
        perform(r,monday,4);perform(r,thursday,4);perform(r,thursday.plusDays(1),4);
        var first=overview(monday);assertThat(first.get("statistics").get("plannedWorkoutDays").asInt()).isEqualTo(3);
        assertThat(first.get("statistics").get("routineAchievementPercent").asString()).isEqualTo("66.666667");
        assertThat(first.get("effectiveWorkoutDays").asString()).isEqualTo("2");assertThat(first.get("unknownPlanDays").asInt()).isZero();
        assertThat(first.get("statistics").get("summary").get("plannedSets").asString()).isEqualTo("12");
        assertThat(first.get("statistics").get("summary").get("completedSets").asString()).isEqualTo("12");
        for(int day=0;day<3;day++){var before=first.get("plannedDays").get(day);assertThat(before.get("status").asString()).isEqualTo("BEFORE_REGISTRATION");assertThat(before.get("plan").isNull()).isTrue();assertThat(before.get("effectiveDay").isNull()).isTrue();}
        assertThat(first.get("plannedDays").get(5).get("effectiveDay").asString()).isEqualTo("0");
        assertThat(overview(monday.plusDays(7)).get("statistics").get("plannedWorkoutDays").asInt()).isEqualTo(4);
        var changed=schedule(r,"1",List.of(2,4));assertThat(changed.get("appliedAt").asString().substring(0,10)).isEqualTo(thursday.toString());
        assertThat(overview(monday).get("plannedDays").get(3).get("plan")).isEqualTo(first.get("plannedDays").get(3).get("plan"));assertThat(overview(monday.plusDays(7)).get("statistics").get("plannedWorkoutDays").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForMap("SELECT * FROM weekly_schedule_versions WHERE user_id=? AND version=1",owner)).isEqualTo(original);
        assertThat(overview(monday.minusDays(7)).get("statistics").get("plannedWorkoutDays").isNull()).isTrue();
    }
    @Test void sundayRegistrationWithNoRemainingPlannedDayDoesNotInventAchievementAndNextWeekHasAllDays() throws Exception {
        var sunday=LocalDate.of(2026,11,15);
        EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(),sunday.atTime(5,0).toInstant(ZoneOffset.UTC)));
        var r=routine(1);schedule(r,"0",List.of(1,4,5,6));
        var first=overview(sunday.minusDays(6));assertThat(first.get("statistics").get("plannedWorkoutDays").asInt()).isZero();
        assertThat(first.get("statistics").get("routineAchievementPercent").isNull()).isTrue();assertThat(first.get("unknownPlanDays").asInt()).isZero();
        assertThat(overview(sunday.plusDays(1)).get("statistics").get("plannedWorkoutDays").asInt()).isEqualTo(4);
    }
    @Test void scheduleWritesRequireCsrfOwnerGenerationAndExpectedVersionAndKeepImmutableHistory() throws Exception {
        var routine=routine(1);var first=schedule(routine,"0",List.of(1,3));schedule(routine,"1",List.of(2,4));
        assertThat(success(browser.get("/api/v1/weekly-schedule/history?beforeVersion=3")).get("history").size()).isEqualTo(2);
        var stale=Map.of("contractVersion","immediate-v1","dataGeneration","0","expectedVersion","0","timeZone","Asia/Seoul","assignments",List.of());
        assertThat(browser.send("PUT","/api/v1/weekly-schedule",JSON.writeValueAsString(stale),csrf()).statusCode()).isEqualTo(409);
        assertThat(browser.send("PUT","/api/v1/weekly-schedule",JSON.writeValueAsString(stale),null).statusCode()).isEqualTo(403);
        assertThat(overview(LocalDate.parse(first.get("appliedAt").asString().substring(0,10))).get("statistics").get("plannedWorkoutDays").asInt()).isEqualTo(2);
        try(var other=new Browser()) {other.login();assertThat(success(other.get("/api/v1/weekly-schedule?timeZone=Asia/Seoul")).get("assignments").isEmpty()).isTrue();}
        var unchanged=jdbc.queryForMap("SELECT assignments::text,effective_from FROM weekly_schedule_versions WHERE user_id=? AND version=1",owner);
        assertThat(unchanged.get("assignments").toString()).contains("\"dayOfWeek\": 1");
        assertThatThrownBy(()->jdbc.update("UPDATE weekly_schedule_versions SET assignments='[]' WHERE user_id=?",owner)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void routineEditsPreserveStartedPlansAndRetainMissedDayHistory() throws Exception {
        var r=routine(1);var saved=schedule(r,"0",List.of(1,2,4,5));var monday=LocalDate.parse(saved.get("appliedAt").asString().substring(0,10));
        perform(r,monday,4);var before=jdbc.queryForList("SELECT initial_plan_snapshot::text,applied_plan_snapshot::text FROM workout_sessions WHERE user_id=?",owner);
        Instant thursday=monday.plusDays(3).atTime(6,0).toInstant(ZoneOffset.UTC);EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(),thursday));
        var weights=new ArrayList<Map<String,Object>>();for(var old:r.get("weightPlacements")){
            var w=new LinkedHashMap<String,Object>();w.put("id",old.get("id").asString());w.put("mode","EXISTING");w.put("manufacturerId",null);w.put("loadType",null);w.put("setCount",2);w.put("minReps",6);w.put("maxReps",10);weights.add(w);
        }
        var update=Map.of("dataGeneration","0","expectedVersion","0","name","updated","weightPlacements",weights,"cardioPlacements",List.of(),"weightApprovals",List.of());
        success(browser.send("PUT","/api/v1/routines/"+r.get("id").asString(),JSON.writeValueAsString(update),csrf()));
        var result=overview(monday);assertThat(result.get("statistics").get("summary").get("plannedSets").asString()).isEqualTo("24");
        assertThat(result.get("statistics").get("routineAchievementPercent").asString()).isEqualTo("25");
        assertThat(result.get("plannedDays").get(1).get("plan").get("weightPlacements").get(0).get("setCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT initial_plan_snapshot::text,applied_plan_snapshot::text FROM workout_sessions WHERE user_id=?",owner)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_plan_history WHERE user_id=?",Integer.class,owner)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM progress_change_events WHERE user_id=?",Integer.class,owner)).isZero();
        var revision=schedule(r,"1",List.of(1));assertThat(revision.get("appliedAt").asString()).isEqualTo(thursday.toString());
        assertThat(overview(monday).get("statistics").get("plannedWorkoutDays").asInt()).isEqualTo(2);
    }
    @Test void concurrentScheduleSavesSerializeAndNoopDoesNotCreateAnotherVersion() throws Exception {
        var r=routine(1);schedule(r,"0",List.of(1));String token=csrf();
        var bodies=List.of(2,3).stream().map(day->JSON.writeValueAsString(Map.of("contractVersion","immediate-v1","dataGeneration","0","expectedVersion","1","timeZone","Asia/Seoul","assignments",List.of(Map.of("dayOfWeek",day,"routineId",r.get("id").asString()))))).toList();
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var tasks=bodies.stream().<java.util.concurrent.Callable<Integer>>map(body->()->browser.send("PUT","/api/v1/weekly-schedule",body,token).statusCode()).toList();
            var statuses=new ArrayList<Integer>();for(var future:pool.invokeAll(tasks))statuses.add(future.get());assertThat(statuses).containsExactlyInAnyOrder(200,409);
        }
        var current=success(browser.get("/api/v1/weekly-schedule?timeZone=Asia/Seoul"));
        var body=Map.of("contractVersion","immediate-v1","dataGeneration","0","expectedVersion","2","timeZone","Asia/Seoul","assignments",current.get("assignments"));
        assertThat(success(browser.send("PUT","/api/v1/weekly-schedule",JSON.writeValueAsString(body),token)).get("version").asString()).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_schedule_versions WHERE user_id=?",Integer.class,owner)).isEqualTo(2);
    }
    @Test void routineDeletionImmediatelyRemovesAssignmentsWithoutErasingPastPlannedDays() throws Exception {
        var r=routine(1);var saved=schedule(r,"0",List.of(1,3,5));var monday=LocalDate.parse(saved.get("appliedAt").asString().substring(0,10));
        var wednesday=monday.plusDays(2).atTime(6,0).toInstant(ZoneOffset.UTC);EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(),wednesday));
        assertThat(browser.send("DELETE","/api/v1/routines/"+r.get("id").asString(),"{\"dataGeneration\":\"0\",\"expectedVersion\":\"0\"}",csrf()).statusCode()).isEqualTo(204);
        assertThat(overview(monday).get("statistics").get("plannedWorkoutDays").asInt()).isEqualTo(1);
        assertThat(overview(monday.plusDays(7)).get("statistics").get("plannedWorkoutDays").asInt()).isZero();
        var after=success(browser.get("/api/v1/weekly-schedule?timeZone=Asia/Seoul"));assertThat(after.get("assignments").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_plan_history WHERE user_id=?",Integer.class,owner)).isEqualTo(2);
    }
    @Test void scheduleHistoryIsPagedAndLatestImmediateVersionRemainsVisibleAfterManyEdits() throws Exception {
        var r=routine(1);var saved=schedule(r,"0",List.of(1));var monday=LocalDate.parse(saved.get("appliedAt").asString().substring(0,10));
        EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(),monday.atTime(6,0).toInstant(ZoneOffset.UTC)));
        for(int version=1;version<=21;version++)schedule(r,Integer.toString(version),List.of(version%2==1?2:1));
        var current=success(browser.get("/api/v1/weekly-schedule?timeZone=Asia/Seoul"));
        assertThat(success(browser.get("/api/v1/weekly-schedule/history?beforeVersion=23")).get("history").size()).isEqualTo(20);assertThat(current.get("version").asString()).isEqualTo("22");
        assertThat(current.has("pending")).isFalse();
        var page=success(browser.get("/api/v1/weekly-schedule/history?beforeVersion=3"));assertThat(page.get("history").size()).isEqualTo(2);
        assertThat(page.get("nextBeforeVersion").isNull()).isTrue();
        assertThat(overview(monday).get("statistics").get("plannedWorkoutDays").asInt()).isEqualTo(1);
    }
    @Test void failedHistoryWriteRollsBackRoutineDeletionAndScheduleReservationTogether() throws Exception {
        var r=routine(1);schedule(r,"0",List.of(1));UUID id=UUID.fromString(r.get("id").asString());
        var before=jdbc.queryForMap("SELECT * FROM routines WHERE id=?",id);var hit=new java.util.concurrent.atomic.AtomicBoolean();
        WorkoutReadFixtures.observer=event->{if(event.before()&&event.sql().stripLeading().toLowerCase(Locale.ROOT).startsWith("insert into routine_plan_history")){
            hit.set(true);throw new org.springframework.dao.DataAccessResourceFailureException("isolated planning history failure");
        }};
        try{assertThat(browser.send("DELETE","/api/v1/routines/"+id,"{\"dataGeneration\":\"0\",\"expectedVersion\":\"0\"}",csrf()).statusCode()).isEqualTo(503);}
        finally{WorkoutReadFixtures.observer=null;}
        assertThat(hit).isTrue();assertThat(jdbc.queryForMap("SELECT * FROM routines WHERE id=?",id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM routine_plan_history WHERE user_id=?",Integer.class,owner)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_schedule_versions WHERE user_id=?",Integer.class,owner)).isEqualTo(1);
    }
    String growth(){return "/api/v1/statistics/growth/exercises";}
    JsonNode approve(JsonNode r,int index,String next,String occurred) throws Exception {
        var weights=new ArrayList<Map<String,Object>>();for(var old:r.get("weightPlacements")){
            var w=new LinkedHashMap<String,Object>();w.put("mode","EXISTING");w.put("id",old.get("id").asString());w.put("manufacturerId",null);w.put("loadType",null);w.put("setCount",old.get("setCount").asInt());w.put("minReps",6);w.put("maxReps",10);weights.add(w);
        }
        var change=Map.of("placementId",r.get("weightPlacements").get(index).get("id").asString(),"nextApprovedWeightKg",next,"occurredAt",occurred);
        return success(browser.send("PUT","/api/v1/routines/"+r.get("id").asString(),JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion",r.get("version").asString(),"name",r.get("name").asString(),"weightPlacements",weights,"cardioPlacements",List.of(),"weightApprovals",List.of(change))),csrf()));
    }
    void growthClock(){EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(),Instant.parse("2026-11-20T06:00:00Z")));}
    @Test void growthReusesActualApprovalsSeparatesPlacementsAndPreservesDeletedRoutineHistory() throws Exception {
        growthClock();var r=routine(1);String standard=r.get("weightPlacements").get(0).get("standardExerciseId").asString(),placement=r.get("weightPlacements").get(0).get("id").asString();
        r=approve(r,0,"82.5","2026-09-01T06:00:00Z");EmailOtpFixtures.CLOCK.advance(Duration.ofMinutes(1));
        r=approve(r,0,"85","2026-11-10T06:00:00Z");EmailOtpFixtures.CLOCK.advance(Duration.ofMinutes(1));r=approve(r,1,"100","2026-11-11T06:00:00Z");
        var before=jdbc.queryForList("SELECT * FROM progress_change_events WHERE user_id=? ORDER BY id",owner);
        String root=growth()+"/"+standard;var options=success(browser.get(root+"?period=all&timeZone=UTC"));assertThat(options.get("placements").size()).isEqualTo(2);
        var all=success(browser.get(root+"/placements/"+placement+"?period=all&timeZone=UTC"));assertThat(all.get("points").size()).isEqualTo(2);
        assertThat(all.get("points").get(0).get("approvedWeightKg").asString()).isEqualTo("85");assertThat(all.get("points").get(0).get("connectFromPrevious").asBoolean()).isTrue();
        assertThat(all.get("points").get(1).get("approvedWeightKg").asString()).isEqualTo("82.5");assertThat(all.get("points").get(1).get("connectFromPrevious").asBoolean()).isFalse();
        assertThat(success(browser.get(root+"/placements/"+placement+"?period=1&timeZone=UTC")).get("points").size()).isEqualTo(1);
        assertThat(browser.send("DELETE","/api/v1/routines/"+r.get("id").asString(),JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion",r.get("version").asString())),csrf()).statusCode()).isEqualTo(204);
        assertThat(success(browser.get(root+"?period=all&timeZone=UTC")).get("placements").get(0).get("deleted").asBoolean()).isTrue();
        assertThat(success(browser.get(growth()+"?period=all&timeZone=UTC")).get("exercises").size()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM progress_change_events WHERE user_id=? ORDER BY id",owner)).isEqualTo(before);
        try(var other=new Browser()){other.login();assertThat(other.get(root+"?period=all&timeZone=UTC").statusCode()).isEqualTo(404);assertThat(success(other.get(growth()+"?period=all&timeZone=UTC")).get("exercises").isEmpty()).isTrue();}
    }
    @Test void growthSearchIncludesHistoricalExtraExercisesPrioritizesRecentPerformanceAndNeverInventsApproval() throws Exception {
        growthClock();var source=seed(owner,"FINISHED",false,"2026-11-01T14:30:00Z","2026-11-01");
        var r=routine(1);r=approve(r,0,"90","2026-11-19T06:00:00Z");
        var result=success(browser.get(growth()+"?period=1&timeZone=Asia/Seoul"));assertThat(result.get("exercises").size()).isEqualTo(2);
        assertThat(result.get("exercises").get(0).get("name").asString()).isEqualTo("historical standard");
        var old=result.get("exercises").get(0).get("standardExerciseId").asString();assertThat(success(browser.get(growth()+"/"+old+"?period=all&timeZone=UTC")).get("placements").isEmpty()).isTrue();
        jdbc.update("UPDATE standard_exercise SET name='renamed current standard' WHERE id=?",UUID.fromString(old));
        assertThat(success(browser.get(growth()+"?period=all&timeZone=UTC&q=historical")).get("exercises").size()).isEqualTo(1);
        assertThat(success(browser.get(growth()+"?period=all&timeZone=UTC&q=%25")).get("exercises").isEmpty()).isTrue();
        jdbc.update("UPDATE workout_sessions SET deleted_at=now() WHERE id=?",source.session());
        assertThat(success(browser.get(growth()+"?period=all&timeZone=UTC&q=historical")).get("exercises").isEmpty()).isTrue();
    }
    @Test void growthNeverJoinsTwoRoutinesSharingOneStandardExercise() throws Exception {
        growthClock();UUID standard=CatalogFixtures.exercise(jdbc);var a=routine(1,standard);var b=routine(1,standard);
        a=approve(a,0,"85","2026-11-10T06:00:00Z");EmailOtpFixtures.CLOCK.advance(Duration.ofMinutes(1));b=approve(b,0,"100","2026-11-11T06:00:00Z");
        var root=growth()+"/"+standard;assertThat(success(browser.get(root+"?period=all&timeZone=UTC")).get("placements").size()).isEqualTo(2);
        for(var r:List.of(a,b)){var page=success(browser.get(root+"/placements/"+r.get("weightPlacements").get(0).get("id").asString()+"?period=all&timeZone=UTC"));
            assertThat(page.get("points").size()).isEqualTo(1);assertThat(page.get("points").get(0).get("connectFromPrevious").asBoolean()).isFalse();assertThat(page.get("placement").get("routineId").asString()).isEqualTo(r.get("id").asString());}
    }
    @Test void growthPaginationAndAmbiguousApprovalTimesNeverCreateAnUnsupportedContinuousLine() throws Exception {
        growthClock();var r=routine(1);UUID placement=UUID.fromString(r.get("weightPlacements").get(0).get("id").asString());String standard=r.get("weightPlacements").get(0).get("standardExerciseId").asString();
        for(int n=0;n<105;n++)jdbc.update("INSERT INTO progress_change_events(id,user_id,data_generation,routine_id,placement_id,routine_name,exercise_name,previous_weight,next_weight,occurred_at,recorded_at,source) VALUES (?,?,0,?,?,'r','lift',?,?,?::timestamptz,?::timestamptz,'ROUTINE_EDITOR')",UUID.randomUUID(),owner,UUID.fromString(r.get("id").asString()),placement,80+n,81+n,Instant.parse("2026-11-01T00:00:00Z").plusSeconds(n).toString(),"2026-11-20T06:00:00Z");
        String path=growth()+"/"+standard+"/placements/"+placement+"?period=all&timeZone=UTC";var page=success(browser.get(path));assertThat(page.get("points").size()).isEqualTo(100);assertThat(page.get("nextOffset").asInt()).isEqualTo(100);
        for(var p:page.get("points"))assertThat(p.get("connectFromPrevious").asBoolean()).isFalse();
        var last=success(browser.get(path+"&offset=100"));assertThat(last.get("points").size()).isEqualTo(5);assertThat(last.get("nextOffset").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT approved_weight FROM routine_weight_placements WHERE id=?",java.math.BigDecimal.class,placement)).isEqualByComparingTo("80");
    }
    @Test void growthContractIsAuthenticatedReadOnlyAndStrict() throws Exception {
        try(var anonymous=new Browser()){assertThat(anonymous.get(growth()+"?period=all&timeZone=UTC").statusCode()).isEqualTo(401);}
        for(String query:List.of("period=2&timeZone=UTC","period=all&timeZone=bogus","period=all&period=1&timeZone=UTC","period=all&timeZone=UTC&userId="+owner,"period=all&timeZone=UTC&offset=01"))assertThat(browser.get(growth()+"?"+query).statusCode()).isEqualTo(400);
        assertThat(browser.send("GET",growth()+"?period=all&timeZone=UTC","{}",null).statusCode()).isEqualTo(400);
        var seen=new java.util.concurrent.atomic.AtomicInteger();WorkoutReadFixtures.observer=event->{String sql=event.sql().stripLeading().toLowerCase(Locale.ROOT);if(event.before()&&(sql.startsWith("with bounds as")||sql.startsWith("select data_generation from users where id=?"))){assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();seen.incrementAndGet();}};
        try{success(browser.get(growth()+"?period=all&timeZone=UTC"));}finally{WorkoutReadFixtures.observer=null;}assertThat(seen.get()).isEqualTo(2);
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
