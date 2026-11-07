package com.overload.overload_server.deload;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.completion.ExactFraction;
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
import static com.overload.overload_server.deload.DeloadWeeklyFinalization.Status.*;
import static org.assertj.core.api.Assertions.*;

/** Schema-valid source fixtures live only in this disposable DB; existing API regressions run separately. */
@Tag("postgres") @Testcontainers
class DeloadHistoricalRecalculationIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine")
        .withDatabaseName("overload_g4_recalculation_isolated").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
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
    @BeforeEach void setup() throws Exception {
        WorkoutReadFixtures.observer=null;
        var repository=app.getBean(DeloadAnalysisRepository.class);
        for(UUID id:jdbc.queryForList("SELECT DISTINCT user_id FROM deload_analysis_versions",UUID.class)) {
            var prior=repository.current(id,0);
            if(prior.enabled())repository.append(id,0,prior.change(false,prior.timeZone()==null?"Asia/Seoul":prior.timeZone(),prior.recordedAt().isAfter(Instant.parse("2026-11-01T19:00:00Z"))?prior.recordedAt():Instant.parse("2026-11-01T19:00:00Z")));
        }
        clock("2026-11-01T19:00:00Z");jdbc.update("DELETE FROM email_otp_rate_limits");app.getBean(EmailOtpFixtures.FakeSender.class).reset();
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
    List<String> frozen(Session s) {return jdbc.queryForList("SELECT jsonb_build_array(initial_plan_snapshot,completion_assessment_snapshot,workout_date,started_at,started_time_zone,writer_epoch,local_revision,last_snapshot_hash)::text FROM workout_sessions WHERE id=?",String.class,s.id());}
    Session half(int day) {return seed(owner,0,day,at(day),1,1,"FINISHED",false,false,true);}
    WorkoutSnapshotData.SetValue other(Session s) {
        var id=jdbc.queryForObject("SELECT id FROM session_exercises WHERE session_id=? AND id<>?",UUID.class,s.id(),s.item());
        var v=missing(s,1);return new WorkoutSnapshotData.SetValue(v.id(),id,1,v.weightKg(),v.reps(),v.completedAt(),v.weightContext(),null,null);
    }
    void fullWeeks(int count) {for(int i=0;i<count;i++){weight(i*7,1,1);weight(i*7+1,1,1);}}
    void due(int count) {clock(monday.plusWeeks(count).atTime(4,0).atZone(ZoneId.of("Asia/Seoul")).toInstant().toString());}
    ExactFraction total() {return finalizer.accumulated(owner,0).weeks();}
    @Test void recoveryChangesResetToAddAndReplaysFourPointSevenFiveThenDeletionRestoresReset()throws Exception {
        fullWeeks(3);var changed=half(21);half(22);weight(28,1,1);weight(29,1,1);due(5);
        assertThat(finalizer.runBatch()).isEqualTo(4);assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(total()).isEqualTo(ExactFraction.of(1,1));
        var immutable=frozen(changed);var extra=other(changed);recover(changed,List.of(extra),List.of());
        assertThat(frozen(changed)).isEqualTo(immutable);assertThat(finalizer.accumulated(owner,0).blockedAt()).isEqualTo(monday.plusWeeks(3));assertThat(total()).isEqualTo(ExactFraction.of(3,1));
        assertThat(finalizer.runBatch()).isEqualTo(2);assertThat(total()).isEqualTo(ExactFraction.of(19,4));
        assertThat(weeklyRows().get(3).contribution()).isEqualTo(ExactFraction.of(3,4));
        edit(changed,List.of(),List.of(),List.of(extra.id()),List.of());assertThat(finalizer.runBatch()).isEqualTo(2);assertThat(total()).isEqualTo(ExactFraction.of(1,1));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM performed_sets WHERE id=?",Boolean.class,extra.id())).isTrue();
        assertThat(frozen(changed)).isEqualTo(immutable);assertThat(finalizer.runBatch()).isZero();
    }
    @Test void correctedAddUsesExactSevenTenthsAndNoOpAckDoesNotInvalidate()throws Exception {
        for(int i=0;i<3;i++)weight(i,1,1);var s=half(3);weight(4,4,1);var extra=other(s);recover(s,List.of(extra),List.of());
        assertThat(finish().confirmed()).isEqualTo(1);assertThat(total()).isEqualTo(ExactFraction.of(4,5));
        edit(s,List.of(),List.of(),List.of(extra.id()),List.of());assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(total()).isEqualTo(ExactFraction.of(7,10));
        var row=weeklyRows().getFirst();var ack=edit(s,List.of(new WorkoutRecordData.SetCorrection(s.sets().getFirst(),"60",10)),List.of(),List.of(),List.of());
        assertThat(ack.get("changed").asBoolean()).isFalse();
        assertThat(finalizer.runBatch()).isZero();assertThat(weeklyRows().getFirst()).isEqualTo(row);
    }
    @Test void initiallyEmptyWeekAcceptsLateClosedRecordAndLastDeletionReturnsNormalZero()throws Exception {
        var a=weight(0,1,0);var b=weight(1,1,0);var frozen=frozen(a);assertThat(finish().confirmed()).isEqualTo(1);assertThat(weeklyRows().getFirst().n()).isZero();
        var aa=missing(a,1);var bb=missing(b,1);recover(a,List.of(aa),List.of());recover(b,List.of(bb),List.of());
        assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(total()).isEqualTo(ExactFraction.of(1,1));
        edit(a,List.of(),List.of(),List.of(aa.id()),List.of());edit(b,List.of(),List.of(),List.of(bb.id()),List.of());
        assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(weeklyRows().getFirst().n()).isZero();assertThat(total()).isEqualTo(ExactFraction.ZERO);assertThat(frozen(a)).isEqualTo(frozen);
        var body=scope(a);body.put("confirm",true);body.put("origin",Map.of("editorDeviceId",jdbc.queryForObject("SELECT started_device_id FROM workout_sessions WHERE id=?",UUID.class,a.id()),"writerEpoch","0","localRevision","0"));body.put("performedSets",List.of(aa));body.put("cardioResults",List.of());
        assertThat(browser.send("POST","/api/v1/workout-sessions/"+a.id()+"/recoveries",JSON.writeValueAsString(body),browser.csrf()).statusCode()).isEqualTo(422);
        var replacement=missing(a,1);recover(a,List.of(replacement),List.of());assertThat(replacement.id()).isNotEqualTo(aa.id());assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM performed_sets WHERE id=?",Boolean.class,aa.id())).isTrue();
    }
    @Test void cardioCorrectionUnknownDeletionAndNewIdentityRecoveryPreserveOriginalPlan()throws Exception {
        weight(0,1,1);var s=seed(owner,0,1,at(1),0,0,"FINISHED",true,false);cardio(s,"COMPLETED",100000,"1",false);
        var id=jdbc.queryForObject("SELECT id FROM cardio_results WHERE session_id=?",UUID.class,s.id());var original=frozen(s);assertThat(finish().confirmed()).isEqualTo(1);
        edit(s,List.of(),List.of(new WorkoutRecordData.CardioCorrection(id,1,true,"1",null)),List.of(),List.of());assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(total()).isEqualTo(ExactFraction.ZERO);
        edit(s,List.of(),List.of(new WorkoutRecordData.CardioCorrection(id,100000,true,null,null)),List.of(),List.of());
        assertThat(finalizer.runBatch()).isZero();assertThat(weeklyRows().getFirst().dirty()).isTrue();assertThat(weeklyRows().getFirst().operation()).isEqualTo("RESET");
        edit(s,List.of(),List.of(new WorkoutRecordData.CardioCorrection(id,100000,true,"1",null)),List.of(),List.of());assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(total()).isEqualTo(ExactFraction.of(1,1));
        edit(s,List.of(),List.of(),List.of(),List.of(id));assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(weeklyRows().getFirst().n()).isEqualTo(1);
        var started=at(1);var recovered=new WorkoutSnapshotData.Cardio(UUID.randomUUID(),s.item(),"COMPLETED",started,started.plusSeconds(100),new WorkoutSnapshotData.CardioValue(100000L,100000L,false,"1",null));
        recover(s,List.of(),List.of(recovered));assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(total()).isEqualTo(ExactFraction.of(1,1));assertThat(frozen(s)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM cardio_results WHERE id=?",Boolean.class,id)).isTrue();
    }
    @Test void unchangedFlagClearsWithoutRewritingScoreInputOrConfirmationTime() {
        fullWeeks(1);assertThat(finish().confirmed()).isEqualTo(1);var row=weeklyRows().getFirst();var stamp=jdbc.queryForObject("SELECT confirmed_at FROM deload_weekly_assessments WHERE user_id=?",Timestamp.class,owner);
        transaction().executeWithoutResult(tx->{repo().lock(owner,0,false);repo().markSuffix(owner,0,0,monday,monday,EmailOtpFixtures.CLOCK.instant());});
        assertThat(finalizer.runBatch()).isEqualTo(1);var checked=weeklyRows().getFirst();assertThat(checked.input()).isEqualTo(row.input());assertThat(checked.e()).isEqualTo(row.e());assertThat(checked.dirty()).isFalse();
        assertThat(jdbc.queryForObject("SELECT confirmed_at FROM deload_weekly_assessments WHERE user_id=?",Timestamp.class,owner)).isEqualTo(stamp);assertThat(finalizer.runBatch()).isZero();
    }
    @Test void boundedReplayPersistsSuffixAndResumesAfterApplicationRestart()throws Exception {
        var first=weight(0,1,1);weight(1,1,1);for(int w=1;w<7;w++){weight(w*7,1,1);weight(w*7+1,1,1);}due(7);
        assertThat(finalizer.runBatch()).isEqualTo(4);assertThat(finalizer.runBatch()).isEqualTo(3);
        edit(first,List.of(),List.of(),List.of(first.sets().getFirst()),List.of());assertThat(finalizer.runBatch()).isEqualTo(4);
        assertThat(total()).isEqualTo(ExactFraction.of(3,1));assertThat(finalizer.accumulated(owner,0).blockedAt()).isEqualTo(monday.plusWeeks(4));assertThat(weeklyRows().subList(4,7)).allMatch(DeloadWeeklyRepository.Row::dirty);
        browser.close();app.close();boot();finalizer=app.getBean(DeloadWeeklyFinalization.class);
        assertThat(finalizer.runBatch()).isEqualTo(3);assertThat(total()).isEqualTo(ExactFraction.of(6,1));assertThat(finalizer.runBatch()).isZero();
    }
    @Test void failureDuringThirdReplayRollsBackWholeWindowAndPreservesDirtyPrefix()throws Exception {
        var s=weight(0,1,1);weight(1,1,1);weight(7,1,1);weight(8,1,1);weight(14,1,1);weight(15,1,1);due(3);assertThat(finalizer.runBatch()).isEqualTo(3);
        edit(s,List.of(),List.of(),List.of(s.sets().getFirst()),List.of());var old=weeklyRows();var updates=new AtomicInteger();
        WorkoutReadFixtures.observer=e->{if(e.before()&&(e.sql().contains("SET status='CONFIRMED'")||e.sql().contains("SET needs_recheck=false"))&&updates.incrementAndGet()==3)throw new IllegalStateException("isolated replay rollback");};
        try{assertThat(finalizer.runBatch()).isZero();}finally{WorkoutReadFixtures.observer=null;}
        assertThat(weeklyRows()).extracting(DeloadWeeklyRepository.Row::contribution).containsExactlyElementsOf(old.stream().map(DeloadWeeklyRepository.Row::contribution).toList());assertThat(weeklyRows()).allMatch(DeloadWeeklyRepository.Row::dirty);assertThat(total()).isEqualTo(ExactFraction.ZERO);
        assertThat(finalizer.runBatch()).isEqualTo(3);assertThat(total()).isEqualTo(ExactFraction.of(2,1));
    }
    @Test void middleUnknownAndActiveNeverExposeLaterStoredContributions()throws Exception {
        fullWeeks(1);weight(7,1,1);var s=seed(owner,0,8,at(8),0,0,"FINISHED",true,false);cardio(s,"COMPLETED",100000,"1",false);weight(14,1,1);weight(15,1,1);due(3);assertThat(finalizer.runBatch()).isEqualTo(3);
        var id=jdbc.queryForObject("SELECT id FROM cardio_results WHERE session_id=?",UUID.class,s.id());edit(s,List.of(),List.of(new WorkoutRecordData.CardioCorrection(id,100000,true,null,null)),List.of(),List.of());
        assertThat(finalizer.runBatch()).isZero();assertThat(total()).isEqualTo(ExactFraction.of(1,1));assertThat(weeklyRows().get(2).dirty()).isTrue();
        edit(s,List.of(),List.of(new WorkoutRecordData.CardioCorrection(id,100000,true,"1",null)),List.of(),List.of());assertThat(finalizer.runBatch()).isEqualTo(2);assertThat(total()).isEqualTo(ExactFraction.of(3,1));
        seed(owner,0,9,at(9),1,0,"ACTIVE",false,false);assertThat(finalizer.runBatch()).isZero();assertThat(total()).isEqualTo(ExactFraction.of(1,1));
    }
    @Test void oldEpochCorrectionDoesNotImportItsScoreIntoReactivatedEpoch()throws Exception {
        var s=weight(0,1,1);weight(1,1,1);assertThat(finish().confirmed()).isEqualTo(1);
        var settings=app.getBean(DeloadAnalysisService.class);settings.save(owner,new DeloadAnalysisRequests.Save(0,1,false,"Asia/Seoul"));
        edit(s,List.of(),List.of(),List.of(s.sets().getFirst()),List.of());assertThat(finalizer.runBatch()).isZero();assertThat(weeklyRows().getFirst().operation()).isEqualTo("ADD");
        var on=settings.save(owner,new DeloadAnalysisRequests.Save(0,2,true,"Asia/Seoul"));due(3);assertThat(finalizer.runBatch()).isGreaterThan(0);
        assertThat(weeklyRows().getFirst().operation()).isEqualTo("RESET");assertThat(finalizer.accumulated(owner,0).epoch()).isEqualTo(Long.parseLong(on.epoch()));assertThat(total()).isEqualTo(ExactFraction.ZERO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_analysis_versions WHERE user_id=?",Integer.class,owner)).isEqualTo(4);
    }
    @Test void preAdoptionRecordsRemainExcludedAfterActualCorrection()throws Exception {
        var before=seed(owner,0,0,from.minusSeconds(1),1,1,"FINISHED",false,false);weight(1,1,1);assertThat(finish().confirmed()).isEqualTo(1);var original=weeklyRows().getFirst();
        edit(before,List.of(new WorkoutRecordData.SetCorrection(before.sets().getFirst(),"61",9)),List.of(),List.of(),List.of());assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(weeklyRows().getFirst().n()).isEqualTo(1);assertThat(total()).isEqualTo(ExactFraction.ZERO);assertThat(weeklyRows().getFirst().e()).isEqualTo(original.e());
    }
    void calendar() {
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,effective_from,time_zone,recorded_at,assignments) VALUES (?,0,1,?,'Asia/Seoul',?,'[]')",owner,monday,Timestamp.from(from));
        jdbc.update("INSERT INTO workout_calendar_control VALUES (?,0,'Asia/Seoul',1,?,?)",owner,monday,monday.plusDays(6));
    }
    @Test void missAndActualUseOneDateAndG3ClearPreservesRecalculationAndFirstMiss()throws Exception {
        var s=weight(0,1,0);weight(1,1,1);calendar();var missed=Timestamp.from(at(6).plusSeconds(86400));
        jdbc.update("""
            INSERT INTO daily_training_assessments(user_id,data_generation,workout_date,first_missed_at,missed_schedule_version,missed_routine_id,missed_plan_version,state,revision,needs_recheck,reviewed_at)
            VALUES (?,0,?,?,1,?,0,'MISSED',10,false,?) ON CONFLICT(user_id,data_generation,workout_date) DO UPDATE SET
            first_missed_at=EXCLUDED.first_missed_at,missed_schedule_version=1,missed_routine_id=EXCLUDED.missed_routine_id,missed_plan_version=0,state='MISSED',needs_recheck=false,reviewed_at=EXCLUDED.reviewed_at
            """,owner,monday,missed,s.routine(),missed);
        var g3=app.getBean(com.overload.overload_server.statistics.DailyTrainingReviewService.class);g3.reviewBatch();
        // Seed a confirmed G3 MISS for the initially empty session; a subsequent real recovery changes only its current state.
        jdbc.update("UPDATE daily_training_assessments SET state='MISSED',needs_recheck=false WHERE user_id=? AND workout_date=?",owner,monday);
        assertThat(finish().confirmed()).isEqualTo(1);assertThat(weeklyRows().getFirst().n()).isEqualTo(2);assertThat(total()).isEqualTo(ExactFraction.ZERO);
        recover(s,List.of(missing(s,1)),List.of());g3.reviewBatch();
        assertThat(jdbc.queryForObject("SELECT state FROM daily_training_assessments WHERE user_id=? AND workout_date=?",String.class,owner,monday)).isEqualTo("PERFORMED");
        assertThat(jdbc.queryForObject("SELECT needs_recheck FROM daily_training_assessments WHERE user_id=? AND workout_date=?",Boolean.class,owner,monday)).isFalse();
        assertThat(weeklyRows().getFirst().dirty()).isTrue();assertThat(weeklyRows().getFirst().operation()).isEqualTo("RESET");assertThat(finalizer.runBatch()).isEqualTo(1);
        assertThat(total()).isEqualTo(ExactFraction.of(1,1));assertThat(weeklyRows().getFirst().n()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT first_missed_at FROM daily_training_assessments WHERE user_id=? AND workout_date=?",Timestamp.class,owner,monday)).isEqualTo(missed);
    }
    @Test void secondSourceMutationDuringReplayCannotConfirmStaleScore()throws Exception {
        var s=weight(0,1,1);weight(1,1,1);assertThat(finish().confirmed()).isEqualTo(1);
        edit(s,List.of(new WorkoutRecordData.SetCorrection(s.sets().getFirst(),"61",10)),List.of(),List.of(),List.of());var old=weeklyRows().getFirst();var once=new AtomicBoolean();
        WorkoutReadFixtures.observer=e->{if(!e.before()&&e.sql().startsWith("SELECT ws.id,ws.workout_date,ws.started_at")&&once.compareAndSet(false,true)) {
            try(var statement=e.connection().prepareStatement("UPDATE performed_sets SET deleted_at=now() WHERE id=?")){statement.setObject(1,s.sets().getFirst());statement.executeUpdate();}catch(Exception failure){throw new IllegalStateException(failure);}
        }};
        try {assertThat(finish().status()).isEqualTo(STALE);}finally{WorkoutReadFixtures.observer=null;}
        assertThat(weeklyRows().getFirst().e()).isEqualTo(old.e());assertThat(weeklyRows().getFirst().dirty()).isTrue();assertThat(total()).isEqualTo(ExactFraction.ZERO);
        assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(weeklyRows().getFirst().operation()).isEqualTo("RESET");
    }
    @Test void concurrentReplayWorkersAndActualRecordWriterSerializeAtOwnerLock()throws Exception {
        var s=weight(0,1,1);weight(1,1,1);assertThat(finish().confirmed()).isEqualTo(1);
        edit(s,List.of(new WorkoutRecordData.SetCorrection(s.sets().getFirst(),"61",10)),List.of(),List.of(),List.of());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var once=new AtomicBoolean();
        WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().contains("SET status='CONFIRMED'")&&once.compareAndSet(false,true)) {
            entered.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("isolated replay pause");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
        }};
        try(var pool=Executors.newFixedThreadPool(3)) {
            var a=pool.submit(()->finalizer.runBatch());assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(()->finalizer.runBatch());assertThat(b.get(10,TimeUnit.SECONDS)).isZero();
            var writer=pool.submit(()->edit(s,List.of(),List.of(),List.of(s.sets().getFirst()),List.of()));
            try{assertThatThrownBy(()->writer.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            assertThat(a.get(20,TimeUnit.SECONDS)+b.get(20,TimeUnit.SECONDS)).isEqualTo(1);assertThat(writer.get(20,TimeUnit.SECONDS).get("changed").asBoolean()).isTrue();
        }finally{release.countDown();WorkoutReadFixtures.observer=null;}
        assertThat(finalizer.accumulated(owner,0).blockedAt()).isEqualTo(monday);assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(total()).isEqualTo(ExactFraction.ZERO);
    }
    @Test void lastTravelActivityDeletionIsExcludedAndRealRecoveryCanReenterWithoutLosingProgress()throws Exception {
        var early=seed(owner,0,-1,from,1,1,"FINISHED",false,false,false,"America/Los_Angeles");fullWeeks(1);
        var original=frozen(early);assertThat(finalizer.runBatch()).isEqualTo(2);assertThat(total()).isEqualTo(ExactFraction.of(1,1));
        edit(early,List.of(),List.of(),List.of(early.sets().getFirst()),List.of());assertThat(finalizer.accumulated(owner,0).blockedAt()).isEqualTo(monday.minusWeeks(1));
        assertThat(finalizer.runBatch()).isEqualTo(2);var row=weeklyRows().getFirst();assertThat(row.status()).isEqualTo("EXCLUDED");
        assertThat(row.n()).isNull();assertThat(row.e()).isNull();assertThat(row.operation()).isNull();assertThat(row.contribution()).isNull();assertThat(row.dirty()).isFalse();
        assertThat(total()).isEqualTo(ExactFraction.of(1,1));assertThat(finalizer.runBatch()).isZero();
        var stamp=jdbc.queryForObject("SELECT confirmed_at FROM deload_weekly_assessments WHERE user_id=? AND week_start=?",Timestamp.class,owner,row.week());
        transaction().executeWithoutResult(tx->{repo().lock(owner,0,false);repo().markSuffix(owner,0,0,monday.minusWeeks(1),monday,EmailOtpFixtures.CLOCK.instant());});
        assertThat(finalizer.runBatch()).isEqualTo(2);assertThat(jdbc.queryForObject("SELECT confirmed_at FROM deload_weekly_assessments WHERE user_id=? AND week_start=?",Timestamp.class,owner,row.week())).isEqualTo(stamp);
        var recovered=missing(early,1);recover(early,List.of(recovered),List.of());assertThat(finalizer.runBatch()).isEqualTo(2);
        row=weeklyRows().getFirst();assertThat(row.status()).isEqualTo("CONFIRMED");assertThat(row.n()).isEqualTo(1);assertThat(row.operation()).isEqualTo("RESET");assertThat(total()).isEqualTo(ExactFraction.of(1,1));
        edit(early,List.of(),List.of(),List.of(recovered.id()),List.of());assertThat(finalizer.runBatch()).isEqualTo(2);assertThat(weeklyRows().getFirst().status()).isEqualTo("EXCLUDED");
        assertThat(frozen(early)).isEqualTo(original);assertThat(weeklyRows()).hasSize(2);assertThat(finalizer.runBatch()).isZero();
    }
    @Test void exclusionFailureRollsBackWithoutErasingOldScoreAndRetriesWithCas()throws Exception {
        var early=seed(owner,0,-1,from,1,1,"FINISHED",false,false,false,"America/Los_Angeles");fullWeeks(1);assertThat(finalizer.runBatch()).isEqualTo(2);
        var old=weeklyRows().getFirst();edit(early,List.of(),List.of(),List.of(early.sets().getFirst()),List.of());var once=new AtomicBoolean();
        WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().contains("SET status='EXCLUDED'")&&once.compareAndSet(false,true)) {
            try(var statement=e.connection().prepareStatement("UPDATE deload_weekly_assessments SET revision=revision+1 WHERE user_id=?")){statement.setObject(1,owner);statement.executeUpdate();}catch(Exception failure){throw new IllegalStateException(failure);}
        }};
        try{assertThat(finalizer.runBatch()).isZero();}finally{WorkoutReadFixtures.observer=null;}
        assertThat(once).isTrue();assertThat(weeklyRows().getFirst().e()).isEqualTo(old.e());assertThat(weeklyRows().getFirst().status()).isEqualTo("CONFIRMED");assertThat(weeklyRows().getFirst().dirty()).isTrue();
        assertThat(total()).isEqualTo(ExactFraction.ZERO);assertThat(finalizer.runBatch()).isEqualTo(2);assertThat(total()).isEqualTo(ExactFraction.of(1,1));
    }
    @Test void activityArrivingBetweenExclusionReadsCannotPublishStaleExcludedState()throws Exception {
        var early=seed(owner,0,-1,from,1,1,"FINISHED",false,false,false,"America/Los_Angeles");fullWeeks(1);assertThat(finalizer.runBatch()).isEqualTo(2);
        edit(early,List.of(),List.of(),List.of(early.sets().getFirst()),List.of());var once=new AtomicBoolean();
        WorkoutReadFixtures.observer=e->{if(!e.before()&&e.sql().startsWith("SELECT ws.id,ws.workout_date,ws.started_at")&&once.compareAndSet(false,true)) {
            try(var statement=e.connection().prepareStatement("INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording) SELECT ?,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording FROM performed_sets WHERE id=?")) {
                statement.setObject(1,UUID.randomUUID());statement.setObject(2,early.sets().getFirst());statement.executeUpdate();
            }catch(Exception failure){throw new IllegalStateException(failure);}
        }};
        try{assertThat(finalizer.finalizeWeek(owner,0,0,monday.minusWeeks(1)).status()).isEqualTo(STALE);}finally{WorkoutReadFixtures.observer=null;}
        assertThat(once).isTrue();assertThat(weeklyRows().getFirst().status()).isEqualTo("CONFIRMED");assertThat(weeklyRows().getFirst().dirty()).isTrue();assertThat(weeklyRows().getFirst().n()).isEqualTo(1);
        assertThat(finalizer.runBatch()).isEqualTo(2);assertThat(weeklyRows().getFirst().status()).isEqualTo("CONFIRMED");assertThat(total()).isEqualTo(ExactFraction.of(1,1));
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
