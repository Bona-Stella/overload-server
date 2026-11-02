package com.overload.overload_server.statistics;

import com.overload.overload_server.workoutsession.*;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.overload.overload_server.statistics.StatisticsCalculator.*;

class StatisticsCalculatorTests {
    @Test void explicitBodyweightExtraUsesOnlyActualRepsAcrossExercisesMusclesGroupsAndPeriods(){
        var extra=new SetFact(UUID.randomUUID(),session,item,standard,"squat",new BigDecimal("80"),8,"BODYWEIGHT",new BigDecimal("10.000001"));
        var zero=new SetFact(UUID.randomUUID(),session,item,standard,"squat",new BigDecimal("80"),5,"BODYWEIGHT",BigDecimal.ZERO);
        var links=List.of(new Mapping(standard,UUID.randomUUID(),"one","LEGS","PRIMARY"),new Mapping(standard,UUID.randomUUID(),"two","LEGS","PRIMARY"),new Mapping(standard,UUID.randomUUID(),"assist","LEGS","SECONDARY"));
        var input=new Input(List.of(session(4,2)),List.of(set("70",10,"BODYWEIGHT"),set("0",10,"BODYWEIGHT"),extra,zero),List.of(),links,List.of());
        for(var period:List.of(query,new StatisticsQuery(day.withDayOfMonth(1),day.plusMonths(1).withDayOfMonth(1)))){
            var result=StatisticsCalculator.calculate(owner,0,period,input);
            assertThat(result.summary()).isEqualTo(new StatisticsResponse.Weight("4","4","33","80.000008","2"));
            assertThat(result.exercises()).singleElement().extracting(StatisticsResponse.Exercise::weights).isEqualTo(result.summary());
            assertThat(result.muscles()).hasSize(3).allSatisfy(m->assertThat(m.weights()).isEqualTo(result.summary()));
            assertThat(result.groups()).hasSize(2).allSatisfy(g->assertThat(g.weights()).isEqualTo(result.summary()));
            assertThat(result.days()).filteredOn(d->d.date().equals(day)).singleElement().extracting(StatisticsResponse.Day::weights).isEqualTo(result.summary());
        }
        assertThat(calculate(List.of(zero),List.of()).summary().volumeLoadKg()).isEqualTo("0");
        assertThat(calculate(List.of(extra,set("20",3,"DUMBBELL")),List.of()).summary().volumeLoadKg()).isEqualTo("140.000008");
    }
    @Test void cardioDistancesAreSeparatedByKindAndProvenanceWhileAllActivityTimeIsAdded(){
        UUID session=UUID.randomUUID();var ids=java.util.stream.IntStream.range(0,5).mapToObj(i->UUID.randomUUID()).toList();
        var mapper=tools.jackson.databind.json.JsonMapper.builder().build();var plans=new ArrayList<WorkoutSnapshotData.CardioPlan>();
        for(int i=0;i<5;i++)plans.add(new WorkoutSnapshotData.CardioPlan(ids.get(i),mapper.valueToTree(new com.overload.overload_server.routine.RoutineResponses.Plan(i<3?"TREADMILL":i==3?"BIKE":"STAIRS","name"+i,600,null,null,null,null))));
        var source=new Session(session,LocalDate.of(2026,11,1),new InitialPlan(2,UUID.randomUUID(),"r","0",List.of(),List.of()),new WorkoutSnapshotData.Plan(2,List.of(),plans));
        var input=new Input(List.of(source),List.of(),List.of(new CardioFact(session,ids.get(0),1800000,new BigDecimal("3.8"),new BigDecimal("8"),"MANUAL"),
            new CardioFact(session,ids.get(1),1800000,new BigDecimal("4"),new BigDecimal("8"),"CALCULATED"),new CardioFact(session,ids.get(2),600000,new BigDecimal("1.25")),
            new CardioFact(session,ids.get(3),3600000,new BigDecimal("20"),null,"MANUAL"),new CardioFact(session,ids.get(4),600000,null)),List.of(),List.of());
        var totals=cardioByKind(input);assertThat(totals).hasSize(3);
        assertThat(totals).filteredOn(t->t.kind().equals("TREADMILL")).singleElement().satisfies(t->{assertThat(t.activeMs()).isEqualTo("4200000");assertThat(t.distanceKm()).isEqualTo("9.05");assertThat(t.manualDistanceKm()).isEqualTo("3.8");assertThat(t.calculatedDistanceKm()).isEqualTo("4");assertThat(t.unknownSourceDistanceKm()).isEqualTo("1.25");});
        assertThat(totals).filteredOn(t->t.kind().equals("STAIRS")).singleElement().satisfies(t->{assertThat(t.distanceKm()).isNull();assertThat(t.recordsWithoutDistance()).isEqualTo("1");});
        var stats=StatisticsCalculator.calculate(UUID.randomUUID(),0,new StatisticsQuery(source.date(),source.date().plusDays(1)),input);assertThat(stats.cardioActiveMs()).isEqualTo("8400000");assertThat(stats.cardio()).hasSize(5);
    }
    final UUID owner=UUID.randomUUID(), session=UUID.randomUUID(), standard=UUID.randomUUID(), item=UUID.randomUUID();
    final LocalDate day=LocalDate.of(2026,11,1);
    final StatisticsQuery query=new StatisticsQuery(day.minusDays(6),day.plusDays(1));
    Session session(int planned, int applied) {
        return new Session(session,day,new InitialPlan(2,UUID.randomUUID(),"original","0",List.of(
                new InitialPlan.Weight(item,UUID.randomUUID(),standard,"squat",null,null,planned,6,10,"80","80")),List.of()),
                new WorkoutSnapshotData.Plan(2,List.of(new WorkoutSnapshotData.WeightPlan(item,applied,6,10,null,null)),List.of()));
    }
    SetFact set(String kg,int reps,String load) { return new SetFact(UUID.randomUUID(),session,item,standard,"squat",new BigDecimal(kg),reps,load); }
    StatisticsResponse calculate(List<SetFact> sets,List<Mapping> links) {
        return StatisticsCalculator.calculate(owner,0,query,new Input(List.of(session(4,2)),sets,List.of(),links,List.of()));
    }
    @Test void actualRepetitionsProduce2140KgAndInitialPlanSurvivesAppliedPlanReduction() {
        var result=calculate(List.of(set("80",10,null),set("80",8,null),set("70",10,null)),List.of());
        assertThat(result.summary()).isEqualTo(new StatisticsResponse.Weight("4","3","28","2140","0"));
        assertThat(result.unmappedCompletedSets()).isEqualTo("3");
        assertThat(result.plannedWorkoutDays()).isNull();assertThat(result.routineAchievementPercent()).isNull();
        assertThat(result.days()).hasSize(7);
        assertThat(result.days().getLast().weights()).isEqualTo(result.summary());
        assertThat(result.days().getFirst().weights().completedSets()).isEqualTo("0");
    }
    @Test void multiplePrimaryMusclesCountFullyButSameGroupAndOverallOnlyOnceAndSecondarySeparately() {
        UUID quads=UUID.randomUUID(),glutes=UUID.randomUUID(),assist=UUID.randomUUID();
        var first=new Mapping(standard,quads,"quads","LEGS","PRIMARY");
        var result=calculate(List.of(set("80",10,null),set("80",10,null),set("80",10,null)),List.of(first,first,
                new Mapping(standard,glutes,"glutes","LEGS","PRIMARY"),new Mapping(standard,assist,"assist","LEGS","SECONDARY")));
        assertThat(result.summary().completedSets()).isEqualTo("3");
        assertThat(result.muscles()).hasSize(3).allSatisfy(m->{assertThat(m.weights().completedSets()).isEqualTo("3");assertThat(m.weights().volumeLoadKg()).isEqualTo("2400");});
        assertThat(result.groups()).hasSize(2).allSatisfy(g->{assertThat(g.weights().completedSets()).isEqualTo("3");assertThat(g.weights().plannedSets()).isEqualTo("4");});
        assertThat(result.unmappedCompletedSets()).isEqualTo("0");
    }
    @Test void additionalExercisesAndExcessSetsRemainActualFactsWithoutInventingPlans() {
        var additional=new SetFact(UUID.randomUUID(),session,UUID.randomUUID(),UUID.randomUUID(),"added",BigDecimal.TEN,5,null);
        var result=calculate(List.of(set("80",10,null),set("80",10,null),set("80",10,null),set("80",10,null),set("80",10,null),additional),List.of());
        assertThat(result.summary().plannedSets()).isEqualTo("4");assertThat(result.summary().completedSets()).isEqualTo("6");
        assertThat(result.exercises()).filteredOn(e->e.name().equals("added")).singleElement().satisfies(e->{assertThat(e.weights().plannedSets()).isEqualTo("0");assertThat(e.weights().completedSets()).isEqualTo("1");});
    }
    @Test void bodyweightDoesNotInventBodyMassOrTreatAmbiguousLegacyNumbersAsExplicitExtraLoad() {
        var bodyweight=calculate(List.of(set("0",10,"BODYWEIGHT"),set("70",8,"BODYWEIGHT")),List.of());
        assertThat(bodyweight.summary().completedSets()).isEqualTo("2");assertThat(bodyweight.summary().reps()).isEqualTo("18");
        assertThat(bodyweight.summary().volumeLoadKg()).isNull();assertThat(bodyweight.summary().setsWithoutVolume()).isEqualTo("2");
        var mixed=calculate(List.of(set("0",10,"BODYWEIGHT"),set("2.500001",3,"DUMBBELL")),List.of());
        assertThat(mixed.summary().volumeLoadKg()).isEqualTo("7.500003");assertThat(mixed.summary().setsWithoutVolume()).isEqualTo("1");
    }
    @Test void cardioKeepsKindsSeparateIncludesActualTimeAndNeverInfersDistanceFromPlannedSpeed() {
        UUID run=UUID.randomUUID(),bike=UUID.randomUUID();
        var initial=new InitialPlan(2,UUID.randomUUID(),"r","0",List.of(),List.of());
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var applied=new WorkoutSnapshotData.Plan(2,List.of(),List.of(
                new WorkoutSnapshotData.CardioPlan(run,json.readTree("{\"kind\":\"TREADMILL\",\"name\":\"running\",\"intensity\":{\"mode\":\"SPEED\",\"value\":\"8\"}}")),
                new WorkoutSnapshotData.CardioPlan(bike,json.readTree("{\"kind\":\"BIKE\",\"name\":\"bike\"}"))));
        var result=StatisticsCalculator.calculate(owner,0,query,new Input(List.of(new Session(session,day,initial,applied)),List.of(),List.of(
                new CardioFact(session,run,1800000,null),new CardioFact(session,bike,1800000,new BigDecimal("20"))),List.of(),List.of()));
        assertThat(result.cardioActiveMs()).isEqualTo("3600000");assertThat(result.summary().completedSets()).isEqualTo("0");
        assertThat(result.cardio()).hasSize(2);
        assertThat(result.cardio()).filteredOn(c->c.kind().equals("TREADMILL")).singleElement().satisfies(c->{assertThat(c.distanceKm()).isNull();assertThat(c.recordsWithoutDistance()).isEqualTo("1");});
        assertThat(result.cardio()).filteredOn(c->c.kind().equals("BIKE")).singleElement().extracting(StatisticsResponse.Cardio::distanceKm).isEqualTo("20");
    }
    @Test void queryRequiresRealDatesSingleValuesAndBoundedExclusiveRange() {
        var params=new LinkedMultiValueMap<String,String>();params.add("fromDate","2026-10-26");params.add("toDateExclusive","2026-11-02");
        assertThat(StatisticsQuery.parse(params)).isEqualTo(query);
        params.set("fromDate","2026-02-30");assertThatThrownBy(()->StatisticsQuery.parse(params)).isInstanceOf(StatisticsFailure.class);
        params.set("fromDate","2026-10-26");params.add("fromDate","2026-10-26");assertThatThrownBy(()->StatisticsQuery.parse(params)).isInstanceOf(StatisticsFailure.class);
        params.set("fromDate","2024-01-01");assertThatThrownBy(()->StatisticsQuery.parse(params)).isInstanceOf(StatisticsFailure.class);
        params.set("fromDate","2026-11-02");assertThatThrownBy(()->StatisticsQuery.parse(params)).isInstanceOf(StatisticsFailure.class);
    }
}
