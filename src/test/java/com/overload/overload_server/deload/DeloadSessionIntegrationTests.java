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
class DeloadSessionIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine").withDatabaseName("overload_g5_sessions_isolated").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
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
    DeloadTemporaryPlans temporary(){return app.getBean(DeloadTemporaryPlans.class);}
    DeloadPeriodService service(){return app.getBean(DeloadPeriodService.class);}
    Choice rest(){return new Choice(Method.REST,Split.REST,List.of());}
    DeloadPeriodService.Preview preview(Choice c){return service().preview(owner,0,null,c);}
    DeloadPeriodRequests.Start request(DeloadPeriodService.Preview p){return new DeloadPeriodRequests.Start(0,UUID.randomUUID(),p.previewToken(),null,p.choice());}
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
        app.getBean(RoutineService.class).create(owner,new RoutineRequests.Create(id,0,"immutable source",List.of(w),List.of(new RoutineRequests.Cardio(RoutineRequests.Mode.NEW,UUID.randomUUID(),new RoutineRequests.Plan("TREADMILL","run",2100,null,"SPEED",new BigDecimal("8"),null,null)))));return id;
    }
    void schedule(UUID id,int... days) {
        var assignments=new ArrayList<WeeklySchedule.Assignment>();for(int d:days)assignments.add(new WeeklySchedule.Assignment(d,id));
        saveSourcePreset(assignments);
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,recorded_at,effective_from,time_zone,assignments) VALUES (?,0,1,?,?,'Asia/Seoul',?::jsonb)",owner,Timestamp.from(EmailOtpFixtures.CLOCK.instant()),monday,JSON.writeValueAsString(assignments));
        jdbc.update("INSERT INTO workout_calendar_control(user_id,data_generation,time_zone,baseline_version,review_from_date,reviewed_through) VALUES (?,0,'Asia/Seoul',1,?,?)",owner,monday,monday.minusDays(1));
    }
    void saveSourcePreset(List<WeeklySchedule.Assignment> assignments) {
        jdbc.update("UPDATE weekly_schedule_presets SET assignments=?::jsonb,preset_version=1 WHERE user_id=? AND data_generation=0",
                JSON.writeValueAsString(assignments),owner);
    }
    List<String> originals(){var out=new ArrayList<String>();for(String t:List.of("routines","routine_weight_placements","routine_cardio_placements","weekly_schedule_versions","workout_sessions"))out.addAll(jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+t+" t WHERE user_id=? ORDER BY to_jsonb(t)::text",String.class,owner));for(String t:List.of("performed_sets","cardio_results"))out.addAll(jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+t+" t JOIN workout_sessions ws ON ws.id=t.session_id WHERE ws.user_id=? ORDER BY to_jsonb(t)::text",String.class,owner));return out;}
    DeloadPeriodRepository.Stored begin(int... days) {weight(2,1,1);schedule(planRoutine(),days);return start(new Choice(Method.KEEP,Split.ORIGINAL,List.of())).period();}
    DeloadPeriodRepository.Stored current(){return service().current(owner).period();}
    DeloadPeriodRepository.SavedPlan todayPlan(){return current().plans().stream().filter(p->p.plannedDate().equals(LocalDate.of(2026,11,8))).findFirst().orElseThrow();}
    Map<String,Object> startBody(DeloadPeriodRepository.SavedPlan plan,UUID session,UUID device){return Map.of("id",session,"dataGeneration","0","planId",plan.id(),"expectedPlanVersion",plan.version(),"editorDeviceId",device,"startedAt",EmailOtpFixtures.CLOCK.instant().toString(),"workoutDate",EmailOtpFixtures.CLOCK.instant().atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString(),"startedTimeZone","Asia/Seoul");}
    JsonNode session(DeloadPeriodRepository.SavedPlan plan)throws Exception {var response=browser.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(startBody(plan,UUID.randomUUID(),UUID.randomUUID())),browser.csrf());assertThat(response.statusCode()).as(response.body()).isEqualTo(201);return JSON.readTree(response.body());}
    WorkoutSnapshotData.State state(JsonNode session){return JSON.treeToValue(session.get("snapshot").get("state"),WorkoutSnapshotData.State.class);}
    WorkoutSnapshotData.State withSets(JsonNode session,int count){var base=state(session);var initial=JSON.treeToValue(session.get("initialPlan"),InitialPlan.class);var w=initial.weightExercises().getFirst();var sets=new ArrayList<WorkoutSnapshotData.SetValue>();for(int i=1;i<=count;i++)sets.add(new WorkoutSnapshotData.SetValue(UUID.randomUUID(),w.sessionExerciseId(),i,w.initialWeightKg(),8,Instant.parse(session.get("startedAt").asString()).plusSeconds(60),new WorkoutSnapshotData.Context("TODAY_ONLY",null),w.manufacturerId(),w.loadType()));return new WorkoutSnapshotData.State(base.sessionExercises(),base.appliedPlan(),sets,List.of(),List.of(),List.of(),List.of());}
    Map<String,Object> saveBody(JsonNode session,long revision,WorkoutSnapshotData.State state){return new LinkedHashMap<>(Map.of("dataGeneration","0","editorDeviceId",session.get("editorDeviceId").asString(),"writerEpoch",session.get("writerEpoch").asString(),"localRevision",Long.toString(revision),"snapshot",state));}
    HttpResponse<String> save(JsonNode session,long revision,WorkoutSnapshotData.State state)throws Exception {return browser.send("PUT","/api/v1/workout-sessions/"+session.get("id").asString()+"/snapshot",JSON.writeValueAsString(saveBody(session,revision,state)),browser.csrf());}
    JsonNode finish(JsonNode session,long revision,WorkoutSnapshotData.State state)throws Exception {clock("2026-11-08T03:03:00Z");var body=saveBody(session,revision,state);body.put("endedAt",EmailOtpFixtures.CLOCK.instant());return ok(browser.send("POST","/api/v1/workout-sessions/"+session.get("id").asString()+"/finish",JSON.writeValueAsString(body),browser.csrf()));}
    JsonNode detail(JsonNode s)throws Exception{return ok(browser.get("/api/v1/workout-sessions/"+s.get("id").asString()));}
    Map<String,Object> command(){return new LinkedHashMap<>(Map.of("dataGeneration","0","expectedVersion",current().version(),"requestId",UUID.randomUUID()));}
    JsonNode move(DeloadPeriodRepository.SavedPlan plan,LocalDate date,UUID swap)throws Exception {var body=command();body.put("date",date);body.put("swapPlanId",swap);return ok(browser.send("PUT",PATH+"/"+current().id()+"/plans/"+plan.id()+"/date",JSON.writeValueAsString(body),browser.csrf()));}
    JsonNode abandon(JsonNode session)throws Exception {var s=detail(session);return ok(browser.send("POST","/api/v1/workout-sessions/"+s.get("id").asString()+"/abandon",JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion",s.get("version").asString(),"editorDeviceId",s.get("editorDeviceId").asString(),"writerEpoch",s.get("writerEpoch").asString(),"localRevision",s.get("localRevision").asString(),"confirm",true)),browser.csrf()));}
    @Test void deloadStartHasNoFakeNormalRoutineAndSharesSingleActiveAndIdempotentStart()throws Exception {
        begin(7,1);var p=todayPlan();var body=startBody(p,UUID.randomUUID(),UUID.randomUUID());var first=browser.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(body),browser.csrf());assertThat(first.statusCode()).as(first.body()).isEqualTo(201);var s=JSON.readTree(first.body());
        assertThat(s.get("initialPlan").get("routineId").isNull()).isTrue();assertThat(s.get("deloadPeriodId").asString()).isEqualTo(current().id().toString());assertThat(s.get("initialPlan").get("deloadOrigin").get("initialSource").get("weights").get(0).get("sourceRoutineId").asString()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT source_routine_id FROM workout_sessions WHERE id=?",UUID.class,UUID.fromString(s.get("id").asString()))).isNull();
        assertThat(ok(browser.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(body),browser.csrf()))).isEqualTo(s);
        var again=startBody(p,UUID.randomUUID(),UUID.randomUUID());assertThat(browser.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(again),browser.csrf()).statusCode()).isEqualTo(409);
        assertThatThrownBy(()->jdbc.update("UPDATE workout_sessions SET deload_plan_id=? WHERE id=?",UUID.randomUUID(),UUID.fromString(s.get("id").asString()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void partialFinishKeepsFactsAndNeverApprovesOrEvaluatesNormalGrowth()throws Exception {
        begin(7);var s=session(todayPlan());var original=originals();var state=withSets(s,1);var ack=ok(save(s,1,state));assertThat(ok(save(s,1,state)).get("duplicate").asBoolean()).isTrue();assertThat(ack.get("acceptedLocalRevision").asString()).isEqualTo("1");
        var result=finish(s,2,state);assertThat(result.get("completionAssessment").get("overallWeightPlanComplete").isNull()).isTrue();assertThat(result.get("completionAssessment").get("exercises").get(0).get("reasonCodes").toString()).contains("DELOAD_SESSION");
        var assessment=ok(browser.get("/api/v1/workout-sessions/"+s.get("id").asString()+"/assessment"));assertThat(assessment.get("currentAssessment").get("state").asString()).isEqualTo("EXCLUDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM progress_change_events WHERE session_id=?",Integer.class,UUID.fromString(s.get("id").asString()))).isZero();assertThat(current().hasActivity()).isTrue();assertThat(original).isNotEmpty();
        assertThat(ok(browser.get("/api/v1/workout-sessions/deload/"+current().id())).get("sessions").size()).isEqualTo(1);
    }
    @Test void deletedSetAndSessionCannotErasePermanentActivityAndRecoveryCannotApprove()throws Exception {
        begin(7);var s=session(todayPlan());var state=withSets(s,1);finish(s,1,state);var currentSession=detail(s);var id=UUID.fromString(s.get("id").asString());
        var patch=new LinkedHashMap<String,Object>();patch.put("dataGeneration","0");patch.put("expectedVersion",currentSession.get("version").asString());patch.put("setCorrections",List.of());patch.put("cardioCorrections",List.of());patch.put("deletedPerformedSetIds",List.of(state.performedSets().getFirst().id()));patch.put("deletedCardioResultIds",List.of());
        ok(browser.send("PATCH","/api/v1/workout-sessions/"+id+"/records",JSON.writeValueAsString(patch),browser.csrf()));assertThat(current().hasActivity()).isTrue();currentSession=detail(s);
        ok(browser.send("DELETE","/api/v1/workout-sessions/"+id,JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion",currentSession.get("version").asString())),browser.csrf()));assertThat(current().hasActivity()).isTrue();
        assertThat(jdbc.queryForObject("SELECT first_deload_activity_at FROM workout_sessions WHERE id=?",Timestamp.class,id)).isNotNull();assertThatThrownBy(()->jdbc.update("UPDATE workout_sessions SET first_deload_activity_at=NULL WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var change=command();change.put("method","REST");change.put("split","REST");change.put("dates",List.of());assertThat(browser.send("PUT",PATH+"/"+current().id()+"/method",JSON.writeValueAsString(change),browser.csrf()).statusCode()).isEqualTo(409);
    }
    @Test void emptyStartCanBeExplicitlyAbandonedWithoutHistoryOrLossOfInitialPlan()throws Exception {
        begin(7);var plan=todayPlan();var s=session(plan);var initial=s.get("initialPlan");var body=Map.of("dataGeneration","0","expectedVersion",s.get("version").asString(),"editorDeviceId",s.get("editorDeviceId").asString(),"writerEpoch","0","localRevision","0","confirm",true);
        var path="/api/v1/workout-sessions/"+s.get("id").asString()+"/abandon";var first=ok(browser.send("POST",path,JSON.writeValueAsString(body),browser.csrf()));assertThat(ok(browser.send("POST",path,JSON.writeValueAsString(body),browser.csrf()))).isEqualTo(first);assertThat(current().hasActivity()).isFalse();
        assertThat(ok(browser.get("/api/v1/workout-sessions/deload/"+current().id())).get("sessions").size()).isZero();assertThat(detail(s).get("initialPlan")).isEqualTo(initial);assertThat(session(plan).get("status").asString()).isEqualTo("ACTIVE");
    }
    @Test void dateSwapMoveAndDeleteAreAtomicVersionedAndLeavePeriodActive()throws Exception {
        begin(7,1,3);var before=current();var a=todayPlan();var b=before.plans().stream().filter(p->p.plannedDate().equals(LocalDate.of(2026,11,9))).findFirst().orElseThrow();move(a,b.plannedDate(),b.id());
        assertThat(current().plans().stream().filter(p->p.id().equals(b.id())).findFirst().orElseThrow().plannedDate()).isEqualTo(a.plannedDate());
        move(current().plans().stream().filter(p->p.id().equals(a.id())).findFirst().orElseThrow(),LocalDate.of(2026,11,17),null);
        for(var p:new ArrayList<>(current().plans()))ok(browser.send("DELETE",PATH+"/"+current().id()+"/plans/"+p.id(),JSON.writeValueAsString(command()),browser.csrf()));
        assertThat(current().plans()).isEmpty();assertThat(current().status()).isEqualTo("ACTIVE");assertThat(before.source()).isEqualTo(current().source());
    }
    @Test void dayEightEditsAndMovesAreAllowedButNewGenerationAndDayElevenAreNot()throws Exception {
        begin(7,1);var plan=todayPlan();move(plan,LocalDate.of(2026,11,16),null);clock("2026-11-15T03:00:00Z");
        var p=current().plans().stream().filter(v->v.id().equals(plan.id())).findFirst().orElseThrow();move(p,LocalDate.of(2026,11,17),null);
        var bad=command();bad.put("date","2026-11-18");bad.put("swapPlanId",null);assertThat(browser.send("PUT",PATH+"/"+current().id()+"/plans/"+p.id()+"/date",JSON.writeValueAsString(bad),browser.csrf()).statusCode()).isEqualTo(409);
        var method=command();method.put("method","KEEP");method.put("split","ORIGINAL");method.put("dates",List.of());assertThat(browser.send("PUT",PATH+"/"+current().id()+"/method",JSON.writeValueAsString(method),browser.csrf()).statusCode()).isEqualTo(409);
        method.put("method","REST");method.put("split","REST");ok(browser.send("PUT",PATH+"/"+current().id()+"/method",JSON.writeValueAsString(method),browser.csrf()));assertThat(current().plans()).isEmpty();assertThat(current().status()).isEqualTo("ACTIVE");
    }
    @Test void startedOrFinishedPlanCannotBeMovedEditedOrDeleted()throws Exception {
        begin(7,1);var plan=todayPlan();var s=session(plan);var command=command();
        assertThat(browser.send("DELETE",PATH+"/"+current().id()+"/plans/"+plan.id(),JSON.writeValueAsString(command),browser.csrf()).statusCode()).isEqualTo(409);
        finish(s,1,withSets(s,1));command=command();command.put("date","2026-11-09");command.put("swapPlanId",current().plans().getFirst().id());assertThat(browser.send("PUT",PATH+"/"+current().id()+"/plans/"+plan.id()+"/date",JSON.writeValueAsString(command),browser.csrf()).statusCode()).isEqualTo(409);
    }
    @Test void noActivityMethodChangeUsesSavedOriginalAndCannotAffectGeneralSources()throws Exception {
        var p=begin(7,1,3,4);var source=p.source();var original=originals();var body=command();body.put("method","TWO");body.put("split","EQUAL");body.put("dates",List.of("2026-11-08","2026-11-11"));
        var response=ok(browser.send("PUT",PATH+"/"+p.id()+"/method",JSON.writeValueAsString(body),browser.csrf()));assertThat(ok(browser.send("PUT",PATH+"/"+p.id()+"/method",JSON.writeValueAsString(body),browser.csrf()))).isEqualTo(response);assertThat(current().currentMethod()).isEqualTo(Method.TWO);assertThat(current().source()).isEqualTo(source);assertThat(originals()).isEqualTo(original);
    }
    Map<String,Object> editBody(DeloadPeriodRepository.SavedPlan plan,String weight) {
        var body=command();var content=plan.current();body.put("name","edited temporary");var items=new ArrayList<Map<String,Object>>();for(var w:content.weights()){var m=new LinkedHashMap<String,Object>();m.put("id",w.id());m.put("standardExerciseId",w.standardExerciseId());m.put("manufacturerId",w.manufacturerId());m.put("loadType",w.loadType());m.put("setCount",4);m.put("minReps",7);m.put("maxReps",11);m.put("initialWeightKg",weight);items.add(m);}body.put("weightPlacements",items);body.put("cardioPlacements",content.cardio().stream().map(c->Map.of("id",c.id(),"plan",c.plan())).toList());return body;
    }
    @Test void editingCurrentGoalsNeverReappliesReductionOrChangesInitialSource()throws Exception {
        var period=begin(7);var p=todayPlan();var source=p.plan();var body=editBody(p,"57");var path=PATH+"/"+period.id()+"/plans/"+p.id();var first=ok(browser.send("PUT",path,JSON.writeValueAsString(body),browser.csrf()));assertThat(ok(browser.send("PUT",path,JSON.writeValueAsString(body),browser.csrf()))).isEqualTo(first);
        p=todayPlan();assertThat(p.current().weights().getFirst().initialWeightKg()).isEqualTo("57");assertThat(p.current().weights().getFirst().setCount()).isEqualTo(4);assertThat(p.plan()).isEqualTo(source);var s=session(p);assertThat(s.get("initialPlan").get("weightExercises").get(0).get("initialWeightKg").asString()).isEqualTo("57");
        assertThat(browser.send("PUT",path,JSON.writeValueAsString(editBody(p,"60")),browser.csrf()).statusCode()).isEqualTo(409);assertThat(detail(s).get("initialPlan")).isEqualTo(s.get("initialPlan"));
    }
    @Test void positiveCardioEvenWithoutGoalCompletionPermanentlyPreventsAbandonment()throws Exception {
        begin(7);var s=session(todayPlan());var base=state(s);var at=Instant.parse(s.get("startedAt").asString());var result=new WorkoutSnapshotData.Cardio(UUID.randomUUID(),base.appliedPlan().cardioActivities().getFirst().sessionActivityId(),"COMPLETED",at.plusSeconds(60),at.plusSeconds(120),new WorkoutSnapshotData.CardioValue(60000L,60000L,false,null,null));
        var state=new WorkoutSnapshotData.State(base.sessionExercises(),base.appliedPlan(),List.of(),List.of(result),List.of(),List.of(),List.of());finish(s,1,state);assertThat(current().hasActivity()).isTrue();var id=UUID.fromString(s.get("id").asString());var marker=jdbc.queryForObject("SELECT first_deload_activity_at FROM workout_sessions WHERE id=?",Timestamp.class,id);
        var patch=Map.of("dataGeneration","0","expectedVersion",detail(s).get("version").asString(),"setCorrections",List.of(),"cardioCorrections",List.of(),"deletedPerformedSetIds",List.of(),"deletedCardioResultIds",List.of(result.id()));ok(browser.send("PATCH","/api/v1/workout-sessions/"+id+"/records",JSON.writeValueAsString(patch),browser.csrf()));
        assertThat(current().hasActivity()).isTrue();assertThat(jdbc.queryForObject("SELECT first_deload_activity_at FROM workout_sessions WHERE id=?",Timestamp.class,id)).isEqualTo(marker);
        var latest=detail(s);var abandon=Map.of("dataGeneration","0","expectedVersion",latest.get("version").asString(),"editorDeviceId",latest.get("editorDeviceId").asString(),"writerEpoch",latest.get("writerEpoch").asString(),"localRevision",latest.get("localRevision").asString(),"confirm",true);assertThat(browser.send("POST","/api/v1/workout-sessions/"+id+"/abandon",JSON.writeValueAsString(abandon),browser.csrf()).statusCode()).isEqualTo(409);
    }
    @Test void recoveryAndCurrentAssessmentRemainExcludedFromApprovalAndGrowth()throws Exception {
        begin(7);var s=session(todayPlan());var initial=state(s);finish(s,1,initial);var id=s.get("id").asString();var v=withSets(s,1).performedSets().getFirst();
        var recovery=new LinkedHashMap<String,Object>();recovery.put("dataGeneration","0");recovery.put("expectedVersion",detail(s).get("version").asString());recovery.put("confirm",true);recovery.put("origin",Map.of("editorDeviceId",s.get("editorDeviceId").asString(),"writerEpoch","0","localRevision","1"));recovery.put("cardioResults",List.of());
        var forbidden=new WorkoutSnapshotData.SetValue(v.id(),v.sessionExerciseId(),v.ordinal(),v.weightKg(),v.reps(),v.completedAt(),new WorkoutSnapshotData.Context("INITIAL_APPROVED",null),v.manufacturerId(),v.loadType());recovery.put("performedSets",List.of(forbidden));assertThat(browser.send("POST","/api/v1/workout-sessions/"+id+"/recoveries",JSON.writeValueAsString(recovery),browser.csrf()).statusCode()).isEqualTo(409);assertThat(current().hasActivity()).isFalse();
        recovery.put("performedSets",List.of(v));ok(browser.send("POST","/api/v1/workout-sessions/"+id+"/recoveries",JSON.writeValueAsString(recovery),browser.csrf()));assertThat(current().hasActivity()).isTrue();assertThat(ok(browser.get("/api/v1/workout-sessions/"+id+"/assessment")).get("currentAssessment").get("state").asString()).isEqualTo("EXCLUDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM progress_change_events WHERE session_id=?",Integer.class,UUID.fromString(id))).isZero();
    }
    @Test void actualDeloadVolumeAndMusclesAreIncludedWhileGeneralPlanningAndG4CandidatesExcludeIt()throws Exception {
        begin(7);var url="/api/v1/statistics/overview?fromDate=2026-11-02&toDateExclusive=2026-11-09";var before=ok(browser.get(url));var s=session(todayPlan());finish(s,1,withSets(s,1));var after=ok(browser.get(url));
        assertThat(new BigDecimal(after.get("statistics").get("summary").get("volumeLoadKg").asString()).subtract(new BigDecimal(before.get("statistics").get("summary").get("volumeLoadKg").asString()))).isEqualByComparingTo("400");assertThat(after.get("statistics").get("muscles").size()).isGreaterThan(0);
        var facts=app.getBean(com.overload.overload_server.statistics.StatisticsRepository.class).completionFacts(owner,0,monday,monday.plusDays(7),from,null);assertThat(facts).noneMatch(f->f.id().toString().equals(s.get("id").asString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_training_assessments WHERE user_id=? AND first_session_id=?",Integer.class,owner,UUID.fromString(s.get("id").asString()))).isZero();
    }
    @Test void snapshotFailureRollsBackBothActualMarkerAndPartialWrites()throws Exception {
        begin(7);var s=session(todayPlan());var valid=withSets(s,1);var invalid=saveBody(s,1,valid);invalid.put("endedAt",Instant.parse(s.get("startedAt").asString()).plusSeconds(30));
        assertThat(browser.send("POST","/api/v1/workout-sessions/"+s.get("id").asString()+"/finish",JSON.writeValueAsString(invalid),browser.csrf()).statusCode()).isEqualTo(422);assertThat(current().hasActivity()).isFalse();assertThat(jdbc.queryForObject("SELECT count(*) FROM performed_sets WHERE session_id=?",Integer.class,UUID.fromString(s.get("id").asString()))).isZero();assertThat(detail(s).get("localRevision").asString()).isEqualTo("0");
        ok(save(s,1,valid));assertThat(current().hasActivity()).isTrue();
    }
    @Test void approvalsAreRejectedAtSnapshotClosedResolutionAndDatabaseBoundaries()throws Exception {
        begin(7);var s=session(todayPlan());var base=withSets(s,1);var approval=new WorkoutSnapshotData.Approval(UUID.randomUUID(),base.performedSets().getFirst().sessionExerciseId(),1,null,0,"50","60",Instant.parse(s.get("startedAt").asString()));var state=new WorkoutSnapshotData.State(base.sessionExercises(),base.appliedPlan(),base.performedSets(),List.of(),List.of(),List.of(),List.of(approval));assertThat(save(s,1,state).statusCode()).isEqualTo(409);assertThat(current().hasActivity()).isFalse();
        finish(s,1,base);var body=new LinkedHashMap<String,Object>(Map.of("dataGeneration","0","expectedVersion",detail(s).get("version").asString(),"sessionExerciseId",approval.sessionExerciseId(),"pendingRequestIds",List.of(approval.id()),"decision","KEEP_CURRENT","occurredAt","2026-11-08T03:03:00Z"));body.put("newApprovalRequest",null);var rejected=browser.send("POST","/api/v1/workout-sessions/"+s.get("id").asString()+"/closed-approval-resolutions",JSON.writeValueAsString(body),browser.csrf());assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);assertThat(JSON.readTree(rejected.body()).get("code").asString()).isEqualTo("DELOAD_APPROVAL_FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM session_weight_approval_requests WHERE session_id=?",Integer.class,UUID.fromString(s.get("id").asString()))).isZero();
        assertThatThrownBy(()->jdbc.update("INSERT INTO session_weight_approval_requests(id,session_id,session_exercise_id,sequence,expected_routine_version,previous_weight,next_weight,occurred_at,status,reason) VALUES (?,?,?,1,0,50,60,now(),'PENDING','FIXTURE')",UUID.randomUUID(),UUID.fromString(s.get("id").asString()),approval.sessionExerciseId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void staleAndForeignPlanVersionsCannotStartAndMidnightChangesTheScheduledDate()throws Exception {
        begin(7,1);var p=todayPlan();var body=new LinkedHashMap<>(startBody(p,UUID.randomUUID(),UUID.randomUUID()));body.put("expectedPlanVersion","9");assertThat(browser.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(body),browser.csrf()).statusCode()).isEqualTo(409);
        body.put("expectedPlanVersion",p.version());body.put("dataGeneration","1");assertThat(browser.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(body),browser.csrf()).statusCode()).isEqualTo(409);
        try(var other=new Browser()){other.login();assertThat(other.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(startBody(p,UUID.randomUUID(),UUID.randomUUID())),other.csrf()).statusCode()).isEqualTo(409);assertThat(other.get("/api/v1/workout-sessions/deload/"+current().id()).statusCode()).isEqualTo(404);}
        clock("2026-11-08T15:00:00Z");body=new LinkedHashMap<>(startBody(p,UUID.randomUUID(),UUID.randomUUID()));assertThat(browser.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(body),browser.csrf()).statusCode()).isEqualTo(409);
    }
    @Test void startedSnapshotAndPlanEditRacesNeverChangeTheFrozenSessionPlan()throws Exception {
        begin(7);var p=todayPlan();var version=p.version();var gate=new CountDownLatch(1);var body=editBody(p,"57");var token=browser.csrf();var startBody=startBody(p,UUID.randomUUID(),UUID.randomUUID());
        try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{gate.await();return browser.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(startBody),token);});var b=pool.submit(()->{gate.await();return browser.send("PUT",PATH+"/"+current().id()+"/plans/"+p.id(),JSON.writeValueAsString(body),token);});gate.countDown();var started=a.get(15,TimeUnit.SECONDS);var edited=b.get(15,TimeUnit.SECONDS);assertThat(started.statusCode()).as(started.body()).isIn(201,409);assertThat(edited.statusCode()).as(edited.body()).isIn(200,409);assertThat(started.statusCode()==201).isNotEqualTo(edited.statusCode()==200);if(started.statusCode()==201){var s=JSON.readTree(started.body());ok(save(s,1,withSets(s,1)));assertThat(detail(s).get("initialPlan")).isEqualTo(s.get("initialPlan"));}else assertThat(todayPlan().version()).isNotEqualTo(version);}
    }
    @Test void compressedSessionSealsSeveralRealRoutineOriginsWithoutCreatingFakeRoutines()throws Exception {
        weight(2,1,1);var routines=List.of(planRoutine(),planRoutine(),planRoutine(),planRoutine());var assignments=new ArrayList<WeeklySchedule.Assignment>();int[] days={1,2,3,7};for(int i=0;i<4;i++)assignments.add(new WeeklySchedule.Assignment(days[i],routines.get(i)));
        saveSourcePreset(assignments);
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,recorded_at,effective_from,time_zone,assignments) VALUES (?,0,1,?,?,'Asia/Seoul',?::jsonb)",owner,Timestamp.from(EmailOtpFixtures.CLOCK.instant()),monday,JSON.writeValueAsString(assignments));jdbc.update("INSERT INTO workout_calendar_control VALUES (?,0,'Asia/Seoul',1,?,?)",owner,monday,monday.minusDays(1));
        var count=jdbc.queryForObject("SELECT count(*) FROM routines WHERE user_id=?",Integer.class,owner);start(new Choice(Method.TWO,Split.EQUAL,List.of()));var s=session(todayPlan());var origins=s.get("initialPlan").get("deloadOrigin").get("initialSource").get("weights");var ids=new HashSet<String>();origins.forEach(w->ids.add(w.get("sourceRoutineId").asString()));assertThat(ids).hasSize(2);assertThat(jdbc.queryForObject("SELECT count(*) FROM routines WHERE user_id=?",Integer.class,owner)).isEqualTo(count);assertThat(s.get("initialPlan").get("routineId").isNull()).isTrue();
    }
    @Test void autoCloseAndTakeoverReuseWriterEpochAndPermitExplicitOfflineRecovery()throws Exception {
        begin(7);var s=session(todayPlan());var oldState=withSets(s,1);var newDevice=UUID.randomUUID();var takeover=ok(browser.send("POST","/api/v1/workout-sessions/"+s.get("id").asString()+"/takeover",JSON.writeValueAsString(Map.of("dataGeneration","0","editorDeviceId",newDevice,"expectedWriterEpoch","0")),browser.csrf()));assertThat(takeover.get("writerEpoch").asString()).isEqualTo("1");assertThat(save(s,1,oldState).statusCode()).isEqualTo(409);ok(save(takeover,1,withSets(takeover,1)));
        clock("2026-11-09T04:00:00Z");var closed=detail(takeover);assertThat(closed.get("status").asString()).isEqualTo("AUTO_CLOSED");var base=withSets(takeover,1).performedSets().getFirst();var second=new WorkoutSnapshotData.SetValue(UUID.randomUUID(),base.sessionExerciseId(),2,base.weightKg(),base.reps(),Instant.parse("2026-11-08T03:02:00Z"),base.weightContext(),base.manufacturerId(),base.loadType());
        var body=Map.of("dataGeneration","0","expectedVersion",closed.get("version").asString(),"confirm",true,"origin",Map.of("editorDeviceId",newDevice,"writerEpoch","1","localRevision","2"),"performedSets",List.of(second),"cardioResults",List.of());ok(browser.send("POST","/api/v1/workout-sessions/"+s.get("id").asString()+"/recoveries",JSON.writeValueAsString(body),browser.csrf()));assertThat(detail(s).get("status").asString()).isEqualTo("AUTO_CLOSED");assertThat(current().hasActivity()).isTrue();
    }
    @Test void startAndPlanCommandsEnforceAuthenticationCsrfStrictFieldsAndGeneration()throws Exception {
        begin(7);var plan=todayPlan();var body=startBody(plan,UUID.randomUUID(),UUID.randomUUID());try(var fresh=new Browser()){assertThat(fresh.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(body),fresh.csrf()).statusCode()).isEqualTo(401);}
        assertThat(browser.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(body),null).statusCode()).isEqualTo(403);
        var bad=new LinkedHashMap<>(body);bad.put("periodId",current().id());assertThat(browser.send("POST","/api/v1/workout-sessions/deload",JSON.writeValueAsString(bad),browser.csrf()).statusCode()).isEqualTo(400);assertThat(browser.send("POST","/api/v1/workout-sessions/deload?userId="+owner,JSON.writeValueAsString(body),browser.csrf()).statusCode()).isEqualTo(400);
        var delete=command();delete.put("dataGeneration","1");assertThat(browser.send("DELETE",PATH+"/"+current().id()+"/plans/"+plan.id(),JSON.writeValueAsString(delete),browser.csrf()).statusCode()).isEqualTo(409);
    }
    @Test void swapReplayDoesNotSwapBackAndOldVersionCannotOverwriteLaterChanges()throws Exception {
        begin(7,1);var a=todayPlan();var b=current().plans().stream().filter(p->!p.id().equals(a.id())).findFirst().orElseThrow();var body=command();body.put("date",b.plannedDate());body.put("swapPlanId",b.id());var path=PATH+"/"+current().id()+"/plans/"+a.id()+"/date";var first=ok(browser.send("PUT",path,JSON.writeValueAsString(body),browser.csrf()));assertThat(ok(browser.send("PUT",path,JSON.writeValueAsString(body),browser.csrf()))).isEqualTo(first);
        var latest=current().plans().stream().filter(p->p.id().equals(a.id())).findFirst().orElseThrow();move(latest,LocalDate.of(2026,11,10),null);assertThat(browser.send("PUT",path,JSON.writeValueAsString(body),browser.csrf()).statusCode()).isEqualTo(409);
    }
    @Test void anEmptyFinishedSessionCanBeAbandonedWithoutChangingSealedFinishFacts()throws Exception {
        begin(7);var s=session(todayPlan());finish(s,1,state(s));var sealed=detail(s);abandon(s);var after=detail(s);for(String field:List.of("initialPlan","endedAt","closedAt","completionAssessment","status"))assertThat(after.get(field)).as(field).isEqualTo(sealed.get(field));assertThat(current().hasActivity()).isFalse();
    }
    @Test void methodReplacementMustFitAllRequestedSlotsInTheRemainingFirstSevenDays()throws Exception {
        begin(7,1,3,4);clock("2026-11-13T03:00:00Z");var body=command();body.put("method","THREE");body.put("split","EQUAL");body.put("dates",List.of("2026-11-13","2026-11-14","2026-11-15"));var path=PATH+"/"+current().id()+"/method";
        var rejected=browser.send("PUT",path,JSON.writeValueAsString(body),browser.csrf());assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(409);assertThat(JSON.readTree(rejected.body()).get("code").asString()).isEqualTo("DELOAD_INSUFFICIENT_DATES");
        body.put("method","TWO");body.put("dates",List.of("2026-11-13","2026-11-14"));ok(browser.send("PUT",path,JSON.writeValueAsString(body),browser.csrf()));assertThat(current().plans()).allSatisfy(p->assertThat(p.plannedDate()).isBetween(LocalDate.of(2026,11,13),LocalDate.of(2026,11,14)));
    }
    private DeloadPeriodLifecycle lifecycle() {
        return app.getBean(DeloadPeriodLifecycle.class);
    }
    private HttpResponse<String> end(DeloadPeriodRepository.Stored period, String operation) throws Exception {
        var body=Map.of("dataGeneration","0","expectedVersion",period.version(),"requestId",UUID.randomUUID());
        return browser.send("POST",PATH+"/"+period.id()+"/"+operation,JSON.writeValueAsString(body),browser.csrf());
    }
    private void rejected(HttpResponse<String> response,String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo(code);
    }

    @Test void noActivityCancellationRequiresExplicitAbandonAndImmediatelyAllowsNormalStart() throws Exception {
        begin(7);
        var period=current();
        var workout=session(todayPlan());
        rejected(end(period,"cancel"),"ACTIVE_SESSION_EXISTS");
        rejected(end(period,"complete"),"ACTIVE_SESSION_EXISTS");
        finish(workout,1,state(workout));
        rejected(end(period,"cancel"),"DELOAD_SESSION_ABANDON_REQUIRED");
        abandon(workout);
        assertThat(ok(end(period,"cancel")).get("period").get("status").asString()).isEqualTo("CANCELLED");
        assertThat(detail(workout).get("initialPlan")).isEqualTo(workout.get("initialPlan"));
        var routine=period.source().days().getFirst().routine().id();
        var body=Map.of("id",UUID.randomUUID(),"dataGeneration","0","routineId",routine,"editorDeviceId",UUID.randomUUID(),
            "startedAt",EmailOtpFixtures.CLOCK.instant().toString(),"workoutDate","2026-11-08","startedTimeZone","Asia/Seoul");
        var normal=browser.send("POST","/api/v1/workout-sessions",JSON.writeValueAsString(body),browser.csrf());
        assertThat(normal.statusCode()).as(normal.body()).isEqualTo(201);
    }

    @Test void cancellationRejectsWeightEvenAfterLogicalDeletionAndCompletionRetainsSealedOrigin() throws Exception {
        begin(7);
        var period=current();
        var workout=session(todayPlan());
        var performed=withSets(workout,1);
        finish(workout,1,performed);
        rejected(end(period,"cancel"),"DELOAD_ACTIVITY_RECORDED");
        var latest=detail(workout);
        var workoutId=UUID.fromString(workout.get("id").asString());
        var delete=JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion",latest.get("version").asString()));
        ok(browser.send("DELETE","/api/v1/workout-sessions/"+workoutId,delete,browser.csrf()));
        rejected(end(period,"cancel"),"DELOAD_ACTIVITY_RECORDED");
        var before=originals();
        var completed=ok(end(period,"complete")).get("period");
        assertThat(completed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(completed.get("hasActivity").asBoolean()).isTrue();
        assertThat(completed.get("sourceHash").asString()).isEqualTo(period.sourceHash());
        assertThat(originals()).isEqualTo(before);
        assertThat(detail(workout).get("initialPlan")).isEqualTo(workout.get("initialPlan"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM progress_change_events WHERE session_id=?",Integer.class,workoutId)).isZero();
    }

    @Test void cancellationRejectsPositiveCardioEvenWhenTargetWasNotMet() throws Exception {
        begin(7);
        var period=current();
        var workout=session(todayPlan());
        var base=state(workout);
        var at=Instant.parse(workout.get("startedAt").asString());
        var activity=new WorkoutSnapshotData.Cardio(UUID.randomUUID(),base.appliedPlan().cardioActivities().getFirst().sessionActivityId(),
            "COMPLETED",at.plusSeconds(60),at.plusSeconds(120),new WorkoutSnapshotData.CardioValue(60000L,60000L,false,null,null));
        finish(workout,1,new WorkoutSnapshotData.State(base.sessionExercises(),base.appliedPlan(),List.of(),List.of(activity),
            List.of(),List.of(),List.of()));
        rejected(end(period,"cancel"),"DELOAD_ACTIVITY_RECORDED");
        assertThat(current().hasActivity()).isTrue();
    }

    @Test void completedHistoryRetainsDeletedTemporaryPlanAndDoesNotAllowNewUse() throws Exception {
        begin(7);
        var plan=todayPlan();
        var delete=PATH+"/"+current().id()+"/plans/"+plan.id();
        ok(browser.send("DELETE",delete,JSON.writeValueAsString(command()),browser.csrf()));
        var active=current();
        assertThat(active.plans()).isEmpty();
        var completed=ok(end(active,"complete")).get("period");
        var historical=completed.get("plans").get(0);
        assertThat(historical.get("id").asString()).isEqualTo(plan.id().toString());
        assertThat(historical.get("deletedAt").asString()).isNotBlank();
        assertThat(historical.get("plan")).isEqualTo(JSON.valueToTree(plan.plan()));
        rejected(browser.send("DELETE",delete,JSON.writeValueAsString(command()),browser.csrf()),"DELOAD_PERIOD_NOT_ACTIVE");
    }

    @Test void dayTenSnapshotAndFinishAckSurviveConcurrentAutomaticCompletion() throws Exception {
        begin(7);
        var period=current();
        var plan=todayPlan();
        move(plan,period.maximumEndDate(),null);
        clock("2026-11-17T14:55:00Z");
        var workout=session(current().plans().stream().filter(p->p.id().equals(plan.id())).findFirst().orElseThrow());
        var facts=withSets(workout,1);
        clock("2026-11-17T15:00:00Z");
        var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var snapshot=pool.submit(()->{gate.await();return save(workout,1,facts);});
            var worker=pool.submit(()->{gate.await();return lifecycle().completeDuePeriods();});
            gate.countDown();
            assertThat(ok(snapshot.get(15,TimeUnit.SECONDS)).get("acceptedLocalRevision").asString()).isEqualTo("1");
            worker.get(15,TimeUnit.SECONDS);
        }
        assertThat(current().status()).isEqualTo("ACTIVE");
        assertThat(ok(save(workout,1,facts)).get("duplicate").asBoolean()).isTrue();
        assertThat(current().hasActivity()).isTrue();
        var body=saveBody(workout,2,facts);
        body.put("endedAt",EmailOtpFixtures.CLOCK.instant());
        var payload=JSON.writeValueAsString(body);
        var path="/api/v1/workout-sessions/"+workout.get("id").asString()+"/finish";
        var first=ok(browser.send("POST",path,payload,browser.csrf()));
        lifecycle().completeDuePeriods();
        assertThat(service().detail(owner,period.id()).period().endReason()).isEqualTo("AUTOMATIC_COMPLETION");
        var retry=ok(browser.send("POST",path,payload,browser.csrf()));
        assertThat(retry.get("duplicate").asBoolean()).isTrue();
        assertThat(retry.get("acceptedLocalRevision")).isEqualTo(first.get("acceptedLocalRevision"));
        assertThat(retry.get("completionAssessment")).isEqualTo(first.get("completionAssessment"));
        assertThat(detail(workout).get("initialPlan")).isEqualTo(workout.get("initialPlan"));
    }

    @Test void overdueActiveRemainsGatedAndLateRecoveryRemainsDeloadAfterSafeAutoClose() throws Exception {
        begin(7,3,4);
        var period=current();
        var plan=todayPlan();
        move(plan,period.maximumEndDate(),null);
        clock("2026-11-17T14:55:00Z");
        var workout=session(current().plans().stream().filter(p->p.id().equals(plan.id())).findFirst().orElseThrow());
        var facts=withSets(workout,1);
        clock("2026-11-19T16:00:00Z");
        var review=app.getBean(com.overload.overload_server.statistics.DailyTrainingReviewService.class);
        lifecycle().completeDuePeriods();
        review.reviewBatch();
        assertThat(current().status()).isEqualTo("ACTIVE");
        var expired=detail(workout);
        assertThat(expired.get("status").asString()).isEqualTo("AUTO_CLOSED");
        lifecycle().completeDuePeriods();
        var done=service().detail(owner,period.id()).period();
        assertThat(done.closedDate()).isEqualTo(LocalDate.of(2026,11,17));
        assertThat(done.processedAt()).isEqualTo(Instant.parse("2026-11-19T16:00:00Z"));
        var payload=Map.of("dataGeneration","0","expectedVersion",expired.get("version").asString(),"confirm",true,
            "origin",Map.of("editorDeviceId",workout.get("editorDeviceId").asString(),"writerEpoch","0","localRevision","1"),
            "performedSets",facts.performedSets(),"cardioResults",List.of());
        var path="/api/v1/workout-sessions/"+workout.get("id").asString()+"/recoveries";
        ok(browser.send("POST",path,JSON.writeValueAsString(payload),browser.csrf()));
        assertThat(service().detail(owner,period.id()).period().hasActivity()).isTrue();
        assertThat(detail(workout).get("deloadPeriodId").asString()).isEqualTo(period.id().toString());
        review.reviewBatch();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM daily_training_assessments WHERE user_id=? AND workout_date BETWEEN '2026-11-08' AND '2026-11-19'
              AND first_missed_at IS NOT NULL
            """,Integer.class,owner)).isZero();
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
