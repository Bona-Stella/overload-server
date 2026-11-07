package com.overload.overload_server.statistics;

import com.overload.overload_server.routine.*;
import com.overload.overload_server.workoutsession.*;
import org.junit.jupiter.api.Test;
import java.math.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.overload.overload_server.statistics.StatisticsCalculator.*;

class PlanningCalculatorTests {
    final UUID owner=UUID.randomUUID(),routine=UUID.randomUUID(),standard=UUID.randomUUID();
    final LocalDate monday=LocalDate.of(2026,11,9);
    final StatisticsQuery period=new StatisticsQuery(monday,monday.plusDays(7));
    final List<UUID> placements=java.util.stream.IntStream.range(0,4).mapToObj(i->UUID.randomUUID()).toList();
    RoutineResponses.Detail plan(int sets,String version) {
        return new RoutineResponses.Detail(owner,"0",routine,version,"routine",placements.stream().map(id->new RoutineResponses.Weight(id,standard,"lift",null,null,sets,6,10,"80","80",null)).toList(),List.of());
    }
    WeeklySchedule.Version schedule(){return new WeeklySchedule.Version("1",monday,"Asia/Seoul",monday.minusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant(),List.of(1,2,4,5).stream().map(day->new WeeklySchedule.Assignment(day,routine)).toList());}
    Session session(int weekday,int sets){return new Session(UUID.randomUUID(),monday.plusDays(weekday-1),new InitialPlan(2,routine,"routine","0",placements.stream().map(id->new InitialPlan.Weight(UUID.randomUUID(),id,standard,"lift",null,null,sets,6,10,"80","80")).toList(),List.of()),new WorkoutSnapshotData.Plan(2,List.of(),List.of()));}
    List<SetFact> completed(Session session,int count,int sets){var result=new ArrayList<SetFact>();for(int n=0;n<count;n++)for(int s=0;s<sets;s++)result.add(new SetFact(UUID.randomUUID(),session.id(),session.initial().weightExercises().get(n).sessionExerciseId(),standard,"lift",BigDecimal.TEN,8,null));return result;}
    PlanningCalculator.Evidence evidence(List<Session> sessions,List<PlanningCalculator.Revision> revisions) {
        return new PlanningCalculator.Evidence(List.of(schedule()),revisions,sessions.stream().map(s->new PlanningCalculator.Anchor(s.id(),s.date(),s.date().atTime(10,0).toInstant(ZoneOffset.UTC),s.initial())).toList());
    }
    List<PlanningCalculator.Revision> baseline(){return List.of(new PlanningCalculator.Revision(monday.minusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant(),false,plan(1,"0")));}
    PlanningCalculator.Result calc(List<Session> sessions,List<SetFact> sets,PlanningCalculator.Evidence evidence){return PlanningCalculator.calculate(owner,0,period,new Input(sessions,sets,List.of(),List.of(),List.of()),evidence);}

