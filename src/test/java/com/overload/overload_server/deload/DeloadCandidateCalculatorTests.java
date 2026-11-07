package com.overload.overload_server.deload;

import com.overload.overload_server.completion.*;
import com.overload.overload_server.routine.*;
import com.overload.overload_server.statistics.StatisticsRepository.CompletionSession;
import com.overload.overload_server.workoutsession.InitialPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.overload.overload_server.deload.DeloadCandidateCalculator.*;
import static org.assertj.core.api.Assertions.*;

class DeloadCandidateCalculatorTests {
    String json(InitialPlan plan) {return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(plan); }
    final UUID owner=UUID.randomUUID();final LocalDate monday=LocalDate.of(2026,10,26);
    final Instant from=Instant.parse("2026-10-25T15:00:00Z"),observed=Instant.parse("2026-11-02T06:00:00Z");
    Epoch epoch() {return new Epoch(0,1,from,null,"Asia/Seoul");}
    InitialPlan plan(UUID item,int sets) {return new InitialPlan(2,UUID.randomUUID(),"original","0",List.of(new InitialPlan.Weight(item,UUID.randomUUID(),UUID.randomUUID(),"lift",null,null,sets,6,10,"60","60")),List.of());}
    CompletionSession weight(int day,int actual,int planned) {
        UUID item=UUID.randomUUID();var date=monday.plusDays(day);
        return new CompletionSession(UUID.randomUUID(),date,date.atTime(12,0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),"FINISHED",3,2,"hash",json(plan(item,planned)),actual==0?Map.of():Map.of(item,actual),Map.of());
    }
    CompletionSession alter(CompletionSession s,Instant start,String state,String json) {return new CompletionSession(s.id(),s.date(),start,state,s.version(),s.localRevision(),s.snapshotHash(),json,s.sets(),s.cardio());}
    CompletionSession cardio(int day,long ms,String distance) {
        UUID item=UUID.randomUUID();var base=weight(day,0,1);
        var p=new InitialPlan(2,UUID.randomUUID(),"run","0",List.of(),List.of(new InitialPlan.Cardio(item,UUID.randomUUID(),new RoutineResponses.Plan("TREADMILL","run",100,"1",null,null,null))));
        return new CompletionSession(base.id(),base.date(),base.startedAt(),"AUTO_CLOSED",2,1,null,json(p),Map.of(),Map.of(item,new InitialPlanCompletion.CardioEvidence(ms,distance==null?null:new BigDecimal(distance),null)));
    }
    Week run(List<CompletionSession> sessions) {return calculate(owner,0,epoch(),monday,observed,sessions,Map.of(),null,null);}
    DailyEvidence daily(String state,boolean dirty) {return new DailyEvidence(state,7,dirty,observed,observed.minusSeconds(1),1L,UUID.randomUUID(),"0");}
    WorkoutCalendarRepository.Control calendar(LocalDate through) {return new WorkoutCalendarRepository.Control("Asia/Seoul",1,from,monday,through);}
    @Test void noActivityStartIsExcludedButOneActualSetIsAZeroValuedCandidate() {
        var result=run(List.of(weight(0,0,4),weight(1,1,4)));
        assertThat(result.calculable()).isTrue();assertThat(result.n()).isEqualTo(1);assertThat(result.e()).isEqualTo(ExactFraction.ZERO);
        assertThat(result.days().get(0).candidates()).isEmpty();assertThat(result.days().get(1).candidates()).hasSize(1);
    }
    @Test void independentInitialPlansCountUnscheduledAndRestDayWorkWithoutAnySchedule() {
        var result=run(List.of(weight(0,2,2),weight(6,1,1)));
        assertThat(result.n()).isEqualTo(2);assertThat(result.e()).isEqualTo(ExactFraction.of(2,1));
        assertThat(result.requiredReviewsComplete()).isTrue();assertThat(result.days()).noneMatch(Day::reviewRequired);
    }
    @Test void positiveCardioActualActivityAndGoalAchievementAreDifferent() {
        var failed=run(List.of(cardio(0,1,"1")));assertThat(failed.n()).isEqualTo(1);assertThat(failed.e()).isEqualTo(ExactFraction.ZERO);
        var unknown=run(List.of(cardio(0,100000,null)));assertThat(unknown.e()).isNull();assertThat(unknown.pending()).contains(Pending.UNKNOWN_COMPLETION);
        assertThat(run(List.of(cardio(0,100000,"1"))).e()).isEqualTo(ExactFraction.of(1,1));
        assertThat(run(List.of(cardio(0,0,null))).n()).isZero();
    }
    @Test void differentHalfSessionsAreNeverCombinedAndHigherSingleSessionWins() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();var p=plan(a,1);
        p=new InitialPlan(2,p.routineId(),"original","0",List.of(p.weightExercises().getFirst(),plan(b,1).weightExercises().getFirst()),List.of());
        var base=weight(0,0,1);var x=new CompletionSession(base.id(),base.date(),base.startedAt(),"FINISHED",0,0,null,json(p),Map.of(a,1),Map.of());
        var y=new CompletionSession(UUID.randomUUID(),base.date(),base.startedAt(),"FINISHED",0,0,null,json(p),Map.of(b,1),Map.of());
        var half=run(List.of(x,y));assertThat(half.n()).isEqualTo(1);assertThat(half.e()).isEqualTo(ExactFraction.of(1,2));
        var full=weight(0,1,1);var result=run(List.of(x,y,full));assertThat(result.e()).isEqualTo(ExactFraction.of(1,1));assertThat(result.days().getFirst().selectedSession()).isEqualTo(full.id());
    }
    @Test void actualAndConfirmedMissShareOneDateWhileMissAloneAddsZero() {
        var result=calculate(owner,0,epoch(),monday,observed,List.of(weight(0,1,1)),Map.of(monday,daily("MISSED",false),monday.plusDays(1),daily("MISSED",false)),calendar(monday.plusDays(6)),monday);
        assertThat(result.calculable()).isTrue();assertThat(result.n()).isEqualTo(2);assertThat(result.e()).isEqualTo(ExactFraction.of(1,1));
        assertThat(result.days().getFirst().g3().revision()).isEqualTo(7);assertThat(result.days().get(1).lower()).isEqualTo(ExactFraction.ZERO);
    }
    @Test void performedG3IsNotCompletionOneAndDirtyMissIsNotConfirmed() {
        var result=calculate(owner,0,epoch(),monday,observed,List.of(weight(0,1,4)),Map.of(monday,daily("PERFORMED",false),monday.plusDays(1),daily("MISSED",true)),calendar(monday.plusDays(6)),monday);
        assertThat(result.n()).isEqualTo(1);assertThat(result.lowerE()).isEqualTo(ExactFraction.ZERO);assertThat(result.e()).isNull();assertThat(result.pending()).contains(Pending.REVIEW_DIRTY);
    }
    @Test void unknownAffectsBestValueUnlessACompleteSessionDominatesItsUpperBound() {
        var unknown=cardio(0,100000,null);var first=run(List.of(unknown,weight(0,1,4)));
        assertThat(first.calculable()).isFalse();assertThat(first.lowerE()).isEqualTo(ExactFraction.ZERO);assertThat(first.upperE()).isEqualTo(ExactFraction.of(1,1));
        var dominated=run(List.of(unknown,weight(0,1,1)));assertThat(dominated.calculable()).isTrue();assertThat(dominated.e()).isEqualTo(ExactFraction.of(1,1));
    }
    @Test void activationAndOffBoundariesUseOriginalStartedAtAndDoNotMixEpochs() {
        var bound=from.plusSeconds(12*3600);var until=from.plusSeconds(3*86400);
        var e=new Epoch(4,5,bound,until,"Asia/Seoul");var a=weight(0,1,1);var b=weight(2,1,1);var c=weight(3,1,1);
        var result=calculate(owner,0,e,monday,observed,List.of(alter(a,bound.minusNanos(1),a.status(),a.initialJson()),alter(b,bound,b.status(),b.initialJson()),alter(c,until,c.status(),c.initialJson())),Map.of(),null,null);
        assertThat(result.n()).isEqualTo(1);assertThat(result.e()).isEqualTo(ExactFraction.of(1,1));assertThat(result.epoch().id()).isEqualTo(4);
        assertThat(result.weekStart()).isEqualTo(monday);
    }
    @Test void midweekAdoptionExcludesEarlierDatesAndMissesWithoutMovingMonday() {
        var adopted=from.plusSeconds(2*86400);var e=new Epoch(0,0,adopted,null,"Asia/Seoul");
        var result=calculate(owner,0,e,monday,observed,List.of(weight(0,1,1),weight(2,1,1)),Map.of(monday,daily("MISSED",false)),null,null);
        assertThat(result.weekStart()).isEqualTo(monday);assertThat(result.evaluationFrom()).isEqualTo(monday.plusDays(2));assertThat(result.n()).isEqualTo(1);
    }
    @Test void storedWorkoutDateSurvivesOtherDeviceZoneAcrossTheMondayBoundary() {
        var e=new Epoch(0,1,from.plusSeconds(86400),null,"Asia/Seoul");
        var s=weight(0,1,1);s=alter(s,e.from(),s.status(),s.initialJson());
        var result=calculate(owner,0,e,monday,observed,List.of(s),Map.of(),null,null);
        assertThat(result.days().getFirst().date()).isEqualTo(monday);assertThat(result.n()).isEqualTo(1);
    }
    @Test void missingZoneAndUnreviewedZeroWeekNeverMasqueradeAsKnownRest() {
        var unzoned=calculate(owner,0,new Epoch(0,0,from,null,null),monday,observed,List.of(),Map.of(),null,null);
        assertThat(unzoned.e()).isNull();assertThat(unzoned.pending()).contains(Pending.AWAITING_TIME_ZONE);
        var pending=calculate(owner,0,epoch(),monday,observed,List.of(),Map.of(),calendar(monday.minusDays(1)),monday);
        assertThat(pending.n()).isZero();assertThat(pending.e()).isNull();assertThat(pending.requiredReviewsComplete()).isFalse();
        var checked=calculate(owner,0,epoch(),monday,observed,List.of(),Map.of(),calendar(monday.plusDays(6)),monday);
        assertThat(checked.calculable()).isTrue();assertThat(checked.e()).isEqualTo(ExactFraction.ZERO);
    }
    @Test void cursorCannotHideUnknownActiveOrDirtyRowsAndRevisionIsPreserved() {
        var result=calculate(owner,0,epoch(),monday,observed,List.of(),Map.of(monday,daily("UNKNOWN",false)),calendar(monday.plusDays(6)),monday);
        assertThat(result.e()).isNull();assertThat(result.pending()).contains(Pending.REVIEW_UNKNOWN);
        var active=weight(6,0,1);var open=run(List.of(alter(active,active.startedAt(),"ACTIVE",active.initialJson())));
        assertThat(open.pending()).contains(Pending.ACTIVE);assertThat(open.n()).isZero();assertThat(open.e()).isNull();
    }
    @Test void noScheduleUsesSourceRevisionsWithoutRequiringAFakeCalendarOrG3Misses() {
        var row=daily("UNKNOWN",true);var result=calculate(owner,0,epoch(),monday,observed,List.of(weight(1,1,1)),Map.of(monday.plusDays(1),row),null,null);
        assertThat(result.calculable()).isTrue();assertThat(result.days().get(1).g3()).isEqualTo(row);
    }
    @Test void damagedOrEmptyPlansWithActualActivityStayPending() {
        var s=weight(0,1,1);
        for(String json:List.of("broken",json(new InitialPlan(2,UUID.randomUUID(),"empty","0",List.of(),List.of())))) {
            var result=run(List.of(alter(s,s.startedAt(),s.status(),json)));assertThat(result.n()).isEqualTo(1);assertThat(result.e()).isNull();assertThat(result.pending()).contains(Pending.INVALID_PLAN);
        }
    }
    @Test void futureDatesAndCompletelyOutsideEpochAreNotConfirmedZeroWeeks() {
        var open=calculate(owner,0,epoch(),monday,from,List.of(),Map.of(),null,null);assertThat(open.e()).isNull();assertThat(open.pending()).contains(Pending.OPEN_DATE);
        var outside=calculate(owner,0,new Epoch(0,1,observed,null,"Asia/Seoul"),monday,observed,List.of(),Map.of(),null,null);
        assertThat(outside.e()).isNull();assertThat(outside.pending()).contains(Pending.OUTSIDE_ON_INTERVAL);
    }
    @Test void zeroLengthOnIntervalDoesNotCreateARestWeekOrCountMisses() {
        var instant=from.plusSeconds(3600);
        var result=calculate(owner,0,new Epoch(0,1,instant,instant,"Asia/Seoul"),monday,observed,List.of(),Map.of(monday,daily("MISSED",false)),calendar(monday.plusDays(6)),monday);
        assertThat(result.n()).isZero();assertThat(result.e()).isNull();assertThat(result.pending()).contains(Pending.OUTSIDE_ON_INTERVAL);
    }
    @Test void knownPreRegistrationDatesUseActualWorkButPreG3TransitionScheduleDatesStayPending() {
        var thursday=monday.plusDays(3);
        var control=new WorkoutCalendarRepository.Control("Asia/Seoul",1,from,thursday,monday.plusDays(6));
        var source=List.of(weight(0,1,1),weight(1,1,1));
        var registered=calculate(owner,0,epoch(),monday,observed,source,Map.of(monday,daily("UNKNOWN",true)),control,thursday);
        assertThat(registered.calculable()).isTrue();assertThat(registered.e()).isEqualTo(ExactFraction.of(2,1));
        assertThat(registered.days().getFirst().reviewRequired()).isFalse();assertThat(registered.firstScheduleDate()).isEqualTo(thursday);
        var transitioned=calculate(owner,0,epoch(),monday,observed,source,Map.of(),control,monday);
        assertThat(transitioned.e()).isNull();assertThat(transitioned.pending()).contains(Pending.REVIEW_INCOMPLETE);
    }
    @Test void existingScheduleWithoutControlAndConflictingCalendarZoneStayPending() {
        var missing=calculate(owner,0,epoch(),monday,observed,List.of(),Map.of(),null,monday);
        assertThat(missing.e()).isNull();assertThat(missing.pending()).contains(Pending.REVIEW_INCOMPLETE);
        var wrong=new WorkoutCalendarRepository.Control("UTC",1,from,monday,monday.plusDays(6));
        var conflict=calculate(owner,0,epoch(),monday,observed,List.of(),Map.of(),wrong,monday);
        assertThat(conflict.e()).isNull();assertThat(conflict.pending()).contains(Pending.CALENDAR_ZONE_CONFLICT);
    }

    CompletionSession half(int day) {
        return fraction(day,1,2);
    }
    CompletionSession fraction(int day,int completed,int total) {
        var base=weight(day,0,1);var weights=new ArrayList<InitialPlan.Weight>();var counts=new HashMap<UUID,Integer>();
        for(int i=0;i<total;i++){UUID id=UUID.randomUUID();weights.add(plan(id,1).weightExercises().getFirst());if(i<completed)counts.put(id,1);}
        var p=new InitialPlan(2,UUID.randomUUID(),"fraction","0",weights,List.of());
        return new CompletionSession(base.id(),base.date(),base.startedAt(),"FINISHED",0,0,null,json(p),counts,Map.of());
    }
    @Test void weeklyArithmeticNeverAddsRoundedDisplayValuesOrDividesZeroByZero() {
        var result=run(List.of(fraction(0,1,3),fraction(1,1,3),fraction(2,1,3)));
        assertThat(result.e()).isEqualTo(ExactFraction.of(1,1));assertThat(result.rate()).isEqualTo(ExactFraction.of(1,3));
        assertThat(result.rate().decimal()).isEqualTo("0.333333333333");
        assertThat(run(List.of()).rate()).isNull();
    }
    @ParameterizedTest @CsvSource({"1,1,1,1","1;1,2,1,2","1;half,3,2,2","1;1;1;0,3,1,4","half;half,1,1,2","1;1;1;half;0,7,2,5","1;1;1;1;0,4,1,5"})
    void exactWeeklyExamples(String values,int numerator,int denominator,int expectedN) {
        var sessions=new ArrayList<CompletionSession>();var tokens=values.split(";");
        for(int i=0;i<tokens.length;i++)sessions.add(switch(tokens[i]){case "1"->weight(i,1,1);case "0"->weight(i,1,2);default->half(i);});
        var result=run(sessions);
        assertThat(result.n()).isEqualTo(expectedN);assertThat(result.e()).isEqualTo(ExactFraction.of(numerator,denominator));
        assertThat(result.rate()).isEqualTo(ExactFraction.of(numerator,denominator*expectedN));
    }
}
