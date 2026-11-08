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

/** Real auth, record and read APIs use only this disposable PostgreSQL fixture. */
@Tag("postgres") @Testcontainers
class DeloadAnalysisQueryIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine")
        .withDatabaseName("overload_g4_query_isolated").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext app;static JdbcTemplate jdbc;static final JsonMapper JSON=JsonMapper.builder().build();
    final LocalDate monday=LocalDate.of(2026,10,26);final Instant from=Instant.parse("2026-10-25T15:00:00Z");
    UUID owner;DeloadWeeklyFinalization finalizer;Browser browser;
    record Session(UUID id,UUID routine,UUID item,List<UUID> sets) { }
    @BeforeAll static void start() {
        assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432,5433,12296);
        boot();
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
    @BeforeEach void setup(TestInfo test) throws Exception {
        WorkoutReadFixtures.observer=null;
        var repository=app.getBean(DeloadAnalysisRepository.class);
        for(var entry:jdbc.queryForList("SELECT DISTINCT v.user_id,v.data_generation FROM deload_analysis_versions v JOIN users u ON u.id=v.user_id AND u.data_generation=v.data_generation")) {
            UUID id=(UUID)entry.get("user_id");long generation=((Number)entry.get("data_generation")).longValue();var prior=repository.current(id,generation);
            if(prior.enabled())repository.append(id,generation,prior.change(false,prior.timeZone()==null?"Asia/Seoul":prior.timeZone(),prior.recordedAt().isAfter(Instant.parse("2026-11-01T19:00:00Z"))?prior.recordedAt():Instant.parse("2026-11-01T19:00:00Z")));
        }
        clock(test.getTestMethod().orElseThrow().getName().equals("initialBeforeFourDuePendingAndPublishedResetHaveDifferentMeanings")?"2026-11-01T18:59:59Z":"2026-11-01T19:00:00Z");jdbc.update("DELETE FROM email_otp_rate_limits");app.getBean(EmailOtpFixtures.FakeSender.class).reset();
        browser=new Browser();browser.login();owner=UUID.fromString(JSON.readTree(browser.get("/__otp_test__/me").body()).get("id").asString());
        jdbc.update("DELETE FROM deload_analysis_versions WHERE user_id=?",owner);jdbc.update("UPDATE users SET created_at='2026-01-01T00:00:00Z' WHERE id=?",owner);
        app.getBean(DeloadAnalysisService.class).adoptExisting(owner,0,from);append(owner,0,1,true,0,from,"TIME_ZONE_SET");finalizer=app.getBean(DeloadWeeklyFinalization.class);
    }
    @AfterEach void resetObserver() {WorkoutReadFixtures.observer=null;if(browser!=null)browser.close();}
    static void clock(String value) {EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(),Instant.parse(value)));}
    DeloadWeeklyRepository repo() {return app.getBean(DeloadWeeklyRepository.class);}
    DeloadWeeklyFinalization.Progress finish() {return finalizer.finalizeWeek(owner,0,0,monday);}
    List<DeloadWeeklyRepository.Row> weeklyRows() {return repo().rows(owner,0,0);}
    TransactionTemplate transaction() {var t=new TransactionTemplate(app.getBean("workoutTransactionManager",PlatformTransactionManager.class));t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return t;}

    void append(UUID id,long generation,long version,boolean enabled,long epoch,Instant at,String reason) {
        app.getBean(DeloadAnalysisRepository.class).append(id,generation,new DeloadAnalysisState(version,enabled,epoch,at,epoch==0?from:at,"Asia/Seoul",reason));
    }
    Instant at(int day) {return monday.plusDays(day).atTime(12,0).atZone(ZoneId.of("Asia/Seoul")).toInstant();}
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

    Map<String,Object> scope(Session s) {return new LinkedHashMap<>(Map.of("dataGeneration","0","expectedVersion",jdbc.queryForObject("SELECT version FROM workout_sessions WHERE id=?",Long.class,s.id()).toString()));}
    JsonNode edit(Session s,List<WorkoutRecordData.SetCorrection> sets,List<WorkoutRecordData.CardioCorrection> cardio,List<UUID> deletedSets,List<UUID> deletedCardio)throws Exception {
        var body=scope(s);body.put("setCorrections",sets);body.put("cardioCorrections",cardio);body.put("deletedPerformedSetIds",deletedSets);body.put("deletedCardioResultIds",deletedCardio);
        return ok(browser.send("PATCH","/api/v1/workout-sessions/"+s.id()+"/records",JSON.writeValueAsString(body),browser.csrf()));
    }
    JsonNode recover(Session s,List<WorkoutSnapshotData.SetValue> sets,List<WorkoutSnapshotData.Cardio> cardio)throws Exception {
        var body=scope(s);body.put("confirm",true);body.put("origin",Map.of("editorDeviceId",jdbc.queryForObject("SELECT started_device_id FROM workout_sessions WHERE id=?",UUID.class,s.id()),"writerEpoch","0","localRevision","0"));body.put("performedSets",sets);body.put("cardioResults",cardio);
        return ok(browser.send("POST","/api/v1/workout-sessions/"+s.id()+"/recoveries",JSON.writeValueAsString(body),browser.csrf()));
    }
    JsonNode ok(HttpResponse<String> response) {assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return JSON.readTree(response.body());}
    WorkoutSnapshotData.SetValue missing(Session s,int ordinal) {
        var start=jdbc.queryForObject("SELECT started_at FROM workout_sessions WHERE id=?",Timestamp.class,s.id()).toInstant();
        return new WorkoutSnapshotData.SetValue(UUID.randomUUID(),s.item(),ordinal,"60",10,start.plusSeconds(ordinal*60),new WorkoutSnapshotData.Context("TODAY_ONLY",null),null,null);
    }
    Session half(int day) {return seed(owner,0,day,at(day),1,1,"FINISHED",false,false,true);}
    WorkoutSnapshotData.SetValue other(Session s) {
        var id=jdbc.queryForObject("SELECT id FROM session_exercises WHERE session_id=? AND id<>?",UUID.class,s.id(),s.item());
        var v=missing(s,1);return new WorkoutSnapshotData.SetValue(v.id(),id,1,v.weightKg(),v.reps(),v.completedAt(),v.weightContext(),null,null);
    }
    void fullWeeks(int count) {for(int i=0;i<count;i++){weight(i*7,1,1);weight(i*7+1,1,1);}}
    void due(int count) {clock(monday.plusWeeks(count).atTime(4,0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toString());}
    static final String PATH="/api/v1/deload-analysis";
    JsonNode api()throws Exception {var response=browser.get(PATH);assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");return ok(response);}
    void fraction(JsonNode value,String field,int n,int d) {assertThat(value.get(field).get("numerator").asString()).isEqualTo(Integer.toString(n));assertThat(value.get(field).get("denominator").asString()).isEqualTo(Integer.toString(d));}
    void status(JsonNode value,String status) {assertThat(value.get("calculationStatus").asString()).isEqualTo(status);}
    void through(JsonNode value,LocalDate date) {assertThat(value.get("confirmedThrough").asString()).isEqualTo(date.toString());}
    @Test void newAccountDefaultsAndMissingTimeZoneRemainSeparateFromConfirmedZero()throws Exception {
        try(var fresh=new Browser()) {
            fresh.login();var response=fresh.get(PATH);var value=ok(response);assertThat(value.get("enabled").asBoolean()).isTrue();assertThat(value.get("version").asString()).isEqualTo("0");assertThat(value.get("epoch").asString()).isEqualTo("0");
            status(value,"AWAITING_TIME_ZONE");fraction(value,"epochInitialWeeks",0,1);fraction(value,"recommendationThreshold",8,1);
            assertThat(value.get("confirmedWeeks").isNull()).isTrue();assertThat(value.get("confirmedThrough").isNull()).isTrue();assertThat(value.get("recommendationReady").asBoolean()).isFalse();
        }
    }
    @Test void initialBeforeFourDuePendingAndPublishedResetHaveDifferentMeanings()throws Exception {
        clock("2026-11-01T18:59:59Z");var value=api();status(value,"INITIAL");assertThat(value.get("confirmedWeeks").isNull()).isTrue();assertThat(value.get("blockedAt").isNull()).isTrue();
        clock("2026-11-01T19:00:00Z");value=api();status(value,"PENDING");assertThat(value.get("confirmedWeeks").isNull()).isTrue();assertThat(value.get("blockedAt").asString()).isEqualTo(monday.toString());
        assertThat(finish().confirmed()).isEqualTo(1);value=api();status(value,"CONFIRMED");fraction(value,"confirmedWeeks",0,1);through(value,monday);assertThat(value.get("confirmedFrom").asString()).isEqualTo(monday.toString());
    }
    @Test void exactThreeQuartersMultipleWeeksAndResetUseActualUnscheduledRecords()throws Exception {
        weight(0,1,1);half(1);weight(7,1,1);weight(8,1,1);weight(14,1,1);assertThat(finish().confirmed()).isEqualTo(1);fraction(api(),"confirmedWeeks",3,4);
        due(2);assertThat(finalizer.runBatch()).isEqualTo(1);
        var value=api();status(value,"CONFIRMED");fraction(value,"confirmedWeeks",7,4);through(value,monday.plusWeeks(1));
        due(3);assertThat(finalizer.runBatch()).isEqualTo(1);value=api();fraction(value,"confirmedWeeks",0,1);through(value,monday.plusWeeks(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM weekly_schedule_versions WHERE user_id=?",Integer.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_calendar_control WHERE user_id=?",Integer.class,owner)).isZero();
    }
    @Test void recommendationRequiresEightExactConfirmedWeeksAndNeverClampsTheStoredTotal()throws Exception {
        fullWeeks(9);due(7);assertThat(finalizer.runBatch()).isEqualTo(4);assertThat(finalizer.runBatch()).isEqualTo(3);
        var value=api();fraction(value,"confirmedWeeks",7,1);assertThat(value.get("recommendationReady").asBoolean()).isFalse();
        due(8);assertThat(finalizer.runBatch()).isEqualTo(1);value=api();fraction(value,"confirmedWeeks",8,1);assertThat(value.get("recommendationReady").asBoolean()).isTrue();
        due(9);assertThat(finalizer.runBatch()).isEqualTo(1);value=api();fraction(value,"confirmedWeeks",9,1);assertThat(value.get("recommendationReady").asBoolean()).isTrue();
        due(10);value=api();status(value,"PENDING");fraction(value,"confirmedWeeks",9,1);assertThat(value.get("recommendationReady").asBoolean()).isFalse();
    }
    @Test void excludedTravelWeekPassesWithoutResetAndIsReevaluatedAfterRealRecovery()throws Exception {
        var early=seed(owner,0,-1,from,1,1,"FINISHED",false,false,false,"America/Los_Angeles");fullWeeks(2);due(2);assertThat(finalizer.runBatch()).isEqualTo(3);
        edit(early,List.of(),List.of(),List.of(early.sets().getFirst()),List.of());var blocked=api();status(blocked,"RECALCULATING");assertThat(blocked.get("confirmedWeeks").isNull()).isTrue();
        assertThat(finalizer.runBatch()).isEqualTo(3);var value=api();status(value,"CONFIRMED");fraction(value,"confirmedWeeks",2,1);through(value,monday.plusWeeks(1));
        assertThat(value.get("confirmedFrom").asString()).isEqualTo(monday.minusWeeks(1).toString());assertThat(weeklyRows().getFirst().status()).isEqualTo("EXCLUDED");
        recover(early,List.of(missing(early,1)),List.of());status(api(),"RECALCULATING");assertThat(finalizer.runBatch()).isEqualTo(3);fraction(api(),"confirmedWeeks",2,1);
    }
    @Test void dirtyRevisionIsDetectedBeforeJobAndUnknownStopsAtTrustedPrefix()throws Exception {
        fullWeeks(1);weight(7,1,1);var s=seed(owner,0,8,at(8),0,0,"FINISHED",true,false);cardio(s,"COMPLETED",100000,"1",false);weight(14,1,1);weight(15,1,1);due(3);assertThat(finalizer.runBatch()).isEqualTo(3);
        var id=jdbc.queryForObject("SELECT id FROM cardio_results WHERE session_id=?",UUID.class,s.id());edit(s,List.of(),List.of(new WorkoutRecordData.CardioCorrection(id,100000,true,null,null)),List.of(),List.of());
        assertThat(weeklyRows().get(1).dirty()).isFalse();var value=api();status(value,"RECALCULATING");fraction(value,"confirmedWeeks",1,1);through(value,monday);assertThat(value.get("blockedAt").asString()).isEqualTo(monday.plusWeeks(1).toString());
        assertThat(weeklyRows().get(1).dirty()).isFalse();assertThat(finalizer.runBatch()).isZero();value=api();status(value,"RECALCULATING");fraction(value,"confirmedWeeks",1,1);
    }
    @Test void pendingMiddleCannotExposeLaterStoredAdd()throws Exception {
        fullWeeks(1);weight(14,1,1);weight(15,1,1);seed(owner,0,8,at(8),1,0,"ACTIVE",false,false);due(3);assertThat(finalizer.runBatch()).isEqualTo(1);
        transaction().executeWithoutResult(tx->{repo().lock(owner,0,false);var date=monday.plusWeeks(2);var c=app.getBean(DeloadWeeklyCandidates.class).calculateLocked(owner,0,0,date,EmailOtpFixtures.CLOCK.instant());repo().reserve(owner,0,0,date,EmailOtpFixtures.CLOCK.instant());var row=weeklyRows().getLast();repo().confirm(owner,0,0,row,c,DeloadWeeklyPolicy.input(c),DeloadWeeklyPolicy.decide(c.e(),c.n()),EmailOtpFixtures.CLOCK.instant());});
        var value=api();status(value,"RECALCULATING");fraction(value,"confirmedWeeks",1,1);through(value,monday);assertThat(value.get("blockedAt").asString()).isEqualTo(monday.plusWeeks(1).toString());
    }
    void calendar() {
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,effective_from,time_zone,recorded_at,assignments) VALUES (?,0,1,?,'Asia/Seoul',?,'[]')",owner,monday,Timestamp.from(from));
        jdbc.update("INSERT INTO workout_calendar_control VALUES (?,0,'Asia/Seoul',1,?,?)",owner,monday,monday.minusDays(1));
    }
    @Test void uninspectedG3BoundaryIsAwaitingReviewWithoutMovingItsCursor()throws Exception {
        fullWeeks(1);calendar();var value=api();status(value,"AWAITING_REVIEW");assertThat(value.get("confirmedWeeks").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT reviewed_through FROM workout_calendar_control WHERE user_id=?",LocalDate.class,owner)).isEqualTo(monday.minusDays(1));
        app.getBean(com.overload.overload_server.statistics.DailyTrainingReviewService.class).reviewBatch();status(api(),"PENDING");assertThat(finish().confirmed()).isEqualTo(1);fraction(api(),"confirmedWeeks",1,1);
    }
    @Test void fourPointSevenFiveHistoricalRepairIsReturnedThroughActualGet()throws Exception {
        fullWeeks(3);var s=half(21);half(22);weight(28,1,1);weight(29,1,1);due(5);assertThat(finalizer.runBatch()).isEqualTo(4);assertThat(finalizer.runBatch()).isEqualTo(1);fraction(api(),"confirmedWeeks",1,1);
        recover(s,List.of(other(s)),List.of());var value=api();status(value,"RECALCULATING");fraction(value,"confirmedWeeks",3,1);through(value,monday.plusWeeks(2));
        assertThat(finalizer.runBatch()).isEqualTo(2);value=api();status(value,"CONFIRMED");fraction(value,"confirmedWeeks",19,4);through(value,monday.plusWeeks(4));
    }
    @Test void offHidesAccumulationAndReactivationDoesNotImportOldEpoch()throws Exception {
        fullWeeks(1);assertThat(finish().confirmed()).isEqualTo(1);fraction(api(),"confirmedWeeks",1,1);var stored=weeklyRows();
        var settings=app.getBean(DeloadAnalysisService.class);settings.save(owner,new DeloadAnalysisRequests.Save(0,1,false,"Asia/Seoul"));var off=api();status(off,"DISABLED");assertThat(off.get("confirmedWeeks").isNull()).isTrue();assertThat(off.get("confirmedThrough").isNull()).isTrue();assertThat(off.get("recommendationReady").asBoolean()).isFalse();
        var on=settings.save(owner,new DeloadAnalysisRequests.Save(0,2,true,"Asia/Seoul"));clock(on.epochStartedAt().toString());var value=api();status(value,"INITIAL");assertThat(value.get("epoch").asString()).isEqualTo("3");fraction(value,"epochInitialWeeks",0,1);assertThat(value.get("confirmedWeeks").isNull()).isTrue();assertThat(weeklyRows()).isEqualTo(stored);
    }
    @Test void actualRecordsBeforeAdoptionNeverEnterReadTotalsAfterCorrection()throws Exception {
        var old=seed(owner,0,0,from.minusSeconds(1),1,1,"FINISHED",false,false);weight(1,1,1);assertThat(finish().confirmed()).isEqualTo(1);fraction(api(),"confirmedWeeks",0,1);
        edit(old,List.of(new WorkoutRecordData.SetCorrection(old.sets().getFirst(),"61",9)),List.of(),List.of(),List.of());status(api(),"RECALCULATING");assertThat(finalizer.runBatch()).isEqualTo(1);fraction(api(),"confirmedWeeks",0,1);
    }
    Map<String,List<String>> domainRows() {
        var result=new TreeMap<String,List<String>>();for(var table:List.of("deload_analysis_versions","deload_weekly_assessments","daily_training_assessments","workout_calendar_control","workout_sessions","performed_sets","cardio_results","record_change_events"))result.put(table,jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class));return result;
    }
    @Test void repeatedGetUsesReadOnlyRepeatableReadWithoutAnyAnalysisOrOriginalWrites()throws Exception {
        var s=weight(0,1,1);weight(1,1,1);assertThat(finish().confirmed()).isEqualTo(1);edit(s,List.of(),List.of(),List.of(s.sets().getFirst()),List.of());
        var before=domainRows();var isolation=new AtomicReference<String>();var readonly=new AtomicReference<String>();
        WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().contains("effective_dirty"))try(var statement=e.connection().createStatement()) {
            try(var r=statement.executeQuery("SHOW transaction_read_only")){r.next();readonly.set(r.getString(1));}try(var r=statement.executeQuery("SHOW transaction_isolation")){r.next();isolation.set(r.getString(1));}
        }catch(Exception failure){throw new IllegalStateException(failure);}};
        try{for(int i=0;i<3;i++)status(api(),"RECALCULATING");}finally{WorkoutReadFixtures.observer=null;}
        assertThat(readonly.get()).isEqualTo("on");assertThat(isolation.get()).isEqualTo("repeatable read");assertThat(domainRows()).isEqualTo(before);
    }
    void pauseRead(CountDownLatch entered,CountDownLatch release) {
        var once=new AtomicBoolean();WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().contains("effective_dirty")&&once.compareAndSet(false,true)) {
            entered.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("isolated read pause");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
        }};
    }
    @Test void concurrentJobCanPublishWhileGetKeepsItsOriginalSnapshot()throws Exception {
        fullWeeks(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);pauseRead(entered,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var read=pool.submit(this::api);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try{assertThat(pool.submit(()->finalizer.runBatch()).get(10,TimeUnit.SECONDS)).isEqualTo(1);}finally{release.countDown();}
            var value=read.get(15,TimeUnit.SECONDS);status(value,"PENDING");assertThat(value.get("confirmedWeeks").isNull()).isTrue();
        }finally{release.countDown();WorkoutReadFixtures.observer=null;}
        fraction(api(),"confirmedWeeks",1,1);
    }
    @Test void concurrentOffCannotMixNewSettingsWithOldOnAccumulation()throws Exception {
        fullWeeks(1);assertThat(finish().confirmed()).isEqualTo(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);pauseRead(entered,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var read=pool.submit(this::api);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try{assertThat(pool.submit(()->app.getBean(DeloadAnalysisService.class).save(owner,new DeloadAnalysisRequests.Save(0,1,false,"Asia/Seoul"))).get(10,TimeUnit.SECONDS).enabled()).isFalse();}finally{release.countDown();}
            var value=read.get(15,TimeUnit.SECONDS);assertThat(value.get("enabled").asBoolean()).isTrue();status(value,"CONFIRMED");fraction(value,"confirmedWeeks",1,1);
        }finally{release.countDown();WorkoutReadFixtures.observer=null;}
        status(api(),"DISABLED");
    }
    @Test void concurrentRecordCorrectionIsVisibleOnlyInTheNextConsistentSnapshot()throws Exception {
        var s=weight(0,1,1);weight(1,1,1);assertThat(finish().confirmed()).isEqualTo(1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);pauseRead(entered,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var read=pool.submit(this::api);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            try{assertThat(pool.submit(()->edit(s,List.of(),List.of(),List.of(s.sets().getFirst()),List.of())).get(10,TimeUnit.SECONDS).get("changed").asBoolean()).isTrue();}finally{release.countDown();}
            fraction(read.get(15,TimeUnit.SECONDS),"confirmedWeeks",1,1);
        }finally{release.countDown();WorkoutReadFixtures.observer=null;}
        var value=api();status(value,"RECALCULATING");assertThat(value.get("confirmedWeeks").isNull()).isTrue();
    }
    @Test void authenticationQueryBodyAndMissingSettingsRemainStrict()throws Exception {
        try(var fresh=new Browser()){assertThat(fresh.get(PATH).statusCode()).isEqualTo(401);}
        assertThat(browser.get(PATH+"?userId="+UUID.randomUUID()).statusCode()).isEqualTo(400);assertThat(browser.get(PATH+"?dataGeneration=1").statusCode()).isEqualTo(400);
        assertThat(browser.send("GET",PATH,"{}",null).statusCode()).isEqualTo(400);
        jdbc.update("DELETE FROM deload_analysis_versions WHERE user_id=?",owner);var response=browser.get(PATH);assertThat(response.statusCode()).isEqualTo(500);assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("DELOAD_SETTINGS_MISSING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_analysis_versions WHERE user_id=?",Integer.class,owner)).isZero();
    }
    @Test void reservedPendingIsNotAConfirmedResultAndGetDoesNotRewriteItsPlaceholder()throws Exception {
        fullWeeks(1);transaction().executeWithoutResult(tx->{repo().lock(owner,0,false);repo().reserve(owner,0,0,monday,EmailOtpFixtures.CLOCK.instant());});
        var before=domainRows();var value=api();status(value,"PENDING");assertThat(value.get("confirmedWeeks").isNull()).isTrue();assertThat(domainRows()).isEqualTo(before);
        assertThat(finish().confirmed()).isEqualTo(1);fraction(api(),"confirmedWeeks",1,1);
    }
    @Test void lateActiveTravelDateBeforeStoredPrefixMustBlockUntilItsOutcomeIsKnown()throws Exception {
        fullWeeks(1);assertThat(finish().confirmed()).isEqualTo(1);var early=seed(owner,0,-1,from,1,0,"ACTIVE",false,false,false,"America/Los_Angeles");
        var value=api();status(value,"RECALCULATING");assertThat(value.get("confirmedWeeks").isNull()).isTrue();assertThat(value.get("blockedAt").asString()).isEqualTo(monday.minusWeeks(1).toString());
        assertThat(finalizer.runBatch()).isZero();assertThat(weeklyRows().getFirst().status()).isEqualTo("PENDING");
        jdbc.update("UPDATE workout_sessions SET status='AUTO_CLOSED',closed_at=now() WHERE id=?",early.id());
        assertThat(finalizer.runBatch()).isEqualTo(2);assertThat(weeklyRows().getFirst().status()).isEqualTo("EXCLUDED");fraction(api(),"confirmedWeeks",1,1);
    }
    @Test void getUsesOnlyPrincipalsCurrentGenerationAndAnotherOwnersResultsNeverLeak()throws Exception {
        fullWeeks(1);assertThat(finish().confirmed()).isEqualTo(1);
        try(var fresh=new Browser()) {
            fresh.login();UUID other=UUID.fromString(JSON.readTree(fresh.get("/__otp_test__/me").body()).get("id").asString());
            transaction().executeWithoutResult(tx->{WeeklyPresetFixtures.clear(jdbc,other);var rest=jdbc.queryForMap("SELECT * FROM user_workout_settings WHERE user_id=?",other);jdbc.update("DELETE FROM deload_analysis_versions WHERE user_id=?",other);jdbc.update("DELETE FROM user_workout_settings WHERE user_id=?",other);jdbc.update("UPDATE users SET data_generation=1 WHERE id=?",other);jdbc.update("INSERT INTO user_workout_settings(user_id,data_generation,rest_low_seconds,rest_medium_seconds,rest_high_seconds) VALUES (?,1,?,?,?)",other,rest.get("rest_low_seconds"),rest.get("rest_medium_seconds"),rest.get("rest_high_seconds"));app.getBean(DeloadAnalysisRepository.class).initializeNewAccount(other,1);});
            var value=ok(fresh.get(PATH));assertThat(value.get("userId").asString()).isEqualTo(other.toString());assertThat(value.get("dataGeneration").asString()).isEqualTo("1");assertThat(value.get("confirmedWeeks").isNull()).isTrue();
            var rejected=fresh.send("PUT",PATH+"/settings",JSON.writeValueAsString(Map.of("dataGeneration","0","expectedVersion","0","enabled",true,"timeZone","Asia/Seoul")),fresh.csrf());
            assertThat(rejected.statusCode()).isEqualTo(409);assertThat(JSON.readTree(rejected.body()).get("code").asString()).isEqualTo("DATA_GENERATION_MISMATCH");
        }
        fraction(api(),"confirmedWeeks",1,1);
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
