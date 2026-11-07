package com.overload.overload_server.deload;

import com.overload.overload_server.OverloadServerApplication;
import com.overload.overload_server.completion.ExactFraction;
import com.overload.overload_server.routine.RoutineResponses;
import com.overload.overload_server.workoutsession.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.overload.testfixture.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import static com.overload.overload_server.deload.DeloadWeeklyFinalization.Status.*;
import static com.overload.overload_server.deload.DeloadCandidateCalculator.Pending.*;
import static org.assertj.core.api.Assertions.*;

/** Schema-valid source fixtures live only in this disposable DB; existing API regressions run separately. */
@Tag("postgres") @Testcontainers
class DeloadWeeklyFinalizationIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine")
        .withDatabaseName("overload_g4_finalization_isolated").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext app;static JdbcTemplate jdbc;static final JsonMapper JSON=JsonMapper.builder().build();
    final LocalDate monday=LocalDate.of(2026,10,26);final Instant from=Instant.parse("2026-10-25T15:00:00Z");
    UUID owner;DeloadWeeklyCandidates service;DeloadWeeklyFinalization finalizer;
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
    @BeforeEach void setup() {
        WorkoutReadFixtures.observer=null;
        var repository=app.getBean(DeloadAnalysisRepository.class);
        for(UUID id:jdbc.queryForList("SELECT DISTINCT user_id FROM deload_analysis_versions",UUID.class)) {
            var prior=repository.current(id,0);
            if(prior.enabled())repository.append(id,0,prior.change(false,prior.timeZone()==null?"Asia/Seoul":prior.timeZone(),prior.recordedAt().isAfter(Instant.parse("2026-11-01T19:00:00Z"))?prior.recordedAt():Instant.parse("2026-11-01T19:00:00Z")));
        }
        clock("2026-11-01T19:00:00Z");owner=account(0,true);service=app.getBean(DeloadWeeklyCandidates.class);finalizer=app.getBean(DeloadWeeklyFinalization.class);
    }
    @AfterEach void resetObserver() {WorkoutReadFixtures.observer=null;}
    static void clock(String value) {EmailOtpFixtures.CLOCK.advance(Duration.between(EmailOtpFixtures.CLOCK.instant(),Instant.parse(value)));}
    DeloadWeeklyRepository repo() {return app.getBean(DeloadWeeklyRepository.class);}
    DeloadWeeklyFinalization.Progress finish() {return finalizer.finalizeWeek(owner,0,0,monday);}
    List<DeloadWeeklyRepository.Row> weeklyRows() {return repo().rows(owner,0,0);}
    TransactionTemplate transaction() {var t=new TransactionTemplate(app.getBean("workoutTransactionManager",PlatformTransactionManager.class));t.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);return t;}

    UUID account(long generation,boolean zone) {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO users(id,data_generation,created_at,updated_at) VALUES (?,?,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')",id,generation);
        app.getBean(DeloadAnalysisService.class).adoptExisting(id,generation,from);
        if(zone)append(id,generation,1,true,0,from,"TIME_ZONE_SET");return id;
    }
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
        UUID second=UUID.randomUUID();
        var goal=new RoutineResponses.Plan("TREADMILL","run",100,"1",null,null,null);
        var initial=new InitialPlan(2,routine,"sealed fixture","0",cardio?List.of():(half?List.of(new InitialPlan.Weight(item,UUID.randomUUID(),standard,"lift",null,null,1,6,10,"60","60"),new InitialPlan.Weight(second,UUID.randomUUID(),standard,"second",null,null,1,6,10,"60","60")):List.of(new InitialPlan.Weight(item,UUID.randomUUID(),standard,"lift",null,null,planned,6,10,"60","60"))),
            cardio?List.of(new InitialPlan.Cardio(item,UUID.randomUUID(),goal)):List.of());
        String json=damaged?"{\"schemaVersion\":2,\"weightExercises\":null}":JSON.writeValueAsString(initial);
        var applied=new WorkoutSnapshotData.Plan(2,List.of(),List.of());
        jdbc.update("""
            INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,
            initial_plan_snapshot,applied_plan_snapshot,last_workout_synced_at,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,now(),now(),now())
            """,session,user,generation,routine,Timestamp.from(start),monday.plusDays(day),zone,UUID.randomUUID(),UUID.randomUUID(),json,JSON.writeValueAsString(applied));
        if(cardio)jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind) VALUES (?,?,?,?,'CARDIO')",item,session,user,generation);
        else jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,standard_exercise_id) VALUES (?,?,?,?,'WEIGHT',?)",item,session,user,generation,standard);
        if(half)jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,standard_exercise_id) VALUES (?,?,?,?,'WEIGHT',?)",second,session,user,generation,standard);
        var ids=new ArrayList<UUID>();for(int i=1;i<=actual;i++) {var id=UUID.randomUUID();ids.add(id);jdbc.update("INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording) VALUES (?,?,?,?,60,10,?,'TODAY_ONLY','TODAY_ONLY')",id,session,item,i,Timestamp.from(start.plusSeconds(i*60)));}
        if(status.equals("FINISHED"))jdbc.update("UPDATE workout_sessions SET status='FINISHED',ended_at=greatest(now(),started_at+interval '1 hour'),closed_at=greatest(now(),started_at+interval '1 hour'),completion_assessment_snapshot='{\"schemaVersion\":2}' WHERE id=?",session);
        if(status.equals("AUTO_CLOSED"))jdbc.update("UPDATE workout_sessions SET status='AUTO_CLOSED',closed_at=now() WHERE id=?",session);
        return new Session(session,routine,item,ids);
    }
    Session weight(int day,int planned,int actual) {return seed(owner,0,day,at(day),planned,actual,"FINISHED",false,false);}
    void cardio(Session s,String state,long ms,String distance,boolean deleted) {
        jdbc.update("""
            INSERT INTO cardio_results(id,session_id,session_activity_id,status,started_at,occurred_at,timer_ms,active_ms,corrected,distance,deleted_at)
            VALUES (?,?,?,?,now()-interval '1 hour',now(),?,?,?,?::numeric,?)
            """,UUID.randomUUID(),s.id(),s.item(),state,state.equals("COMPLETED")?ms:null,state.equals("COMPLETED")?ms:null,state.equals("COMPLETED")?false:null,distance,deleted?Timestamp.from(Instant.now()):null);
    }
    DeloadCandidateCalculator.Week read() {return service.calculate(owner,0,0,monday);}
    @Test void mondayBeforeFourIsNotDueAndFourIsInclusiveForAnUnscheduledAccount() {
        weight(0,1,1);weight(1,1,1);clock("2026-11-01T18:59:59.999999Z");
        assertThat(finish().status()).isEqualTo(NOT_DUE);assertThat(weeklyRows()).isEmpty();assertThat(finalizer.runBatch()).isZero();
        clock("2026-11-01T19:00:00Z");assertThat(finalizer.runBatch()).isEqualTo(1);
        var row=weeklyRows().getFirst();assertThat(row.operation()).isEqualTo("ADD");assertThat(row.e()).isEqualTo(ExactFraction.of(2,1));assertThat(row.contribution()).isEqualTo(ExactFraction.of(1,1));
        assertThat(finalizer.accumulated(owner,0).weeks()).isEqualTo(ExactFraction.of(1,1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_calendar_control WHERE user_id=?",Integer.class,owner)).isZero();
    }
    @Test void completedZeroWeekResetsButMissingG3InspectionStaysPending() {
        assertThat(finish().confirmed()).isEqualTo(1);assertThat(weeklyRows().getFirst().operation()).isEqualTo("RESET");
        var second=account(0,true);owner=second;calendar(monday,monday.minusDays(1));
        assertThat(finish().status()).isEqualTo(PENDING);var row=weeklyRows().getFirst();assertThat(row.e()).isNull();assertThat(row.n()).isNull();assertThat(row.contribution()).isNull();assertThat(row.operation()).isNull();
        assertThat(finalizer.accumulated(owner,0).blockedAt()).isEqualTo(monday);
    }
    void calendar(LocalDate reviewFrom,LocalDate through) {
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,effective_from,time_zone,recorded_at,assignments) VALUES (?,0,1,?,'Asia/Seoul',?,'[]')",owner,monday,Timestamp.from(from));
        jdbc.update("INSERT INTO workout_calendar_control VALUES (?,0,'Asia/Seoul',1,?,?)",owner,reviewFrom,through);
    }
    @Test void existingG3ReviewMustRunBeforeFinalizationAndOffDoesNotDisableG3() {
        weight(0,1,1);weight(1,1,1);calendar(monday,monday.minusDays(1));
        assertThat(finalizer.runBatch()).isZero();assertThat(weeklyRows().getFirst().status()).isEqualTo("PENDING");
        app.getBean(com.overload.overload_server.statistics.DailyTrainingReviewService.class).reviewBatch();
        assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(weeklyRows().getFirst().contribution()).isEqualTo(ExactFraction.of(1,1));
        var repo=app.getBean(DeloadAnalysisRepository.class);var state=repo.current(owner,0);repo.append(owner,0,state.change(false,state.timeZone(),state.recordedAt().plusSeconds(1)));
        var changed=weight(2,4,1);app.getBean(com.overload.overload_server.statistics.DailyTrainingReviewService.class).reviewBatch();
        assertThat(jdbc.queryForObject("SELECT state FROM daily_training_assessments WHERE user_id=? AND workout_date=?",String.class,owner,monday.plusDays(2))).isEqualTo("PERFORMED");
        assertThat(finish().status()).isEqualTo(DISABLED);assertThat(changed.sets()).hasSize(1);
    }
    @Test void sameWeekRepeatedAndConcurrentWorkersNeverDoubleAdd()throws Exception {
        weight(0,1,1);weight(1,1,1);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return finish();});var b=pool.submit(()->{gate.await();return finish();});gate.countDown();
            var values=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));assertThat(values.stream().mapToInt(DeloadWeeklyFinalization.Progress::confirmed).sum()).isEqualTo(1);
        }
        var before=jdbc.queryForMap("SELECT * FROM deload_weekly_assessments WHERE user_id=?",owner);
        assertThat(finish().status()).isEqualTo(ALREADY_CONFIRMED);assertThat(finalizer.runBatch()).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM deload_weekly_assessments WHERE user_id=?",owner)).isEqualTo(before);
        assertThat(finalizer.accumulated(owner,0).weeks()).isEqualTo(ExactFraction.of(1,1));
    }
    @Test void activeAndUnknownSundayRemainPendingAfterFour() {
        var s=seed(owner,0,6,at(6),0,0,"AUTO_CLOSED",true,false);cardio(s,"COMPLETED",100000,null,false);
        assertThat(finish().status()).isEqualTo(PENDING);assertThat(weeklyRows().getFirst().e()).isNull();
        seed(owner,0,6,at(6),1,0,"ACTIVE",false,false);assertThat(finish().status()).isEqualTo(PENDING);
        assertThat(jdbc.queryForObject("SELECT status FROM workout_sessions WHERE user_id=? AND status='ACTIVE'",String.class,owner)).isEqualTo("ACTIVE");
    }
    @Test void chronologicalWeeksFoldResetAndAddWithoutCurrentTotalStorage() {
        weight(0,1,1);weight(1,1,1);weight(14,1,1);weight(15,1,1);clock("2026-11-15T19:00:00Z");
        assertThat(finalizer.runBatch()).isEqualTo(3);assertThat(weeklyRows()).extracting(DeloadWeeklyRepository.Row::operation).containsExactly("ADD","RESET","ADD");
        var total=finalizer.accumulated(owner,0);assertThat(total.weeks()).isEqualTo(ExactFraction.of(1,1));assertThat(total.confirmedThrough()).isEqualTo(monday.plusWeeks(2));assertThat(total.blockedAt()).isNull();
        assertThat(finalizer.runBatch()).isZero();assertThat(finalizer.accumulated(owner,0)).isEqualTo(total);
    }
    @Test void boundedCatchupKeepsAllOldUnprocessedWeeksAcrossTicks() {
        clock("2026-12-27T19:00:00Z");
        assertThat(finalizer.runBatch()).isEqualTo(4);assertThat(weeklyRows()).hasSize(4);
        assertThat(finalizer.runBatch()).isEqualTo(4);assertThat(weeklyRows()).hasSize(8);
        assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(weeklyRows()).hasSize(9);
        assertThat(finalizer.runBatch()).isZero();assertThat(finalizer.accumulated(owner,0).confirmedThrough()).isEqualTo(LocalDate.of(2026,12,21));
    }
    @Test void twentyOwnerBatchDoesNotOmitAccountsWithoutSchedules() {
        var ids=new ArrayList<UUID>();ids.add(owner);for(int i=0;i<24;i++)ids.add(account(0,true));
        assertThat(finalizer.runBatch()).isEqualTo(20);assertThat(finalizer.runBatch()).isEqualTo(5);
        for(var id:ids)assertThat(repo().rows(id,0,0)).hasSize(1);
    }
    @Test void rollbackLeavesAPendingRetryAndRestartResumesWithoutDuplicateResults() {
        weight(0,1,1);weight(1,1,1);var fail=new AtomicBoolean();
        WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().contains("SET status='CONFIRMED'")&&fail.compareAndSet(false,true))throw new IllegalStateException("isolated finalization failure");};
        try {assertThat(finalizer.runBatch()).isZero();}finally{WorkoutReadFixtures.observer=null;}
        assertThat(weeklyRows()).hasSize(1);assertThat(weeklyRows().getFirst().status()).isEqualTo("PENDING");assertThat(weeklyRows().getFirst().e()).isNull();
        app.close();boot();finalizer=app.getBean(DeloadWeeklyFinalization.class);
        assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(finalizer.runBatch()).isZero();assertThat(weeklyRows().getFirst().status()).isEqualTo("CONFIRMED");
    }
    @Test void sourceMutationBetweenCalculationsCannotPublishTheStaleInput() {
        var s=weight(0,1,1);weight(1,1,1);var once=new AtomicBoolean();
        WorkoutReadFixtures.observer=e->{if(!e.before()&&e.sql().startsWith("SELECT ws.id,ws.workout_date,ws.started_at")&&once.compareAndSet(false,true)) {
            try(var update=e.connection().prepareStatement("UPDATE performed_sets SET deleted_at=now() WHERE id=?")){update.setObject(1,s.sets().getFirst());update.executeUpdate();}catch(Exception failure){throw new IllegalStateException(failure);}
        }};
        try {assertThat(finish().status()).isEqualTo(STALE);}finally{WorkoutReadFixtures.observer=null;}
        assertThat(weeklyRows().getFirst().status()).isEqualTo("PENDING");assertThat(weeklyRows().getFirst().e()).isNull();
        assertThat(finish().confirmed()).isEqualTo(1);assertThat(weeklyRows().getFirst().operation()).isEqualTo("RESET");
    }
    @Test void sourceVectorsDetectCorrectionEvenWithoutG3CalendarAndBlockLaterTotals() {
        var s=weight(0,1,1);weight(1,1,1);clock("2026-11-08T19:00:00Z");assertThat(finalizer.runBatch()).isEqualTo(2);
        jdbc.update("UPDATE performed_sets SET deleted_at=now() WHERE id=?",s.sets().getFirst());
        var total=finalizer.accumulated(owner,0);assertThat(total.blockedAt()).isEqualTo(monday);assertThat(total.confirmedThrough()).isNull();assertThat(total.weeks()).isEqualTo(ExactFraction.ZERO);
        assertThat(weeklyRows().getFirst().dirty()).isTrue();assertThat(weeklyRows().getFirst().operation()).isEqualTo("ADD");
        assertThat(finish().status()).isEqualTo(RECALCULATION_REQUIRED);assertThat(finalizer.runBatch()).isZero();
    }
    @Test void g3DirtyClearKeepsDurableWeeklyInvalidationAndOriginalScores() {
        var s=weight(0,1,1);weight(1,1,1);calendar(monday,monday.minusDays(1));
        var g3=app.getBean(com.overload.overload_server.statistics.DailyTrainingReviewService.class);g3.reviewBatch();assertThat(finish().confirmed()).isEqualTo(1);
        var original=weeklyRows().getFirst();jdbc.update("UPDATE performed_sets SET deleted_at=now() WHERE id=?",s.sets().getFirst());g3.reviewBatch();
        assertThat(jdbc.queryForObject("SELECT needs_recheck FROM daily_training_assessments WHERE user_id=? AND workout_date=?",Boolean.class,owner,monday)).isFalse();
        var saved=weeklyRows().getFirst();assertThat(saved.dirty()).isTrue();assertThat(saved.e()).isEqualTo(original.e());assertThat(saved.contribution()).isEqualTo(original.contribution());
        assertThat(saved.revision()).isGreaterThan(original.revision());assertThat(finalizer.accumulated(owner,0).blockedAt()).isEqualTo(monday);
    }
    @Test void multipleWorkersAlsoSerializeAtTheOwnerBoundary()throws Exception {
        weight(0,1,1);weight(1,1,1);var gate=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{gate.await();return finalizer.runBatch();});var b=pool.submit(()->{gate.await();return finalizer.runBatch();});gate.countDown();
            assertThat(a.get(20,TimeUnit.SECONDS)+b.get(20,TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(weeklyRows()).hasSize(1);assertThat(finalizer.accumulated(owner,0).weeks()).isEqualTo(ExactFraction.of(1,1));
    }
    void pausePublication(CountDownLatch entered,CountDownLatch release) {
        var once=new AtomicBoolean();WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().contains("SET status='CONFIRMED'")&&once.compareAndSet(false,true)) {
            entered.countDown();try {if(!release.await(20,TimeUnit.SECONDS))throw new IllegalStateException("isolated pause timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
        }};
    }
    @Test void actualWriterShareLockWaitsForPublicationThenNewRevisionInvalidatesIt()throws Exception {
        var s=weight(0,1,1);weight(1,1,1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);pausePublication(entered,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var publish=pool.submit(this::finish);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var writer=pool.submit(()->transaction().execute(tx->{jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR SHARE",UUID.class,owner);return jdbc.update("UPDATE performed_sets SET deleted_at=now() WHERE id=?",s.sets().getFirst());}));
            try {assertThatThrownBy(()->writer.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            assertThat(publish.get(20,TimeUnit.SECONDS).confirmed()).isEqualTo(1);assertThat(writer.get(20,TimeUnit.SECONDS)).isEqualTo(1);
        } finally {release.countDown();WorkoutReadFixtures.observer=null;}
        assertThat(finalizer.accumulated(owner,0).blockedAt()).isEqualTo(monday);assertThat(weeklyRows().getFirst().dirty()).isTrue();
    }
    @Test void concurrentOffIsSerializedAndReactivationNeverImportsTheOldEpoch()throws Exception {
        weight(0,1,1);weight(1,1,1);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);pausePublication(entered,release);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var publish=pool.submit(this::finish);assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
            var off=pool.submit(()->app.getBean(DeloadAnalysisService.class).save(owner,new DeloadAnalysisRequests.Save(0,1,false,"Asia/Seoul")));
            try {assertThatThrownBy(()->off.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            assertThat(publish.get(20,TimeUnit.SECONDS).confirmed()).isEqualTo(1);assertThat(off.get(20,TimeUnit.SECONDS).enabled()).isFalse();
        } finally {release.countDown();WorkoutReadFixtures.observer=null;}
        assertThat(finalizer.runBatch()).isZero();assertThat(finish().status()).isEqualTo(DISABLED);
        var before=weeklyRows().getFirst();var enabled=app.getBean(DeloadAnalysisService.class).save(owner,new DeloadAnalysisRequests.Save(0,2,true,"Asia/Seoul"));
        assertThat(finish().status()).isEqualTo(STALE);assertThat(enabled.epoch()).isEqualTo("3");
        clock("2026-11-15T19:00:00Z");assertThat(finalizer.finalizeWeek(owner,0,3,LocalDate.of(2026,11,2)).confirmed()).isEqualTo(1);
        assertThat(finalizer.accumulated(owner,0).weeks()).isEqualTo(ExactFraction.ZERO);assertThat(weeklyRows().getFirst()).isEqualTo(before);
    }
    @Test void toggleThatWinsTheOwnerLockPreventsAnOldEpochPublication()throws Exception {
        weight(0,1,1);weight(1,1,1);var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var toggle=pool.submit(()->transaction().execute(tx->{repo().lock(owner,0,false);var repository=app.getBean(DeloadAnalysisRepository.class);var old=repository.current(owner,0);repository.append(owner,0,old.change(false,"Asia/Seoul",old.recordedAt().plusSeconds(1)));locked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}return true;}));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();var publish=pool.submit(this::finish);
            try {assertThatThrownBy(()->publish.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
            assertThat(toggle.get(20,TimeUnit.SECONDS)).isTrue();assertThat(publish.get(20,TimeUnit.SECONDS).status()).isEqualTo(DISABLED);
        } finally {release.countDown();}
        assertThat(weeklyRows()).isEmpty();
    }
    @Test void ianaTimeZoneControlsTheSqlDiscoveryAndJavaPublicationBoundary() {
        owner=account(0,false);var repository=app.getBean(DeloadAnalysisRepository.class);
        repository.append(owner,0,new DeloadAnalysisState(1,true,0,from,from,"America/Chicago","TIME_ZONE_SET"));
        weight(0,1,1);weight(1,1,1);clock("2026-11-02T09:59:59Z");
        assertThat(finish().status()).isEqualTo(NOT_DUE);finalizer.runBatch();assertThat(weeklyRows()).noneMatch(r->r.week().equals(monday));
        clock("2026-11-02T10:00:00Z");assertThat(finalizer.runBatch()).isEqualTo(1);
        assertThat(weeklyRows()).anyMatch(r->r.week().equals(monday)&&r.contribution().equals(ExactFraction.of(1,1)));
    }
    @Test void laterStoredWeekCannotJumpOverAnUnprocessedEarlierWeek() {
        weight(7,1,1);weight(8,1,1);clock("2026-11-08T19:00:00Z");
        assertThat(finalizer.finalizeWeek(owner,0,0,monday.plusWeeks(1)).status()).isEqualTo(OUT_OF_ORDER);
        transaction().executeWithoutResult(tx->{repo().lock(owner,0,false);var w=monday.plusWeeks(1);var value=service.calculateLocked(owner,0,0,w,EmailOtpFixtures.CLOCK.instant());repo().reserve(owner,0,0,w,EmailOtpFixtures.CLOCK.instant());var row=repo().rows(owner,0,0).getFirst();repo().confirm(owner,0,0,row,value,DeloadWeeklyPolicy.input(value),DeloadWeeklyPolicy.decide(value.e(),value.n()),EmailOtpFixtures.CLOCK.instant());});
        assertThat(finalizer.accumulated(owner,0).blockedAt()).isEqualTo(monday);assertThat(finalizer.accumulated(owner,0).weeks()).isEqualTo(ExactFraction.ZERO);
        assertThat(finalizer.runBatch()).isEqualTo(1);assertThat(finalizer.accumulated(owner,0).weeks()).isEqualTo(ExactFraction.of(1,1));
    }
    @Test void actualHalfPlansPersistExactThreeQuartersAndSevenTenths() {
        weight(0,1,1);seed(owner,0,1,at(1),1,1,"FINISHED",false,false,true);assertThat(finish().confirmed()).isEqualTo(1);
        var row=weeklyRows().getFirst();assertThat(row.e()).isEqualTo(ExactFraction.of(3,2));assertThat(row.n()).isEqualTo(2);assertThat(row.contribution()).isEqualTo(ExactFraction.of(3,4));
        owner=account(0,true);for(int i=0;i<3;i++)weight(i,1,1);seed(owner,0,3,at(3),1,1,"FINISHED",false,false,true);weight(4,4,1);
        assertThat(finish().confirmed()).isEqualTo(1);row=weeklyRows().getFirst();assertThat(row.e()).isEqualTo(ExactFraction.of(7,2));assertThat(row.n()).isEqualTo(5);assertThat(row.contribution()).isEqualTo(ExactFraction.of(7,10));
    }
    @Test void compareAndSetFailureRollsBackScoresAndRemainsRetryable() {
        weight(0,1,1);weight(1,1,1);var once=new AtomicBoolean();
        WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().contains("SET status='CONFIRMED'")&&once.compareAndSet(false,true)) {
            try(var statement=e.connection().prepareStatement("UPDATE deload_weekly_assessments SET revision=revision+1 WHERE user_id=?")){statement.setObject(1,owner);statement.executeUpdate();}catch(Exception failure){throw new IllegalStateException(failure);}
        }};
        try {assertThat(finalizer.runBatch()).isZero();}finally{WorkoutReadFixtures.observer=null;}
        assertThat(weeklyRows().getFirst().status()).isEqualTo("PENDING");assertThat(weeklyRows().getFirst().e()).isNull();assertThat(finish().confirmed()).isEqualTo(1);
    }
    @Test void g3FailureRollsBackWeeklyMarkerTogetherWithItsDirtyClear() {
        var s=weight(0,1,1);weight(1,1,1);calendar(monday,monday.minusDays(1));var g3=app.getBean(com.overload.overload_server.statistics.DailyTrainingReviewService.class);
        g3.reviewBatch();assertThat(finish().confirmed()).isEqualTo(1);jdbc.update("UPDATE performed_sets SET deleted_at=now() WHERE id=?",s.sets().getFirst());
        var failed=new AtomicBoolean();WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().contains("INSERT INTO daily_training_assessments")) {
            try(var statement=e.connection().prepareStatement("SELECT needs_recheck FROM deload_weekly_assessments WHERE user_id=?")) {
                statement.setObject(1,owner);try(var rows=statement.executeQuery()){if(rows.next()&&rows.getBoolean(1)&&failed.compareAndSet(false,true))throw new IllegalStateException("isolated G3 rollback");}
            }catch(java.sql.SQLException failure){throw new IllegalStateException(failure);}
        }};
        try {g3.reviewBatch();}finally{WorkoutReadFixtures.observer=null;}
        assertThat(failed).isTrue();assertThat(weeklyRows().getFirst().dirty()).isFalse();
        assertThat(jdbc.queryForObject("SELECT needs_recheck FROM daily_training_assessments WHERE user_id=? AND workout_date=?",Boolean.class,owner,monday)).isTrue();
        g3.reviewBatch();assertThat(weeklyRows().getFirst().dirty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT needs_recheck FROM daily_training_assessments WHERE user_id=? AND workout_date=?",Boolean.class,owner,monday)).isFalse();
    }
    @Test void failureInTheThirdWeekRollsBackTheWindowAndRetriesTheOriginalGap() {
        weight(0,1,1);weight(1,1,1);weight(14,1,1);weight(15,1,1);clock("2026-11-15T19:00:00Z");var confirmations=new AtomicInteger();
        WorkoutReadFixtures.observer=e->{if(e.before()&&e.sql().contains("SET status='CONFIRMED'")&&confirmations.incrementAndGet()==3)throw new IllegalStateException("isolated third-week failure");};
        try {assertThat(finalizer.runBatch()).isZero();}finally{WorkoutReadFixtures.observer=null;}
        assertThat(weeklyRows()).hasSize(1);assertThat(weeklyRows().getFirst().week()).isEqualTo(monday);assertThat(weeklyRows().getFirst().status()).isEqualTo("PENDING");assertThat(weeklyRows().getFirst().e()).isNull();
        assertThat(finalizer.runBatch()).isEqualTo(3);assertThat(weeklyRows()).hasSize(3);assertThat(finalizer.accumulated(owner,0).weeks()).isEqualTo(ExactFraction.of(1,1));
    }
    @Test void deletingAnEarlyTravelDateCannotMoveTheAccumulationStartPastItsDirtyWeek() {
        var early=seed(owner,0,-1,from,1,1,"FINISHED",false,false,false,"America/Los_Angeles");weight(0,1,1);weight(1,1,1);
        assertThat(finalizer.runBatch()).isEqualTo(2);assertThat(weeklyRows().getFirst().week()).isEqualTo(monday.minusWeeks(1));
        jdbc.update("UPDATE performed_sets SET deleted_at=now() WHERE id=?",early.sets().getFirst());
        var total=finalizer.accumulated(owner,0);assertThat(total.blockedAt()).isEqualTo(monday.minusWeeks(1));assertThat(total.confirmedThrough()).isNull();
        assertThat(total.weeks()).isEqualTo(ExactFraction.ZERO);assertThat(weeklyRows().getFirst().dirty()).isTrue();assertThat(finalizer.runBatch()).isZero();
    }
}