    @Test void fourPlannedDaysIncludeMissedDayAnd225EffectiveDaysYield5625Percent() {
        var a=session(1,1);var b=session(2,1);var c=session(4,1);var sets=new ArrayList<SetFact>();sets.addAll(completed(a,4,1));sets.addAll(completed(b,2,1));sets.addAll(completed(c,3,1));
        var result=calc(List.of(a,b,c),sets,evidence(List.of(a,b,c),baseline()));
        assertThat(result.knownPlannedDays()).isEqualTo(4);assertThat(result.unknownDays()).isZero();assertThat(result.unverifiableDays()).isZero();
        assertThat(result.effective().decimal()).isEqualTo("2.25");assertThat(result.effective().percent(4)).isEqualTo("56.25");
        assertThat(result.days().get(4).status()).isEqualTo("PLANNED");assertThat(result.days().get(4).effectiveDay()).isEqualTo("0");
    }
    @Test void partialSetsAndAddedExercisesDoNotSatisfyInitialPlanEvenWhenAppliedPlanIsLowered() {
        var a=session(1,4);var sets=new ArrayList<>(completed(a,4,3));
        sets.add(new SetFact(UUID.randomUUID(),a.id(),UUID.randomUUID(),standard,"extra",BigDecimal.TEN,8,null));
        var result=calc(List.of(a),sets,evidence(List.of(a),baseline()));
        assertThat(result.effective().decimal()).isEqualTo("0");assertThat(result.days().getFirst().completedExercises()).isZero();
        assertThat(result.days().getFirst().plan().weightPlacements()).allSatisfy(w->assertThat(w.setCount()).isEqualTo(4));
    }
    @Test void bestSingleSessionWinsWithoutCombiningDisjointHalfSessionsAndRestDayDoesNotFillPlan() {
        var a=session(1,1);var b=session(1,1);var rest=session(3,1);var sets=new ArrayList<>(completed(a,2,1));
        for(int n=2;n<4;n++)sets.add(new SetFact(UUID.randomUUID(),b.id(),b.initial().weightExercises().get(n).sessionExerciseId(),standard,"lift",BigDecimal.TEN,8,null));
        sets.addAll(completed(rest,4,1));
        var result=calc(List.of(a,b,rest),sets,evidence(List.of(a,b,rest),baseline()));
        assertThat(result.effective().decimal()).isEqualTo("0.5");assertThat(result.days().get(2).status()).isEqualTo("REST");
    }
    @Test void withoutScheduleEvidenceThePastIsUnknownNotAnInventedZeroDaySchedule() {
        var a=session(1,1);var result=calc(List.of(a),completed(a,4,1),new PlanningCalculator.Evidence(List.of(),baseline(),List.of()));
        assertThat(result.unknownDays()).isEqualTo(7);assertThat(result.days()).allSatisfy(d->assertThat(d.status()).isEqualTo("UNKNOWN"));
    }
    @Test void thursdayFirstRegistrationExcludesEarlierDaysAndCountsOnlyThreeRemainingPlannedDays() {
        var thursday=monday.plusDays(3);
        var first=new WeeklySchedule.Version("1",thursday,"Asia/Seoul",thursday.atStartOfDay(ZoneOffset.UTC).toInstant(),List.of(1,4,5,6).stream().map(d->new WeeklySchedule.Assignment(d,routine)).toList());
        var a=session(4,1);var b=session(5,1);var extra=session(1,1);var sets=new ArrayList<>(completed(a,4,1));sets.addAll(completed(b,4,1));sets.addAll(completed(extra,4,1));
        var proof=new PlanningCalculator.Evidence(List.of(first),baseline(),evidence(List.of(a,b),baseline()).anchors());
        var result=calc(List.of(a,b,extra),sets,proof);
        assertThat(result.knownPlannedDays()).isEqualTo(3);assertThat(result.unknownDays()).isZero();
        assertThat(result.effective().decimal()).isEqualTo("2");assertThat(result.effective().percent(3)).isEqualTo("66.666667");
        assertThat(result.days().subList(0,3)).allSatisfy(d->{assertThat(d.status()).isEqualTo("BEFORE_REGISTRATION");assertThat(d.plan()).isNull();assertThat(d.effectiveDay()).isNull();});
        assertThat(result.days().get(5).effectiveDay()).isEqualTo("0");
        var next=PlanningCalculator.calculate(owner,0,new StatisticsQuery(monday.plusDays(7),monday.plusDays(14)),new Input(List.of(),List.of(),List.of(),List.of(),List.of()),proof);
        assertThat(next.knownPlannedDays()).isEqualTo(4);assertThat(next.unknownDays()).isZero();
        var past=PlanningCalculator.calculate(owner,0,new StatisticsQuery(monday.minusDays(7),monday),new Input(List.of(),List.of(),List.of(),List.of(),List.of()),proof);
        assertThat(past.unknownDays()).isEqualTo(7);
    }
    @Test void sundayRegistrationWithNoRemainingWorkoutDayHasKnownZeroDenominator() {
        var first=new WeeklySchedule.Version("1",monday.plusDays(6),"Asia/Seoul",Instant.EPOCH,List.of(new WeeklySchedule.Assignment(1,routine)));
        var result=calc(List.of(),List.of(),new PlanningCalculator.Evidence(List.of(first),baseline(),List.of()));
        assertThat(result.knownPlannedDays()).isZero();assertThat(result.unknownDays()).isZero();assertThat(result.effective().decimal()).isEqualTo("0");
        assertThat(result.days().getLast().status()).isEqualTo("REST");
    }
    @Test void routineEditsOnlyAffectSubsequentDaysAndDeletedSessionStillAnchorsOriginalDayPlan() {
        var a=session(1,1);var revisions=new ArrayList<>(baseline());
        revisions.add(new PlanningCalculator.Revision(monday.plusDays(2).atTime(18,0).toInstant(ZoneOffset.UTC),false,plan(2,"1")));
        var anchors=evidence(List.of(a),revisions);
        var result=calc(List.of(),List.of(),anchors);
        assertThat(result.days().getFirst().plan().weightPlacements().getFirst().setCount()).isEqualTo(1);
        assertThat(result.days().get(1).plan().weightPlacements().getFirst().setCount()).isEqualTo(1);
        assertThat(result.days().get(3).plan().weightPlacements().getFirst().setCount()).isEqualTo(2);
        assertThat(result.effective().decimal()).isEqualTo("0");
    }
    @Test void explicitRestScheduleIsKnownAndScheduleChangesDoNotRewriteEarlierWeek() {
        var newer=new WeeklySchedule.Version("2",monday.plusDays(7),"Asia/Seoul",monday.atStartOfDay(ZoneOffset.UTC).toInstant(),List.of());
        var result=calc(List.of(),List.of(),new PlanningCalculator.Evidence(List.of(schedule(),newer),baseline(),List.of()));
        assertThat(result.knownPlannedDays()).isEqualTo(4);assertThat(result.unknownDays()).isZero();
    }
    @Test void knownFullSessionDominatesUnknownUpperBoundWithoutChangingGeneralSelection() {
        UUID exercise=UUID.randomUUID(),activity=UUID.randomUUID();
        var initial=new InitialPlan(2,routine,"original","0",List.of(new InitialPlan.Weight(exercise,UUID.randomUUID(),standard,"lift",null,null,1,6,10,"60","60")),
            List.of(new InitialPlan.Cardio(activity,UUID.randomUUID(),new RoutineResponses.Plan("TREADMILL","run",100,null,new RoutineResponses.Intensity("SPEED","8"),null,null))));
        var a=new Session(UUID.randomUUID(),monday,initial,new WorkoutSnapshotData.Plan(2,List.of(),List.of()));
        var b=new Session(UUID.randomUUID(),monday,initial,a.applied());
        var one=new SetFact(UUID.randomUUID(),a.id(),exercise,standard,"lift",BigDecimal.TEN,8,null);
        var two=new SetFact(UUID.randomUUID(),b.id(),exercise,standard,"lift",BigDecimal.TEN,8,null);
        var unknownCardio=new CardioFact(a.id(),activity,100000,null);
        var uncertain=PlanningCalculator.calculate(owner,0,period,new Input(List.of(a),List.of(one),List.of(unknownCardio),List.of(),List.of()),evidence(List.of(a),baseline()));
        assertThat(uncertain.days().getFirst().status()).isEqualTo("UNVERIFIABLE");assertThat(uncertain.days().getFirst().effectiveDay()).isNull();
        var knownCardio=new CardioFact(b.id(),activity,100000,null,new BigDecimal("8"),null);
        var result=PlanningCalculator.calculate(owner,0,period,new Input(List.of(a,b),List.of(one,two),List.of(unknownCardio,knownCardio),List.of(),List.of()),evidence(List.of(a,b),baseline()));
        assertThat(result.days().getFirst().effectiveDay()).isEqualTo("1");assertThat(result.days().getFirst().selectedSessionId()).isEqualTo(b.id());
        assertThat(result.knownPlannedDays()).isEqualTo(4);
    }
    @Test void cardioTimeAndDistanceUseInitialTargetsAndMissingActualIntensityRemainsUnverifiable() {
        UUID activity=UUID.randomUUID();var target=new RoutineResponses.Plan("TREADMILL","run",100,"1",null,null,null);
        var initial=new InitialPlan(2,routine,"r","0",List.of(),List.of(new InitialPlan.Cardio(activity,UUID.randomUUID(),target)));
        var s=new Session(UUID.randomUUID(),monday,initial,new WorkoutSnapshotData.Plan(2,List.of(),List.of()));
        var proof=new PlanningCalculator.Evidence(List.of(schedule()),baseline(),List.of(new PlanningCalculator.Anchor(s.id(),monday,monday.atStartOfDay(ZoneOffset.UTC).toInstant(),initial)));
        var facts=new Input(List.of(s),List.of(),List.of(new CardioFact(s.id(),activity,100000,new BigDecimal("1"))),List.of(),List.of());
        var result=PlanningCalculator.calculate(owner,0,period,facts,proof);assertThat(result.effective().decimal()).isEqualTo("1");
        var speed=new RoutineResponses.Plan("TREADMILL","run",100,null,new RoutineResponses.Intensity("SPEED","8"),null,null);
        var speedInitial=new InitialPlan(2,routine,"r","0",List.of(),List.of(new InitialPlan.Cardio(activity,UUID.randomUUID(),speed)));
        var s2=new Session(s.id(),monday,speedInitial,s.applied());
        var unknown=PlanningCalculator.calculate(owner,0,period,new Input(List.of(s2),List.of(),facts.cardio(),List.of(),List.of()),proof);
        assertThat(unknown.unverifiableDays()).isEqualTo(1);assertThat(unknown.days().getFirst().effectiveDay()).isNull();
        var measured=PlanningCalculator.calculate(owner,0,period,new Input(List.of(s2),List.of(),List.of(new CardioFact(s.id(),activity,100000,new BigDecimal("0.222222"),new BigDecimal("8"),"CALCULATED")),List.of(),List.of()),proof);
        assertThat(measured.unverifiableDays()).isZero();assertThat(measured.days().getFirst().effectiveDay()).isEqualTo("1");
        var slow=PlanningCalculator.calculate(owner,0,period,new Input(List.of(s2),List.of(),List.of(new CardioFact(s.id(),activity,100000,new BigDecimal("0.194444"),new BigDecimal("7"),"CALCULATED")),List.of(),List.of()),proof);
        assertThat(slow.unverifiableDays()).isZero();assertThat(slow.days().getFirst().effectiveDay()).isEqualTo("0");
        var shortRun=PlanningCalculator.calculate(owner,0,period,new Input(List.of(s2),List.of(),List.of(new CardioFact(s.id(),activity,50000,null)),List.of(),List.of()),proof);
        assertThat(shortRun.unverifiableDays()).isZero();assertThat(shortRun.days().getFirst().effectiveDay()).isEqualTo("0");
    }
}
