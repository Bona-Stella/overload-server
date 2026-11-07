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
import static com.overload.overload_server.deload.DeloadCandidateCalculator.Pending.*;
import static org.assertj.core.api.Assertions.*;

/** Schema-valid source fixtures live only in this disposable DB; existing API regressions run separately. */
@Tag("postgres") @Testcontainers
class DeloadWeeklyCandidatesIntegrationTests {
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres:18.6-alpine")
        .withDatabaseName("overload_g4_candidates_isolated").withUsername("overload_test").withPassword(UUID.randomUUID().toString());
    static ConfigurableApplicationContext app;static JdbcTemplate jdbc;static final JsonMapper JSON=JsonMapper.builder().build();
    final LocalDate monday=LocalDate.of(2026,10,26);final Instant from=Instant.parse("2026-10-25T15:00:00Z");
    UUID owner;DeloadWeeklyCandidates service;
    record Session(UUID id,UUID routine,UUID item,List<UUID> sets) { }
    @BeforeAll static void start() {
        assertThat(POSTGRES.getMappedPort(5432)).isNotIn(5432,5433,12296);
        var key=new byte[32];new java.security.SecureRandom().nextBytes(key);
        app=new SpringApplicationBuilder(OverloadServerApplication.class).web(WebApplicationType.SERVLET).run(
            "--spring.profiles.active=local,integration","--spring.datasource.url="+POSTGRES.getJdbcUrl(),"--spring.datasource.username="+POSTGRES.getUsername(),"--spring.datasource.password="+POSTGRES.getPassword(),
            "--server.address=127.0.0.1","--server.port=0","--OTP_HMAC_KEY="+Base64.getEncoder().encodeToString(key),"--OTP_EMAIL_ENABLED=false",
            "--GOOGLE_OIDC_ENABLED=false","--GOOGLE_REAUTH_ENABLED=false","--spring.session.jdbc.cleanup-cron=-","--logging.level.root=WARN","--spring.main.banner-mode=off");
        jdbc=app.getBean(JdbcTemplate.class);
    }
    @AfterAll static void stop() {if(app!=null)app.close();}
    @BeforeEach void setup() {owner=account(0,true);service=app.getBean(DeloadWeeklyCandidates.class);}
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
        UUID routine=routine(user,generation),session=UUID.randomUUID(),item=UUID.randomUUID(),standard=UUID.randomUUID();
        jdbc.update("INSERT INTO standard_exercise(id,name,is_active) VALUES (?,'fixture lift',false)",standard);
        var goal=new RoutineResponses.Plan("TREADMILL","run",100,"1",null,null,null);
        var initial=new InitialPlan(2,routine,"sealed fixture","0",cardio?List.of():List.of(new InitialPlan.Weight(item,UUID.randomUUID(),standard,"lift",null,null,planned,6,10,"60","60")),
            cardio?List.of(new InitialPlan.Cardio(item,UUID.randomUUID(),goal)):List.of());
        String json=damaged?"{\"schemaVersion\":2,\"weightExercises\":null}":JSON.writeValueAsString(initial);
        var applied=new WorkoutSnapshotData.Plan(2,List.of(),List.of());
        jdbc.update("""
            INSERT INTO workout_sessions(id,user_id,data_generation,source_routine_id,started_at,workout_date,started_time_zone,started_device_id,editor_device_id,
            initial_plan_snapshot,applied_plan_snapshot,last_workout_synced_at,created_at,updated_at)
            VALUES (?,?,?,?,?,?,'Asia/Seoul',?,?,?::jsonb,?::jsonb,now(),now(),now())
            """,session,user,generation,routine,Timestamp.from(start),monday.plusDays(day),UUID.randomUUID(),UUID.randomUUID(),json,JSON.writeValueAsString(applied));
        if(cardio)jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind) VALUES (?,?,?,?,'CARDIO')",item,session,user,generation);
        else jdbc.update("INSERT INTO session_exercises(id,session_id,user_id,data_generation,kind,standard_exercise_id) VALUES (?,?,?,?,'WEIGHT',?)",item,session,user,generation,standard);
        var ids=new ArrayList<UUID>();for(int i=1;i<=actual;i++) {var id=UUID.randomUUID();ids.add(id);jdbc.update("INSERT INTO performed_sets(id,session_id,session_exercise_id,ordinal,weight,reps,completed_at,context_kind,approval_state_at_recording) VALUES (?,?,?,?,60,10,now(),'TODAY_ONLY','TODAY_ONLY')",id,session,item,i);}
        if(status.equals("FINISHED"))jdbc.update("UPDATE workout_sessions SET status='FINISHED',ended_at=now(),closed_at=now(),completion_assessment_snapshot='{\"schemaVersion\":2}' WHERE id=?",session);
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
    @Test void scheduleAbsentStillCountsTwoFullDaysAndDoesNotCreateCalendarOrWeeklyRows() {
        weight(0,1,1);seed(owner,0,6,at(6),1,1,"AUTO_CLOSED",false,false);weight(2,2,0);
        var result=read();assertThat(result.calculable()).isTrue();assertThat(result.e()).isEqualTo(ExactFraction.of(2,1));assertThat(result.n()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workout_calendar_control WHERE user_id=?",Integer.class,owner)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_weekly_assessments WHERE user_id=?",Integer.class,owner)).isZero();
    }
    @Test void actualPartialSetsStayEligibleButDeletedSetsSessionsAndOtherOwnersDoNot() {
        var partial=weight(0,4,1);var removed=weight(1,1,1);jdbc.update("UPDATE performed_sets SET deleted_at=now() WHERE session_id=?",removed.id());
        var deleted=weight(2,1,1);jdbc.update("UPDATE workout_sessions SET deleted_at=now() WHERE id=?",deleted.id());
        var other=account(1,true);seed(other,1,3,at(3),1,1,"FINISHED",false,false);
        var result=read();assertThat(result.n()).isEqualTo(1);assertThat(result.e()).isEqualTo(ExactFraction.ZERO);
        assertThat(result.days().getFirst().selectedSession()).isEqualTo(partial.id());
        assertThatThrownBy(()->service.calculate(owner,1,0,monday)).hasMessage("DATA_GENERATION_MISMATCH");
        assertThat(service.calculate(other,1,0,monday).n()).isEqualTo(1);
    }
    @Test void completedPositiveCardioIsActivityButMissingGoalEvidenceRemainsUnknown() {
        for(int day=0;day<5;day++) {
            var s=seed(owner,0,day,at(day),0,0,"AUTO_CLOSED",true,false);
            cardio(s,day==3?"SKIPPED":"COMPLETED",day==1?1:100000,day==2||day==3?null:"1",day==4);
        }
        var result=read();assertThat(result.n()).isEqualTo(3);assertThat(result.e()).isNull();assertThat(result.pending()).contains(UNKNOWN_COMPLETION);
        assertThat(result.lowerE()).isEqualTo(ExactFraction.of(1,1));assertThat(result.upperE()).isEqualTo(ExactFraction.of(2,1));
        assertThat(result.days().get(1).candidates().getFirst().completion().completed()).isZero();
        var invalid=seed(owner,0,5,at(5),0,0,"AUTO_CLOSED",true,false);
        assertThatThrownBy(()->cardio(invalid,"COMPLETED",0,null,false)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void onIntervalsExcludeActivationBeforeAdoptionAndOffStartsAndNeverMixEpochs() {
        append(owner,0,2,false,0,at(2),"DISABLED");append(owner,0,3,true,3,at(4),"ENABLED");
        seed(owner,0,0,from.minusSeconds(1),1,1,"FINISHED",false,false);weight(1,1,1);weight(3,1,1);weight(5,1,1);
        var old=read();var current=service.calculate(owner,0,3,monday);
        assertThat(old.n()).isEqualTo(1);assertThat(old.e()).isEqualTo(ExactFraction.of(1,1));
        assertThat(current.n()).isEqualTo(1);assertThat(current.e()).isEqualTo(ExactFraction.of(1,1));assertThat(current.epoch().from()).isEqualTo(at(4));
    }
    void calendar() {
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,effective_from,time_zone,recorded_at,assignments) VALUES (?,0,1,?,'Asia/Seoul',?,'[]')",owner,monday,Timestamp.from(from));
        jdbc.update("INSERT INTO workout_calendar_control VALUES (?,0,'Asia/Seoul',1,?,?)",owner,monday,monday.plusDays(6));
    }
    void missed(LocalDate date,UUID routine,boolean dirty) {
        jdbc.update("""
            INSERT INTO daily_training_assessments(user_id,data_generation,workout_date,first_missed_at,missed_schedule_version,missed_routine_id,missed_plan_version,state,revision,needs_recheck,reviewed_at)
            VALUES (?,0,?,?,1,?,'0','MISSED',9,?,now()) ON CONFLICT(user_id,data_generation,workout_date) DO UPDATE SET
            first_missed_at=EXCLUDED.first_missed_at,missed_schedule_version=1,missed_routine_id=EXCLUDED.missed_routine_id,missed_plan_version='0',state='MISSED',needs_recheck=EXCLUDED.needs_recheck,reviewed_at=EXCLUDED.reviewed_at
            """,owner,date,Timestamp.from(at(6).plusSeconds(86400)),routine,dirty);
    }
    @Test void g3CurrentConfirmedMissUnionIsDistinctAndCursorCannotConcealDirtyRows() {
        var s=weight(0,1,1);calendar();missed(monday,s.routine(),false);missed(monday.plusDays(1),s.routine(),false);
        var result=read();assertThat(result.calculable()).isTrue();assertThat(result.n()).isEqualTo(2);assertThat(result.e()).isEqualTo(ExactFraction.of(1,1));
        assertThat(result.days().getFirst().g3().missedScheduleVersion()).isEqualTo(1);
        jdbc.update("UPDATE daily_training_assessments SET needs_recheck=true,revision=revision+1 WHERE user_id=? AND workout_date=?",owner,monday.plusDays(1));
        var pending=read();assertThat(pending.n()).isEqualTo(1);assertThat(pending.e()).isNull();assertThat(pending.pending()).contains(REVIEW_DIRTY);
    }
    @Test void activeAndDamagedPlansBlockKnownZeroInsteadOfPublishingSuccess() {
        seed(owner,0,1,at(1),1,1,"FINISHED",false,true);seed(owner,0,0,at(0),1,0,"ACTIVE",false,false);
        var result=read();assertThat(result.n()).isEqualTo(1);assertThat(result.e()).isNull();assertThat(result.pending()).contains(ACTIVE,INVALID_PLAN);
    }
    @Test void midweekFirstScheduleDoesNotMakeEarlierNoScheduleActivityUnverifiable() {
        weight(0,1,1);weight(1,1,1);var thursday=monday.plusDays(3);
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,effective_from,time_zone,recorded_at,assignments) VALUES (?,0,1,?,'Asia/Seoul',?,'[]')",owner,thursday,Timestamp.from(at(3)));
        jdbc.update("INSERT INTO workout_calendar_control VALUES (?,0,'Asia/Seoul',1,?,?)",owner,thursday,monday.plusDays(6));
        var result=read();assertThat(result.calculable()).isTrue();assertThat(result.n()).isEqualTo(2);assertThat(result.e()).isEqualTo(ExactFraction.of(2,1));
        assertThat(result.firstScheduleDate()).isEqualTo(thursday);assertThat(result.reviewBoundary().reviewFrom()).isEqualTo(thursday);
        assertThat(result.days().getFirst().g3().dirty()).isTrue();assertThat(result.days().getFirst().reviewRequired()).isFalse();
    }
    @Test void registeredZeroPlanUsesOrdinaryG3ReviewAndNeverTurnsPartialPerformedIntoOne() {
        weight(0,1,1);weight(2,1,1);calendar();
        var g3=app.getBean(com.overload.overload_server.statistics.DailyTrainingReviewService.class);
        g3.reviewBatch();var result=read();
        assertThat(result.calculable()).isTrue();assertThat(result.n()).isEqualTo(2);assertThat(result.e()).isEqualTo(ExactFraction.of(2,1));
        weight(3,4,1);g3.reviewBatch();var partial=read();
        assertThat(partial.calculable()).isTrue();assertThat(partial.n()).isEqualTo(3);assertThat(partial.e()).isEqualTo(ExactFraction.of(2,1));
        assertThat(partial.days().get(3).g3().state()).isEqualTo("PERFORMED");assertThat(partial.days().get(3).lower()).isEqualTo(ExactFraction.ZERO);
    }
    List<String> rows(String table) {return jdbc.queryForList("SELECT to_jsonb(t)::text FROM "+table+" t ORDER BY to_jsonb(t)::text",String.class);}
    @Test void repeatReadsPreserveAllSourceAndSettingsRowsWhileCorrectionsExposeNewRevision() {
        var s=weight(0,1,1);var tables=List.of("workout_sessions","performed_sets","cardio_results","daily_training_assessments","workout_calendar_control","deload_analysis_versions","deload_weekly_assessments");
        var before=new LinkedHashMap<String,List<String>>();tables.forEach(t->before.put(t,rows(t)));
        var first=read();var second=read();assertThat(second.days()).isEqualTo(first.days());assertThat(second.e()).isEqualTo(first.e());assertThat(second.n()).isEqualTo(first.n());tables.forEach(t->assertThat(rows(t)).isEqualTo(before.get(t)));
        var revision=first.days().getFirst().g3().revision();jdbc.update("UPDATE performed_sets SET deleted_at=now() WHERE id=?",s.sets().getFirst());
        var changed=read();assertThat(changed.n()).isZero();assertThat(changed.days().getFirst().g3().revision()).isGreaterThan(revision);
        assertThat(rows("workout_sessions")).isEqualTo(before.get("workout_sessions"));
    }
    @Test void unsetTimeZoneAndUnknownEpochAreNotCalculatedRestAndReadsDoNotInitializeSettings() {
        var id=account(0,false);var week=service.calculate(id,0,0,monday);assertThat(week.e()).isNull();assertThat(week.pending()).contains(AWAITING_TIME_ZONE);
        assertThatThrownBy(()->service.calculate(id,0,2,monday)).hasMessage("INVALID_REQUEST");
        assertThatThrownBy(()->service.calculate(id,0,0,monday.plusDays(1))).hasMessage("INVALID_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deload_analysis_versions WHERE user_id=?",Integer.class,id)).isEqualTo(1);
    }
}
