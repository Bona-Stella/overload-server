package com.overload.overload_server.deload;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.routine.RoutineResponses;
import com.overload.overload_server.workoutsession.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.net.*;
import java.net.http.*;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.overload.testfixture.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import static org.assertj.core.api.Assertions.*;

import com.overload.overload_server.routine.*;
import java.math.BigDecimal;
import static com.overload.overload_server.deload.DeloadPlan.*;
@Tag("postgres") @Testcontainers
class DeloadPeriodIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine").withDatabaseName("overload_g5_period_isolated").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext app;static JdbcTemplate jdbc;static final JsonMapper JSON=JsonMapper.builder().build();
    final LocalDate monday=LocalDate.of(2026,11,2);final Instant from=Instant.parse("2026-11-01T15:00:00Z");
    UUID owner;Browser browser;static final String PATH="/api/v1/deload-periods";
    record Session(UUID id,UUID routine,UUID item,List<UUID> sets) { }
    @BeforeAll static void start() {
        assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432,5433,12296);
        boot();
        DeloadScenarioClock.alignAccountDefaults(jdbc, POSTGRES);
    }
    static void boot() {
        var key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        app=new SpringApplicationBuilder(OverloadServerApplication.class,EmailOtpFixtures.class,WorkoutReadFixtures.class).web(WebApplicationType.SERVLET).run(
            "--spring.profiles.active=local,integration","--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
            "--server.address=127.0.0.1","--server.port=0","--OTP_HMAC_KEY="+Base64.getEncoder().encodeToString(key),"--OTP_EMAIL_ENABLED=false",
            "--GOOGLE_OIDC_ENABLED=false","--GOOGLE_REAUTH_ENABLED=false","--spring.session.jdbc.cleanup-cron=-","--logging.level.root=WARN","--spring.main.banner-mode=off");
        jdbc=app.getBean(JdbcTemplate.class);
    }
    @AfterAll static void stop() {if(app!=null)app.close();}

    @BeforeEach void setup()throws Exception {
        DeloadScenarioClock.set(DeloadScenarioClock.START);
        jdbc.update("DELETE FROM email_otp_rate_limits");
        app.getBean(EmailOtpFixtures.FakeSender.class).reset();
        browser=new Browser();browser.login();owner=UUID.fromString(JSON.readTree(browser.get("/__otp_test__/me").body()).get("id").asString());
        DeloadScenarioClock.bindNewAccount(app.getBean(DeloadAnalysisRepository.class), owner);
    }
    @AfterEach void closeBrowser() {
        try {
            if (browser != null) browser.close();
        } finally {
            EmailOtpFixtures.CLOCK.reset();
        }
    }
    static void clock(String value){DeloadScenarioClock.set(Instant.parse(value));}
    Instant at(int day){return monday.plusDays(day).atTime(12,0).atZone(ZoneId.of("Asia/Seoul")).toInstant();}
    DeloadPeriodService service(){return app.getBean(DeloadPeriodService.class);}
    Choice rest(){return new Choice(Method.REST,Split.REST,List.of());}
    DeloadPeriodService.Preview preview(Choice c){return service().preview(owner,0,c);}
    DeloadPeriodRequests.Start request(DeloadPeriodService.Preview p){return new DeloadPeriodRequests.Start(0,UUID.randomUUID(),p.previewToken(),p.choice());}
    DeloadPeriodService.Current start(Choice c){return service().start(owner,request(preview(c)));}
    JsonNode ok(HttpResponse<String> r){assertThat(r.statusCode()).as(r.body()).isEqualTo(200);return JSON.readTree(r.body());}
    void conflict(Runnable work,String code){assertThatThrownBy(work::run).isInstanceOfSatisfying(DeloadPeriodFailure.class,e->assertThat(e.code).isEqualTo(code));}
    UUID routine(UUID user,long generation) {UUID id=UUID.randomUUID();jdbc.update("INSERT INTO routines(id,user_id,data_generation,name,created_at,updated_at) VALUES (?,?,?,'fixture routine',now(),now())",id,user,generation);return id;}
    Session seed(UUID user,long generation,int day,Instant start,int planned,int actual,String status,boolean cardio,boolean damaged) {
        return seed(user,generation,day,start,planned,actual,status,cardio,damaged,false);
    }
    Session seed(UUID user,long generation,int day,Instant start,int planned,int actual,String status,boolean cardio,boolean damaged,boolean half) {
        return seed(user,generation,day,start,planned,actual,status,cardio,damaged,half,"Asia/Seoul");
    }
    Session seed(UUID user,long generation,int day,Instant start,int planned,int actual,String status,boolean cardio,boolean damaged,boolean half,String zone) {
        UUID routine=routine(user,generation),session=UUID.randomUUID(),item=UUID.randomUUID(),standard=UUID.randomUUID();
        jdbc.update("INSERT INTO standard_exercise(id,name,is_active) VALUES (?,'fixture lift',false)",standard);
        UUID second=UUID.randomUUID();UUID device=UUID.randomUUID();
        var goal=new RoutineResponses.Plan("TREADMILL","run",100,"1",null,null,null);
        var initial=new InitialPlan(2,routine,"sealed fixture","0",cardio?List.of():(half?List.of(new InitialPlan.Weight(item,UUID.randomUUID(),standard,"lift",null,null,1,6,10,"60","60"),new InitialPlan.Weight(second,UUID.randomUUID(),standard,"second",null,null,1,6,10,"60","60")):List.of(new InitialPlan.Weight(item,UUID.randomUUID(),standard,"lift",null,null,planned,6,10,"60","60"))),
            cardio?List.of(new InitialPlan.Cardio(item,UUID.randomUUID(),goal)):List.of());
        String json=damaged?"{\"schemaVersion\":2,\"weightExercises\":null}":JSON.writeValueAsString(initial);
        var applied=new WorkoutSnapshotData.Plan(2,List.of(),List.of());
        jdbc.update("""
            INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,
            initial_plan_snapshot,applied_plan_snapshot,last_workout_synced_at,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,now(),now(),now())
            """,session,user,generation,routine,Timestamp.from(start),monday.plusDays(day),zone,device,device,json,JSON.writeValueAsString(applied));
        if(cardio)jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind) VALUES (?,?,?,?,'CARDIO')",item,session,user,generation);
        else jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,standard_exercise_id) VALUES (?,?,?,?,'WEIGHT',?)",item,session,user,generation,standard);
        if(half)jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,standard_exercise_id) VALUES (?,?,?,?,'WEIGHT',?)",second,session,user,generation,standard);
        var ids=new ArrayList<UUID>();for(int i=1;i<=actual;i++) {var id=UUID.randomUUID();ids.add(id);jdbc.update("INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording) VALUES (?,?,?,?,60,10,?,'TODAY_ONLY','TODAY_ONLY')",id,session,item,i,Timestamp.from(start.plusSeconds(i*60)));}
        if(status.equals("FINISHED"))jdbc.update("UPDATE workout_sessions SET status='FINISHED',ended_at=greatest(now(),started_at+interval '1 hour'),closed_at=greatest(now(),started_at+interval '1 hour'),completion_assessment_snapshot='{\"schemaVersion\":2}' WHERE id=?",session);
        if(status.equals("AUTO_CLOSED"))jdbc.update("UPDATE workout_sessions SET status='AUTO_CLOSED',closed_at=now() WHERE id=?",session);
        if(status.equals("ACTIVE"))app.getBean(com.overload.overload_server.routine.WorkoutCalendarRepository.class).recordStart(user,generation,session,null,monday.plusDays(day),routine);
        return new Session(session,routine,item,ids);
    }
    Session weight(int day,int planned,int actual) {return seed(owner,0,day,at(day),planned,actual,"FINISHED",false,false);}
    void cardio(Session s,String state,long ms,String distance,boolean deleted) {
        jdbc.update("""
            INSERT INTO cardio_results(id,session_id,session_activity_id,status,started_at,occurred_at,timer_ms,active_ms,corrected,distance,deleted_at)
            VALUES (?,?,?,?,now()-interval '1 hour',now(),?,?,?,?::numeric,?)
            """,UUID.randomUUID(),s.id(),s.item(),state,state.equals("COMPLETED")?ms:null,state.equals("COMPLETED")?ms:null,state.equals("COMPLETED")?false:null,distance,deleted?Timestamp.from(Instant.now()):null);
    }


    UUID planRoutine() {
        var muscle=UUID.randomUUID();var standard=UUID.randomUUID();
        jdbc.update("INSERT INTO muscle(id,group_code,name,is_active) VALUES (?,'CHEST','대흉근',true)",muscle);
        jdbc.update("INSERT INTO standard_exercise(id,name,is_active,joint_type) VALUES (?,'fixture bench',true,'COMPOUND')",standard);
        jdbc.update("INSERT INTO exercise_muscle(standard_exercise_id,muscle_id,role) VALUES (?,?,'PRIMARY')",standard,muscle);
        UUID id=UUID.randomUUID();var w=new RoutineRequests.Weight(RoutineRequests.Mode.NEW,UUID.randomUUID(),standard,null,"PLATE",3,6,10,new BigDecimal("60"));
        app.getBean(RoutineService.class).create(owner,new RoutineRequests.Create(id,0,"immutable source",List.of(w),List.of()));return id;
    }
    void schedule(UUID id,int... days) {
        var assignments=new ArrayList<WeeklySchedule.Assignment>();for(int d:days)assignments.add(new WeeklySchedule.Assignment(d,id));
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,recorded_at,effective_from,time_zone,assignments) VALUES (?,0,1,?,?,'Asia/Seoul',?::jsonb)",owner,Timestamp.from(EmailOtpFixtures.CLOCK.instant()),monday,JSON.writeValueAsString(assignments));
        jdbc.update("INSERT INTO workout_calendar_control(user_id,data_generation,time_zone,baseline_version,review_from_date,reviewed_through) VALUES (?,0,'Asia/Seoul',1,?,?)",owner,monday,monday.minusDays(1));
    }
    List<String> originals(){var out=new ArrayList<String>();for(String t:List.of("routines","routine_weight_placements","routine_cardio_placements","weekly_schedule_versions","workout_sessions"))out.addAll(jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+t+" t WHERE user_id=? ORDER BY to_jsonb(t)::text",String.class,owner));for(String t:List.of("performed_sets","cardio_results"))out.addAll(jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+t+" t JOIN workout_sessions ws ON ws.id=t.session_id WHERE ws.user_id=? ORDER BY to_jsonb(t)::text",String.class,owner));return out;}
    @Test void sevenLocalDatesExcludeTodayAndStartOnlyNeverQualifies() {
        weight(-2,1,1);weight(0,1,0);assertThat(service().eligibility(owner).eligibility().eligible()).isFalse();
        weight(-1,4,1);assertThat(service().eligibility(owner).eligibility().eligible()).isTrue();
        weight(6,1,1);assertThat(service().eligibility(owner).eligibility().reasons()).contains("TODAY_GENERAL_ACTIVITY");
        conflict(()->start(rest()),"DELOAD_NOT_ELIGIBLE");
    }
    @Test void activeSessionAndSkippedOrDeletedCardioCannotQualify() {
        var s=seed(owner,0,2,at(2),1,0,"FINISHED",true,false);cardio(s,"SKIPPED",0,null,false);
        assertThat(service().eligibility(owner).eligibility().recentActual()).isFalse();
        var d=seed(owner,0,3,at(3),1,0,"FINISHED",true,false);cardio(d,"COMPLETED",1000,null,true);
        assertThat(service().eligibility(owner).eligibility().recentActual()).isFalse();
        var a=seed(owner,0,4,at(4),1,0,"ACTIVE",false,false);
        assertThat(service().eligibility(owner).eligibility().reasons()).contains("ACTIVE_GENERAL_SESSION");
        assertThat(a.id()).isNotNull();
    }
    @Test void completedCardioWithActivityCountsWithoutGoalCompletionEvidence() {
        var s=seed(owner,0,2,at(2),1,0,"FINISHED",true,false);cardio(s,"COMPLETED",1000,null,false);
        assertThat(service().eligibility(owner).eligibility().eligible()).isTrue();assertThat(start(rest()).period().plans()).isEmpty();
    }
    @Test void restWithNoScheduleAndOffZeroWeeksIsAValidPeriod() {
        weight(2,4,1);var repository=app.getBean(DeloadAnalysisRepository.class);var state=repository.current(owner,0);repository.append(owner,0,state.change(false,"Asia/Seoul",EmailOtpFixtures.CLOCK.instant()));
        var period=start(rest()).period();assertThat(period.method()).isEqualTo(Method.REST);assertThat(period.plans()).isEmpty();
        assertThat(period.recommendedEndDate()).isEqualTo(LocalDate.of(2026,11,14));assertThat(period.maximumEndDate()).isEqualTo(LocalDate.of(2026,11,17));
        assertThat(repository.current(owner,0).enabled()).isFalse();
    }
    @Test void oneDayKeepPreservesSourcesAndPersistsReducedImmutablePlan() {
        weight(2,4,1);UUID id=planRoutine();schedule(id,1);var before=originals();var choice=new Choice(Method.KEEP,Split.ORIGINAL,List.of());var p=preview(choice);
        assertThat(originals()).isEqualTo(before);assertThat(service().current(owner).period()).isNull();
        var period=service().start(owner,request(p)).period();assertThat(period.plans()).hasSize(1);var target=period.plans().getFirst().plan().weights().getFirst();
        assertThat(target.weightKg()).isEqualTo("50");assertThat(target.setCount()).isEqualTo(2);assertThat(target.minReps()).isEqualTo(5);assertThat(target.maxReps()).isEqualTo(8);
        assertThat(target.original().initialWeightKg()).isEqualTo("60");assertThat(period.source().days().getFirst().routine().id()).isEqualTo(id);assertThat(originals()).isEqualTo(before);
        assertThatThrownBy(()->jdbc.update("UPDATE deload_periods SET source_snapshot='{}' WHERE id=?",period.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE deload_temporary_plans SET initial_plan='{}' WHERE period_id=?",period.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void duplicateRequestIsIdempotentButDifferentPayloadOrActivePeriodIsRejected() {
        weight(2,1,1);var req=request(preview(rest()));var first=service().start(owner,req);assertThat(service().start(owner,req)).isEqualTo(first);
        conflict(()->service().start(owner,new DeloadPeriodRequests.Start(0,req.requestId(),"f".repeat(64),rest())),"DELOAD_REQUEST_CONFLICT");
        conflict(()->start(rest()),"DELOAD_ACTIVE_PERIOD");assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_periods WHERE user_id=?",Integer.class,owner)).isEqualTo(1);
    }
    @Test void sourceScheduleEligibilityAndDateChangesInvalidatePreview() {
        weight(2,1,1);UUID id=planRoutine();schedule(id,1);var c=new Choice(Method.KEEP,Split.ORIGINAL,List.of());var old=request(preview(c));
        jdbc.update("UPDATE routines SET name='changed',version=version+1 WHERE id=?",id);conflict(()->service().start(owner,old),"DELOAD_PREVIEW_STALE");
        var staleSchedule=request(preview(c));jdbc.update("INSERT INTO weekly_schedule_versions SELECT user_id,data_generation,2,recorded_at,effective_from,time_zone,'[]'::jsonb FROM weekly_schedule_versions WHERE user_id=?",owner);
        conflict(()->service().start(owner,staleSchedule),"INVALID_REQUEST");
    }
    @Test void tomorrowRequiresFreshPreviewAndTodayActivityRevalidatesEligibility() {
        weight(2,1,1);var old=request(preview(rest()));clock("2026-11-09T03:00:00Z");conflict(()->service().start(owner,old),"DELOAD_PREVIEW_STALE");
        weight(7,1,1);conflict(()->start(rest()),"DELOAD_NOT_ELIGIBLE");assertThat(service().current(owner).period()).isNull();
    }
    void previousCompleted(LocalDate date) {
        var at=date.atTime(13,0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        jdbc.update("INSERT INTO deload_periods(id,user_id,data_generation,request_id,request_hash,source_hash,started_at,start_date,time_zone,status,version,method,split,source_snapshot,closed_at,closed_date,updated_at) VALUES (?,?,0,?,?,?, ?,?,'Asia/Seoul','COMPLETED',1,'REST','REST','{}',?,?,?)",UUID.randomUUID(),owner,UUID.randomUUID(),"a".repeat(64),"b".repeat(64),Timestamp.from(at.minus(Duration.ofDays(2))),date.minusDays(2),Timestamp.from(at),date,Timestamp.from(at));
    }
    @Test void completionRequiresActualActivityAfterItsClosingInstant() {
        weight(0,1,1);previousCompleted(monday.plusDays(2));assertThat(service().eligibility(owner).eligibility().afterPreviousCompletion()).isFalse();
        weight(2,1,1);assertThat(service().eligibility(owner).eligibility().afterPreviousCompletion()).isFalse();
        seed(owner,0,2,at(2).plusSeconds(7200),1,1,"FINISHED",false,false);assertThat(service().eligibility(owner).eligibility().afterPreviousCompletion()).isTrue();
        weight(3,1,1);assertThat(service().eligibility(owner).eligibility().eligible()).isTrue();
    }
    @Test void concurrentPeriodStartsCreateExactlyOneAndRetryReturnsWinner()throws Exception {
        weight(2,1,1);var p=preview(rest());var a=request(p);var b=request(p);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=List.of(pool.submit(()->{gate.await();try{return service().start(owner,a).period().id().toString();}catch(DeloadPeriodFailure e){return e.code;}}),pool.submit(()->{gate.await();try{return service().start(owner,b).period().id().toString();}catch(DeloadPeriodFailure e){return e.code;}}));gate.countDown();
            var values=List.of(results.getFirst().get(15,TimeUnit.SECONDS),results.get(1).get(15,TimeUnit.SECONDS));assertThat(values).contains("DELOAD_ACTIVE_PERIOD");assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_periods WHERE user_id=?",Integer.class,owner)).isEqualTo(1);
        }
    }
    @Test void failureDuringChildInsertRollsBackPeriodAndAllChildren() {
        weight(2,1,1);schedule(planRoutine(),1,3);var request=request(preview(new Choice(Method.KEEP,Split.ORIGINAL,List.of())));
        jdbc.execute("CREATE FUNCTION fail_g5_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.position=1 THEN RAISE EXCEPTION 'fixture rollback'; END IF; RETURN NEW; END $$");jdbc.execute("CREATE TRIGGER fail_g5_fixture BEFORE INSERT ON deload_temporary_plans FOR EACH ROW EXECUTE FUNCTION fail_g5_fixture()");
        try{assertThatThrownBy(()->service().start(owner,request)).isInstanceOf(org.springframework.dao.DataAccessException.class);}finally{jdbc.execute("DROP TRIGGER fail_g5_fixture ON deload_temporary_plans");jdbc.execute("DROP FUNCTION fail_g5_fixture()");}
        assertThat(service().current(owner).period()).isNull();assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_temporary_plans WHERE user_id=?",Integer.class,owner)).isZero();
    }
    @Test void activePeriodBlocksNormalWritesButReadsAndAnalysisToggleRemainAvailable()throws Exception {
        weight(2,1,1);UUID id=planRoutine();schedule(id,1);start(rest());var before=originals();
        var update=Map.of("dataGeneration","0","expectedVersion","0");
        var rejected=browser.send("DELETE","/api/v1/routines/"+id,JSON.writeValueAsString(update),browser.csrf());assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);assertThat(JSON.readTree(rejected.body()).get("code").asString()).isEqualTo("DELOAD_ACTIVE_PERIOD");
        var schedule=browser.send("PUT","/api/v1/weekly-schedule",JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion","1","timeZone","Asia/Seoul","assignments",List.of())),browser.csrf());assertThat(schedule.statusCode()).as(schedule.body()).isEqualTo(409);
        var start=browser.send("POST","/api/v1/workout-sessions",JSON.writeValueAsString(Map.of("id",UUID.randomUUID(),"dataGeneration","0","routineId",id,"editorDeviceId",UUID.randomUUID(),"startedAt","2026-11-08T03:00:00Z","workoutDate","2026-11-08","startedTimeZone","Asia/Seoul")),browser.csrf());assertThat(start.statusCode()).as(start.body()).isEqualTo(409);
        assertThat(browser.get("/api/v1/routines/"+id).statusCode()).isEqualTo(200);assertThat(originals()).isEqualTo(before);
        var state=app.getBean(DeloadAnalysisRepository.class).current(owner,0);app.getBean(DeloadAnalysisRepository.class).append(owner,0,state.change(false,"Asia/Seoul",EmailOtpFixtures.CLOCK.instant()));assertThat(service().current(owner).period().status()).isEqualTo("ACTIVE");
    }
    @Test void authenticatedApiIsReadOnlyStrictAndIsolated()throws Exception {
        weight(2,1,1);var before=originals();for(String path:List.of("/eligibility","/options","/current")){assertThat(browser.get(PATH+path).statusCode()).isEqualTo(200);assertThat(browser.get(PATH+path+"?userId="+owner).statusCode()).isEqualTo(400);}
        var body=JSON.writeValueAsString(Map.of("dataGeneration","0","method","REST","split","REST","dates",List.of()));var p=ok(browser.send("POST",PATH+"/preview",body,browser.csrf()));assertThat(p.get("previewToken").asString()).hasSize(64);
        assertThat(browser.send("POST",PATH+"/preview",body,null).statusCode()).isEqualTo(403);assertThat(browser.send("POST",PATH+"/preview",body.replace("\"0\"","\"1\""),browser.csrf()).statusCode()).isEqualTo(409);
        assertThat(browser.send("POST",PATH+"/preview",body.replace("{","{\"userId\":\""+owner+"\","),browser.csrf()).statusCode()).isEqualTo(400);
        try(var fresh=new Browser()){assertThat(fresh.get(PATH+"/current").statusCode()).isEqualTo(401);fresh.login();assertThat(ok(fresh.get(PATH+"/current")).get("period").isNull()).isTrue();}
        assertThat(originals()).isEqualTo(before);assertThat(service().current(owner).period()).isNull();
    }
    @Test void g3StopsAtPeriodDatesAndG4HoldsWithoutFakeResetOrAdd() {
        weight(2,1,1);schedule(planRoutine(),1,2,3,4,5,6,7);start(rest());clock("2026-11-16T03:00:00Z");
        app.getBean(com.overload.overload_server.statistics.DailyTrainingReviewService.class).reviewBatch();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_training_assessments WHERE user_id=? AND workout_date>='2026-11-08'",Integer.class,owner)).isZero();
        var state=app.getBean(DeloadAnalysisRepository.class).current(owner,0);var week=app.getBean(DeloadWeeklyCandidates.class).calculate(owner,0,state.epoch(),monday.plusWeeks(1));assertThat(week.pending()).contains(DeloadCandidateCalculator.Pending.DELOAD_PERIOD);assertThat(week.e()).isNull();
        var result=app.getBean(DeloadWeeklyFinalization.class).finalizeWeek(owner,0,state.epoch(),monday);assertThat(result.status().name()).isEqualTo("PENDING");
        var rows=app.getBean(DeloadWeeklyRepository.class).rows(owner,0,state.epoch());assertThat(rows.stream().filter(r->r.week().equals(monday)).findFirst().orElseThrow().operation()).isNull();
    }
    @Test void httpStartPersistsOnlyTheAuthenticatedOwnersCurrentGeneration()throws Exception {
        weight(2,1,1);var p=preview(rest());var body=new LinkedHashMap<String,Object>();body.put("dataGeneration","0");body.put("requestId",UUID.randomUUID());body.put("previewToken",p.previewToken());body.put("method","REST");body.put("split","REST");body.put("dates",List.of());
        var response=browser.send("POST",PATH,JSON.writeValueAsString(body),browser.csrf());var value=ok(response);assertThat(value.get("period").get("status").asString()).isEqualTo("ACTIVE");assertThat(value.get("userId").asString()).isEqualTo(owner.toString());assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(ok(browser.send("POST",PATH,JSON.writeValueAsString(body),browser.csrf()))).isEqualTo(value);
    }
    @Test void activePeriodBlocksOriginalRecordCorrectionsAndDeleteWithoutChangingFacts()throws Exception {
        var session=weight(2,1,1);start(rest());var before=originals();var body=JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion","0"));
        var response=browser.send("DELETE","/api/v1/workout-sessions/"+session.id(),body,browser.csrf());assertThat(response.statusCode()).as(response.body()).isEqualTo(409);assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("DELOAD_ACTIVE_PERIOD");assertThat(originals()).isEqualTo(before);
    }
    @Test void databaseEnforcesActiveUniquenessPlanOwnerAndInitialDateRange() {
        weight(2,1,1);schedule(planRoutine(),1);var p=start(new Choice(Method.KEEP,Split.ORIGINAL,List.of())).period();
        assertThatThrownBy(()->jdbc.update("INSERT INTO deload_periods(id,user_id,data_generation,request_id,request_hash,source_hash,started_at,start_date,time_zone,status,version,method,split,source_snapshot,updated_at) SELECT ?,user_id,data_generation,?,request_hash,source_hash,started_at,start_date,time_zone,status,version,method,split,source_snapshot,updated_at FROM deload_periods WHERE id=?",UUID.randomUUID(),UUID.randomUUID(),p.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO deload_temporary_plans(id,user_id,data_generation,period_id,position,planned_date,initial_plan) VALUES (?,?,1,?,5,?,'{}')",UUID.randomUUID(),owner,p.id(),p.startDate())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO deload_temporary_plans(id,user_id,data_generation,period_id,position,planned_date,initial_plan) VALUES (?,?,0,?,5,?,'{}')",UUID.randomUUID(),owner,p.id(),p.startDate().plusDays(7))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void newSnapshotIsRejectedEvenForInconsistentOutOfBandActiveSessionFixture()throws Exception {
        var session=seed(owner,0,6,at(6),1,0,"ACTIVE",false,false);
        jdbc.update("INSERT INTO deload_periods(id,user_id,data_generation,request_id,request_hash,source_hash,started_at,start_date,time_zone,status,version,method,split,source_snapshot,updated_at) VALUES (?,?,0,?,?,?,?,'2026-11-08','Asia/Seoul','ACTIVE',0,'REST','REST','{}',?)",UUID.randomUUID(),owner,UUID.randomUUID(),"a".repeat(64),"b".repeat(64),Timestamp.from(EmailOtpFixtures.CLOCK.instant()),Timestamp.from(EmailOtpFixtures.CLOCK.instant()));
        var device=jdbc.queryForObject("SELECT editor_device_id FROM workout_sessions WHERE id=?",UUID.class,session.id());var standard=jdbc.queryForObject("SELECT standard_exercise_id FROM session_exercises WHERE id=?",UUID.class,session.item());
        var weight=new LinkedHashMap<String,Object>();weight.put("sessionExerciseId",session.item());weight.put("setCount",1);weight.put("minReps",6);weight.put("maxReps",10);weight.put("manufacturerId",null);weight.put("loadType",null);
        var snapshot=Map.of("sessionExercises",List.of(Map.of("id",session.item(),"kind","WEIGHT","standardExerciseId",standard)),"appliedPlan",Map.of("schemaVersion",2,"weightExercises",List.of(weight),"cardioActivities",List.of()),"performedSets",List.of(),"cardioResults",List.of(),"deletedPerformedSetIds",List.of(),"deletedCardioResultIds",List.of(),"approvalRequests",List.of());
        var body=JSON.writeValueAsString(Map.of("dataGeneration","0","editorDeviceId",device,"writerEpoch","0","localRevision","1","snapshot",snapshot));var before=originals();
        var response=browser.send("PUT","/api/v1/workout-sessions/"+session.id()+"/snapshot",body,browser.csrf());assertThat(response.statusCode()).as(response.body()).isEqualTo(409);assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("DELOAD_ACTIVE_PERIOD");assertThat(originals()).isEqualTo(before);
    }
    @Test void normalStartAndDeloadStartSerializeOnOwnerWithoutBothSucceeding()throws Exception {
        weight(2,1,1);UUID id=planRoutine();var p=request(preview(rest()));var token=browser.csrf();var gate=new CountDownLatch(1);
        var body=JSON.writeValueAsString(Map.of("id",UUID.randomUUID(),"dataGeneration","0","routineId",id,"editorDeviceId",UUID.randomUUID(),"startedAt","2026-11-08T03:00:00Z","workoutDate","2026-11-08","startedTimeZone","Asia/Seoul"));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var normal=pool.submit(()->{gate.await();return browser.send("POST","/api/v1/workout-sessions",body,token);});var period=pool.submit(()->{gate.await();try{service().start(owner,p);return true;}catch(DeloadPeriodFailure failure){assertThat(failure.code).isIn("DELOAD_NOT_ELIGIBLE","DELOAD_PREVIEW_STALE");return false;}});gate.countDown();
            var n=normal.get(15,TimeUnit.SECONDS);boolean created=period.get(15,TimeUnit.SECONDS);assertThat(n.statusCode()).as(n.body()).isIn(201,409);assertThat(n.statusCode()==201).isNotEqualTo(created);
        }
    }
    @Test void scheduleWriteAndPeriodStartCannotCommitInconsistentSources()throws Exception {
        weight(2,1,1);schedule(planRoutine(),1);var p=request(preview(rest()));String token=browser.csrf();var gate=new CountDownLatch(1);
        var body=JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion","1","timeZone","Asia/Seoul","assignments",List.of()));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var change=pool.submit(()->{gate.await();return browser.send("PUT","/api/v1/weekly-schedule",body,token);});var period=pool.submit(()->{gate.await();try{service().start(owner,p);return true;}catch(DeloadPeriodFailure failure){assertThat(failure.code).isEqualTo("DELOAD_PREVIEW_STALE");return false;}});gate.countDown();
            var c=change.get(15,TimeUnit.SECONDS);boolean created=period.get(15,TimeUnit.SECONDS);assertThat(c.statusCode()).as(c.body()).isIn(200,409);assertThat(c.statusCode()==200).isNotEqualTo(created);
        }
    }
    @Test void lastConfirmedPrefixSurvivesPauseAndOffOnDoesNotCloseThePeriod()throws Exception {
        var repository=app.getBean(DeloadAnalysisRepository.class);jdbc.update("DELETE FROM deload_analysis_versions WHERE user_id=?",owner);jdbc.update("UPDATE users SET created_at='2026-01-01T00:00:00Z' WHERE id=?",owner);
        var beginning=from.minus(Duration.ofDays(7));app.getBean(DeloadAnalysisService.class).adoptExisting(owner,0,beginning);var state=repository.current(owner,0);repository.append(owner,0,state.change(true,"Asia/Seoul",beginning));
        weight(-7,1,1);weight(-6,1,1);assertThat(app.getBean(DeloadWeeklyFinalization.class).finalizeWeek(owner,0,0,monday.minusWeeks(1)).confirmed()).isEqualTo(1);
        weight(2,1,1);start(rest());clock("2026-11-16T03:00:00Z");app.getBean(DeloadWeeklyFinalization.class).runBatch();
        var response=ok(browser.get("/api/v1/deload-analysis"));assertThat(response.get("calculationStatus").asString()).isEqualTo("PAUSED");assertThat(response.get("confirmedWeeks").get("numerator").asString()).isEqualTo("1");assertThat(response.get("recommendationReady").asBoolean()).isFalse();
        var current=repository.current(owner,0);repository.append(owner,0,current.change(false,"Asia/Seoul",EmailOtpFixtures.CLOCK.instant()));current=repository.current(owner,0);repository.append(owner,0,current.change(true,"Asia/Seoul",EmailOtpFixtures.CLOCK.instant()));
        assertThat(service().current(owner).period().status()).isEqualTo("ACTIVE");assertThat(ok(browser.get("/api/v1/deload-analysis")).get("calculationStatus").asString()).isEqualTo("PAUSED");
    }
    private DeloadPeriodLifecycle lifecycle() {
        return app.getBean(DeloadPeriodLifecycle.class);
    }
    private DeloadPeriodRequests.End endRequest(DeloadPeriodRepository.Stored period) {
        return new DeloadPeriodRequests.End(0,Long.parseLong(period.version()),UUID.randomUUID());
    }
    private String endBody(DeloadPeriodRequests.End request) {
        return JSON.writeValueAsString(Map.of("dataGeneration",Long.toString(request.generation()),
            "expectedVersion",Long.toString(request.expectedVersion()),"requestId",request.requestId()));
    }
    private HttpResponse<String> normalStart(UUID routine, String startedAt, String date) throws Exception {
        return normalStart(routine,startedAt,date,"Asia/Seoul");
    }
    private HttpResponse<String> normalStart(UUID routine, String startedAt, String date, String zone) throws Exception {
        var body=Map.of("id",UUID.randomUUID(),"dataGeneration","0","routineId",routine,"editorDeviceId",UUID.randomUUID(),
            "startedAt",startedAt,"workoutDate",date,"startedTimeZone",zone);
        return browser.send("POST","/api/v1/workout-sessions",JSON.writeValueAsString(body),browser.csrf());
    }
    private DeloadAnalysisRepository analysis() {
        return app.getBean(DeloadAnalysisRepository.class);
    }
    private DeloadWeeklyFinalization finalization() {
        return app.getBean(DeloadWeeklyFinalization.class);
    }
    private void seedFourAndThreeQuarters() {
        jdbc.update("DELETE FROM deload_analysis_versions WHERE user_id=?",owner);
        jdbc.update("UPDATE users SET created_at='2026-01-01T00:00:00Z' WHERE id=?",owner);
        var beginning=from.minus(Duration.ofDays(35));
        app.getBean(DeloadAnalysisService.class).adoptExisting(owner,0,beginning);
        analysis().append(owner,0,analysis().current(owner,0).change(true,"Asia/Seoul",beginning));
        for(int day=-35;day<=-14;day+=7) {
            weight(day,1,1);
            weight(day+1,1,1);
        }
        for(int day=-7;day<=-5;day++)weight(day,1,1);
        weight(-4,2,1);
        for(int week=5;week>=1;week--) {
            assertThat(finalization().finalizeWeek(owner,0,0,monday.minusWeeks(week)).confirmed()).isEqualTo(1);
        }
    }
    private void assertTotal(String numerator,String denominator) throws Exception {
        var weeks=ok(browser.get("/api/v1/deload-analysis")).get("confirmedWeeks");
        assertThat(weeks.get("numerator").asString()).isEqualTo(numerator);
        assertThat(weeks.get("denominator").asString()).isEqualTo(denominator);
    }

    @Test void completeRestImmediatelyResetsAnalysisAndPreservesOriginalsThroughLocalMidnight() throws Exception {
        seedFourAndThreeQuarters();
        weight(2,1,1);
        var routine=planRoutine();
        var period=start(rest()).period();
        var before=originals();
        assertTotal("19","4");
        var request=endRequest(period);
        var path=PATH+"/"+period.id()+"/complete";
        var first=ok(browser.send("POST",path,endBody(request),browser.csrf()));
        var completed=first.get("period");
        assertThat(completed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(completed.get("endReason").asString()).isEqualTo("MANUAL_COMPLETION");
        assertThat(completed.get("closedDate").asString()).isEqualTo("2026-11-08");
        assertThat(completed.get("closedAt").asString()).isEqualTo("2026-11-08T03:00:00Z");
        assertThat(completed.get("processedAt")).isEqualTo(completed.get("closedAt"));
        assertThat(completed.get("plans").size()).isZero();
        assertThat(ok(browser.send("POST",path,endBody(request),browser.csrf()))).isEqualTo(first);
        conflict(()->lifecycle().end(owner,period.id(),request,true),"DELOAD_REQUEST_CONFLICT");
        assertThat(analysis().current(owner,0).reason()).isEqualTo("DELOAD_COMPLETED");
        assertThat(analysis().current(owner,0).epoch()).isNotZero();
        var after=ok(browser.get("/api/v1/deload-analysis"));
        assertThat(after.get("epochInitialWeeks").get("numerator").asString()).isEqualTo("0");
        assertThat(after.get("confirmedWeeks").isNull()).isTrue();
        assertThat(after.get("calculationStatus").asString()).isEqualTo("INITIAL");
        assertThat(after.get("analysisResumesOn").asString()).isEqualTo("2026-11-09");
        assertThat(ok(browser.get(PATH+"/history")).get("periods").get(0).get("endReason").asString()).isEqualTo("MANUAL_COMPLETION");
        assertThat(ok(browser.get(PATH+"/"+period.id()))).isEqualTo(first);
        assertThat(ok(browser.get(PATH+"/current"))).isEqualTo(first);
        assertThat(normalStart(routine,"2026-11-08T03:00:00Z","2026-11-08").statusCode()).isEqualTo(409);
        var delete=JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion","0"));
        assertThat(browser.send("DELETE","/api/v1/routines/"+routine,delete,browser.csrf()).statusCode()).isEqualTo(409);
        assertThat(originals()).isEqualTo(before);
        clock("2026-11-08T15:00:00Z");
        assertThat(service().current(owner).period()).isNull();
        assertThat(normalStart(routine,"2026-11-08T14:59:59Z","2026-11-08").statusCode()).isEqualTo(409);
        var next=normalStart(routine,"2026-11-08T15:00:00Z","2026-11-08","America/Los_Angeles");
        assertThat(next.statusCode()).as(next.body()).isEqualTo(201);
        assertThat(JSON.readTree(next.body()).get("workoutDate").asString()).isEqualTo("2026-11-08");
        assertThat(analysis().epochHistory(owner,0,0)).isNotEmpty();
    }

    @Test void cancelRestAfterSeveralDaysKeepsFourAndThreeQuartersAndExcludesOnlyProtectedReset() throws Exception {
        seedFourAndThreeQuarters();
        weight(2,1,1);
        var routine=planRoutine();
        var period=start(rest()).period();
        long settingsVersion=analysis().current(owner,0).version();
        clock("2026-11-11T03:00:00Z");
        var request=endRequest(period);
        var cancelled=lifecycle().end(owner,period.id(),request,true);
        assertThat(cancelled.period().status()).isEqualTo("CANCELLED");
        assertThat(cancelled.period().endReason()).isEqualTo("USER_CANCELLED");
        assertThat(lifecycle().end(owner,period.id(),request,true)).isEqualTo(cancelled);
        assertThat(service().current(owner).period()).isNull();
        assertThat(analysis().current(owner,0).version()).isEqualTo(settingsVersion);
        assertThat(normalStart(routine,"2026-11-11T02:59:59Z","2026-11-11").statusCode()).isEqualTo(409);
        assertThat(finalization().finalizeWeek(owner,0,0,monday).confirmed()).isEqualTo(1);
        assertTotal("19","4");
        clock("2026-11-15T19:00:00Z");
        assertThat(finalization().finalizeWeek(owner,0,0,monday.plusWeeks(1)).confirmed()).isEqualTo(1);
        var rows=app.getBean(DeloadWeeklyRepository.class).rows(owner,0,0);
        assertThat(rows.stream().filter(row->!row.week().isBefore(monday))).allSatisfy(row->{
            assertThat(row.status()).isEqualTo("EXCLUDED");
            assertThat(row.operation()).isNull();
        });
        assertTotal("19","4");
        clock("2026-11-22T19:00:00Z");
        assertThat(finalization().finalizeWeek(owner,0,0,monday.plusWeeks(2)).confirmed()).isEqualTo(1);
        assertTotal("0","1");
    }

    @Test void newEpochConfirmsOnlyRemainingDatesInCompletionWeekAfterMondayFour() throws Exception {
        weight(2,1,1);
        var routine=planRoutine();
        schedule(routine,3,4);
        var period=start(rest()).period();
        clock("2026-11-10T03:00:00Z");
        lifecycle().end(owner,period.id(),endRequest(period),false);
        var current=analysis().current(owner,0);
        assertThat(current.resumeDate()).isEqualTo(LocalDate.of(2026,11,11));
        weight(9,1,1);
        weight(10,1,1);
        clock("2026-11-15T18:59:59Z");
        assertThat(finalization().finalizeWeek(owner,0,current.epoch(),monday.plusWeeks(1)).status().name()).isEqualTo("NOT_DUE");
        assertThat(ok(browser.get("/api/v1/deload-analysis")).get("confirmedWeeks").isNull()).isTrue();
        clock("2026-11-15T19:00:00Z");
        var review=app.getBean(com.overload.overload_server.statistics.DailyTrainingReviewService.class);
        review.reviewBatch();
        assertThat(finalization().finalizeWeek(owner,0,current.epoch(),monday.plusWeeks(1)).confirmed()).isEqualTo(1);
        var row=app.getBean(DeloadWeeklyRepository.class).rows(owner,0,current.epoch()).getFirst();
        assertThat(row.e().numerator()).hasToString("2");
        assertThat(row.n()).isEqualTo(2);
        assertThat(row.operation()).isEqualTo("ADD");
        assertTotal("1","1");
        review.reviewBatch();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM daily_training_assessments WHERE user_id=? AND workout_date BETWEEN '2026-11-08' AND '2026-11-10'
              AND first_missed_at IS NOT NULL
            """,Integer.class,owner)).isZero();
    }

    @Test void cancelledDatesNeverCreateRetroactiveMissAndRealWorkAfterCancelCanAdd() throws Exception {
        weight(2,1,1);
        schedule(planRoutine(),2,3,4);
        var period=start(rest()).period();
        long epoch=analysis().current(owner,0).epoch();
        clock("2026-11-11T03:00:00Z");
        lifecycle().end(owner,period.id(),endRequest(period),true);
        weight(9,1,1);
        weight(10,1,1);
        clock("2026-11-15T19:00:00Z");
        var review=app.getBean(com.overload.overload_server.statistics.DailyTrainingReviewService.class);
        review.reviewBatch();
        assertThat(finalization().finalizeWeek(owner,0,epoch,monday).confirmed()).isEqualTo(1);
        assertThat(finalization().finalizeWeek(owner,0,epoch,monday.plusWeeks(1)).confirmed()).isEqualTo(1);
        review.reviewBatch();
        var rows=app.getBean(DeloadWeeklyRepository.class).rows(owner,0,epoch);
        assertThat(rows.getFirst().status()).isEqualTo("EXCLUDED");
        assertThat(rows.getLast().operation()).isEqualTo("ADD");
        assertThat(rows.getLast().n()).isEqualTo(2);
        assertThat(rows.getLast().e().numerator()).hasToString("2");
        assertTotal("1","1");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM daily_training_assessments WHERE user_id=? AND workout_date BETWEEN '2026-11-08' AND '2026-11-11'
              AND first_missed_at IS NOT NULL
            """,Integer.class,owner)).isZero();
    }

    @Test void automaticDeadlineDoesNotEndOnDaysSevenThroughTenAndAllowsDayEleven() throws Exception {
        weight(2,1,1);
        var routine=planRoutine();
        var period=start(rest()).period();
        for(var time:List.of("2026-11-14T14:59:59Z","2026-11-14T15:00:00Z","2026-11-15T15:00:00Z",
                "2026-11-16T15:00:00Z","2026-11-17T14:59:59.999999Z")) {
            clock(time);
            lifecycle().completeDuePeriods();
            assertThat(service().current(owner).period().status()).isEqualTo("ACTIVE");
        }
        clock("2026-11-17T15:00:00Z");
        conflict(()->lifecycle().end(owner,period.id(),endRequest(period),true),"DELOAD_AUTO_COMPLETION_DUE");
        assertThat(normalStart(routine,"2026-11-17T15:00:00Z","2026-11-18").statusCode()).isEqualTo(409);
        lifecycle().completeDuePeriods();
        var ended=service().detail(owner,period.id()).period();
        assertThat(ended.endReason()).isEqualTo("AUTOMATIC_COMPLETION");
        assertThat(ended.closedDate()).isEqualTo(LocalDate.of(2026,11,17));
        assertThat(ended.closedAt()).isEqualTo(Instant.parse("2026-11-17T14:59:59.999999Z"));
        assertThat(ended.processedAt()).isEqualTo(Instant.parse("2026-11-17T15:00:00Z"));
        assertThat(service().current(owner).period()).isNull();
        assertThat(normalStart(routine,"2026-11-17T15:00:00Z","2026-11-18").statusCode()).isEqualTo(201);
    }

    @Test void onlyOneWorkerOrManualRequestClosesPeriodAndAppendsEpoch() throws Exception {
        weight(2,1,1);
        var period=start(rest()).period();
        var request=endRequest(period);
        clock("2026-11-18T03:00:00Z");
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(3)) {
            var first=pool.submit(()->{gate.await();return lifecycle().completeDuePeriods();});
            var second=pool.submit(()->{gate.await();return lifecycle().completeDuePeriods();});
            var manual=pool.submit(()->{gate.await();return lifecycle().end(owner,period.id(),request,false);});
            gate.countDown();
            first.get(15,TimeUnit.SECONDS);
            second.get(15,TimeUnit.SECONDS);
            assertThat(manual.get(15,TimeUnit.SECONDS).period().endReason()).isEqualTo("AUTOMATIC_COMPLETION");
        }
        assertThat(service().detail(owner,period.id()).period().version()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_analysis_versions WHERE completed_period_id=?",Integer.class,period.id())).isOne();
    }

    @Test void analysisOffCompletionAndCancellationPreserveOffUntilUserReenables() {
        weight(2,1,1);
        analysis().append(owner,0,analysis().current(owner,0).change(false,"Asia/Seoul",EmailOtpFixtures.CLOCK.instant()));
        var disabled=analysis().current(owner,0);
        var cancelled=start(rest()).period();
        lifecycle().end(owner,cancelled.id(),endRequest(cancelled),true);
        assertThat(analysis().current(owner,0)).isEqualTo(disabled);
        var completed=start(rest()).period();
        lifecycle().end(owner,completed.id(),endRequest(completed),false);
        assertThat(analysis().current(owner,0)).isEqualTo(disabled);
        var enabled=disabled.change(true,"Asia/Seoul",EmailOtpFixtures.CLOCK.instant());
        analysis().append(owner,0,enabled);
        assertThat(analysis().current(owner,0).reason()).isEqualTo("ENABLED");
        assertThat(analysis().current(owner,0).resumeDate()).isNull();
    }

    @Test void failedAutomaticEpochAppendRollsBackAndRestartRetriesSameEffectiveEnd() {
        weight(2,1,1);
        var period=start(rest()).period();
        var initial=analysis().current(owner,0);
        clock("2026-11-15T19:00:00Z");
        assertThat(finalization().finalizeWeek(owner,0,initial.epoch(),monday).status().name()).isEqualTo("PENDING");
        clock("2026-11-19T03:00:00Z");
        jdbc.execute("""
            CREATE FUNCTION fail_g53_epoch_fixture() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN IF NEW.completed_period_id='$PERIOD$' THEN RAISE EXCEPTION 'fixture rollback'; END IF; RETURN NEW; END $$;
            """.replace("$PERIOD$",period.id().toString()));
        jdbc.execute("""
            CREATE TRIGGER fail_g53_epoch_fixture BEFORE INSERT ON deload_analysis_versions
                FOR EACH ROW EXECUTE FUNCTION fail_g53_epoch_fixture()
            """);
        try {
            lifecycle().completeDuePeriods();
            assertThat(service().detail(owner,period.id()).period().status()).isEqualTo("ACTIVE");
            assertThat(analysis().current(owner,0)).isEqualTo(initial);
        } finally {
            jdbc.execute("DROP TRIGGER fail_g53_epoch_fixture ON deload_analysis_versions");
            jdbc.execute("DROP FUNCTION fail_g53_epoch_fixture()");
        }
        app.close();
        boot();
        assertThat(service().detail(owner,period.id()).period().status()).isEqualTo("ACTIVE");
        lifecycle().completeDuePeriods();
        var ended=service().detail(owner,period.id()).period();
        assertThat(ended.endReason()).isEqualTo("AUTOMATIC_COMPLETION");
        assertThat(ended.closedDate()).isEqualTo(LocalDate.of(2026,11,17));
        assertThat(ended.processedAt()).isEqualTo(Instant.parse("2026-11-19T03:00:00Z"));
        assertThat(analysis().current(owner,0).resumeDate()).isEqualTo(LocalDate.of(2026,11,18));
        finalization().runBatch();
        var previous=app.getBean(DeloadWeeklyRepository.class).rows(owner,0,initial.epoch());
        assertThat(previous.getFirst().status()).isEqualTo("EXCLUDED");
    }

    @Test void lifecycleCommandsAndHistoryEnforceCsrfOwnershipGenerationAndShape() throws Exception {
        weight(2,1,1);
        var period=start(rest()).period();
        var path=PATH+"/"+period.id()+"/complete";
        var request=endRequest(period);
        var body=endBody(request);
        assertThat(browser.send("POST",path,body,null).statusCode()).isEqualTo(403);
        assertThat(browser.send("POST",path,body.replace("\"dataGeneration\":\"0\"","\"dataGeneration\":\"1\""),browser.csrf()).statusCode()).isEqualTo(409);
        assertThat(browser.send("POST",path,body.replace("{","{\"userId\":\""+owner+"\","),browser.csrf()).statusCode()).isEqualTo(400);
        assertThat(browser.get(PATH+"/history?offset=0&userId="+owner).statusCode()).isEqualTo(400);
        conflict(()->lifecycle().end(owner,period.id(),new DeloadPeriodRequests.End(0,1,UUID.randomUUID()),false),"DELOAD_PERIOD_VERSION_CONFLICT");
        try(var other=new Browser()) {
            assertThat(other.get(PATH+"/history").statusCode()).isEqualTo(401);
            other.login();
            assertThat(other.get(PATH+"/"+period.id()).statusCode()).isEqualTo(404);
            assertThat(other.send("POST",path,body,other.csrf()).statusCode()).isEqualTo(404);
            assertThat(ok(other.get(PATH+"/history?offset=0")).get("periods").size()).isZero();
        }
        assertThat(service().current(owner).period().status()).isEqualTo("ACTIVE");
    }

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
