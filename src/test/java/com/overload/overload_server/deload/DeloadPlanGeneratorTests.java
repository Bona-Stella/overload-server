package com.overload.overload_server.deload;

import com.overload.overload_server.routine.RoutineResponses;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.overload.overload_server.deload.DeloadPlan.*;
import static org.assertj.core.api.Assertions.*;

class DeloadPlanGeneratorTests {
    final UUID owner=UUID.randomUUID();final LocalDate today=LocalDate.of(2026,11,8);
    RoutineResponses.Weight weight(String load,String initial,String approved,int sets,int min,int max) {
        return new RoutineResponses.Weight(UUID.randomUUID(),UUID.randomUUID(),"fixture",null,load,sets,min,max,initial,approved,null);
    }
    RoutineResponses.Cardio cardio(int seconds,String speed) {return new RoutineResponses.Cardio(UUID.randomUUID(),new RoutineResponses.Plan("TREADMILL","run",seconds,null,speed==null?null:new RoutineResponses.Intensity("SPEED",speed),null,null),null);}
    Day day(int weekday,List<RoutineResponses.Weight> weights,List<RoutineResponses.Cardio> cardio) {
        return new Day(weekday,new RoutineResponses.Detail(owner,"0",UUID.randomUUID(),"3","source",weights,cardio));
    }
    Classification classification(RoutineResponses.Weight w,String joint,String group,String name) {return new Classification(w.standardExerciseId(),w.exerciseName(),joint,List.of(new Muscle(UUID.randomUUID(),group,name)));}
    Source source(List<Day> days,List<Classification> classes) {return new Source(owner,"0","Asia/Seoul",today,UUID.randomUUID(),"2",days,classes,null);}
    Generated generate(Source s,Method m,Split split) {return DeloadPlanGenerator.generate(s,new Choice(m,split,List.of()));}
    Weight keep(RoutineResponses.Weight w) {return generate(source(List.of(day(1,List.of(w),List.of())),List.of()),Method.KEEP,Split.ORIGINAL).plans().getFirst().weights().getFirst();}
    @Test void supportedMethodsFollowOriginalFrequencyWithoutMinimumTrainingGate() {
        for(int n=0;n<=6;n++) {
            var days=new ArrayList<Day>();for(int d=1;d<=n;d++)days.add(day(d,List.of(),List.of()));
            var methods=DeloadPlanGenerator.options(source(days,List.of())).stream().map(Option::method).distinct().toList();
            assertThat(methods).contains(Method.REST);assertThat(methods.contains(Method.KEEP)).isEqualTo(n>0);
            assertThat(methods.contains(Method.THREE)).isEqualTo(n>=4);assertThat(methods.contains(Method.TWO)).isEqualTo(n>=3);
        }
        assertThat(generate(source(List.of(),List.of()),Method.REST,Split.REST).plans()).isEmpty();
    }
    @Test void equipmentStepsApprovedPrioritySmallWeightsAndBodyweight() {
        assertThat(keep(weight("PLATE","100","80",4,6,10)).weightKg()).isEqualTo("65");
        assertThat(keep(weight("STACK","55",null,4,6,10)).weightKg()).isEqualTo("45");
        assertThat(keep(weight("DUMBBELL","10",null,4,6,10)).weightKg()).isEqualTo("8");
        assertThat(keep(weight(null,"9",null,4,6,10)).weightKg()).isEqualTo("7");
        assertThat(keep(weight("PLATE","2.5",null,4,6,10)).weightKg()).isEqualTo("2.5");
        assertThat(keep(weight("BODYWEIGHT","0",null,4,6,10)).weightKg()).isEqualTo("0");
    }
    @Test void oddSetsAdjustEachRepBoundaryHalfUpAndEvenSetsPreserveRanges() {
        var odd=keep(weight(null,"50",null,3,6,10));assertThat(odd.setCount()).isEqualTo(2);assertThat(odd.minReps()).isEqualTo(5);assertThat(odd.maxReps()).isEqualTo(8);
        var five=keep(weight(null,"50",null,5,10,10));assertThat(five.setCount()).isEqualTo(3);assertThat(five.minReps()).isEqualTo(8);
        var one=keep(weight(null,"50",null,1,1,1));assertThat(one.setCount()).isEqualTo(1);assertThat(one.minReps()).isEqualTo(1);
        var even=keep(weight(null,"50",null,4,6,10));assertThat(even.setCount()).isEqualTo(2);assertThat(even.minReps()).isEqualTo(6);assertThat(even.maxReps()).isEqualTo(10);
        assertThat(keep(weight(null,"50",null,Integer.MAX_VALUE,10,10)).setCount()).isEqualTo(1073741824);
    }
    @Test void keepUsesOriginalWeekdaysAndRetainsAllCardioPlacements() {
        var s=source(List.of(day(1,List.of(),List.of(cardio(2100,"10"),cardio(2700,null))),day(5,List.of(weight(null,"50",null,2,8,12)),List.of())),List.of());
        var g=generate(s,Method.KEEP,Split.ORIGINAL);assertThat(g.ready()).isTrue();assertThat(g.plans()).hasSize(2);
        assertThat(g.plans().getFirst().date()).isEqualTo(today.plusDays(1));var cardio=g.plans().getFirst().cardio();assertThat(cardio).hasSize(2);
        assertThat(cardio.getFirst().target().targetSeconds()).isEqualTo(1080);assertThat(cardio.getFirst().target().intensity().value()).isEqualTo("8.5");assertThat(cardio.get(1).target().targetSeconds()).isEqualTo(1380);
        assertThat(s.days().getFirst().routine().cardioPlacements().getFirst().plan().targetSeconds()).isEqualTo(2100);
    }
    Source four(List<RoutineResponses.Weight> weights,List<Classification> classes,int cardioDays) {
        var days=new ArrayList<Day>();for(int d=1;d<=4;d++)days.add(day(d,List.of(weights.get((d-1)%weights.size())),d<=cardioDays?List.of(cardio(2400,"8")):List.of()));return source(days,classes);
    }
    @Test void pplOrderFollowsFirstMainAppearanceAndNeverDuplicatesWeightAcrossDates() {
        var pull=weight(null,"50",null,4,6,10);var legs=weight(null,"50",null,4,6,10);var push=weight(null,"50",null,4,6,10);
        var s=four(List.of(pull,legs,push),List.of(classification(pull,"COMPOUND","BACK","광배근"),classification(legs,"COMPOUND","LEGS","대퇴사두근"),classification(push,"COMPOUND","CHEST","대흉근")),3);
        var g=generate(s,Method.THREE,Split.PPL);assertThat(g.ready()).isTrue();assertThat(g.plans().stream().map(Plan::label)).containsExactly("PULL","LEGS","PUSH");
        assertThat(g.plans().stream().mapToInt(p->p.weights().size()).sum()).isEqualTo(3);assertThat(g.plans().get(1).cardio()).isEmpty();assertThat(g.plans().getFirst().cardio()).hasSize(1);assertThat(g.plans().get(2).cardio()).hasSize(1);
        assertThat(g.plans().stream().map(Plan::date)).containsExactly(today,today.plusDays(2),today.plusDays(4));
    }
    @Test void equalRoundRobinAndFrontBackAndFirstCardioSelection() {
        var front=weight(null,"50",null,4,6,10);var back=weight(null,"50",null,4,6,10);
        var s=four(List.of(front,back),List.of(classification(front,"COMPOUND","CHEST","대흉근"),classification(back,"COMPOUND","BACK","광배근")),1);
        var g=generate(s,Method.TWO,Split.FRONT_BACK);assertThat(g.ready()).isTrue();assertThat(g.plans().stream().map(Plan::label)).containsExactly("FRONT","BACK");assertThat(g.plans().getFirst().cardio()).hasSize(1);assertThat(g.plans().get(1).cardio()).isEmpty();
        var equal=generate(s,Method.THREE,Split.EQUAL);assertThat(equal.plans().getFirst().cardio()).hasSize(1);assertThat(equal.plans().get(1).weights().getFirst().original().id()).isEqualTo(back.id());
        assertThat(generate(s,Method.THREE,Split.PPL).issues()).extracting(Issue::code).contains("SPLIT_UNAVAILABLE");
    }
    @Test void unknownClassificationHoldsOnlyCompressedOptionsAndRestRemainsAvailable() {
        var w=weight(null,"50",null,4,6,10);var s=four(List.of(w),List.of(),0);
        assertThat(generate(s,Method.THREE,Split.EQUAL).issues()).extracting(Issue::code).contains("CLASSIFICATION_REQUIRED");
        assertThat(generate(s,Method.KEEP,Split.ORIGINAL).ready()).isTrue();assertThat(generate(s,Method.REST,Split.REST).ready()).isTrue();
        var c=classification(w,"COMPOUND","SHOULDERS","미분류 어깨");s=four(List.of(w),List.of(c),0);
        assertThat(generate(s,Method.TWO,Split.EQUAL).ready()).isTrue();assertThat(generate(s,Method.TWO,Split.FRONT_BACK).ready()).isFalse();
    }
    @Test void multiPrimaryCoverageKeepsEarliestMainThenSortsCompoundBeforeIsolation() {
        var a=weight(null,"50",null,2,8,10);var b=weight(null,"50",null,2,8,10);var c=weight(null,"50",null,2,8,10);var d=weight(null,"50",null,2,8,10);
        var ma=new Muscle(UUID.randomUUID(),"CHEST","a");var mb=new Muscle(UUID.randomUUID(),"BACK","b");var mc=new Muscle(UUID.randomUUID(),"LEGS","c");var md=new Muscle(UUID.randomUUID(),"ARMS","d");
        var classes=List.of(new Classification(a.standardExerciseId(),"a","ISOLATION",List.of(ma,mb)),new Classification(b.standardExerciseId(),"b","COMPOUND",List.of(mb)),new Classification(c.standardExerciseId(),"c","ISOLATION",List.of(mc)),new Classification(d.standardExerciseId(),"d","COMPOUND",List.of(md)));
        var s=source(List.of(day(1,List.of(a,b,c,d),List.of()),day(2,List.of(a,b,c,d),List.of()),day(3,List.of(a,b,c,d),List.of()),day(4,List.of(a,b,c,d),List.of())),classes);
        var g=generate(s,Method.TWO,Split.EQUAL);assertThat(g.plans().getFirst().weights()).extracting(w->w.original().id()).containsExactly(d.id(),a.id());
        assertThat(g.plans().stream().flatMap(p->p.weights().stream()).map(w->w.original().id())).doesNotContain(b.id()).doesNotHaveDuplicates();
    }
    @Test void floorSetCapAllowsOneSetOnlyWhenAverageSeventyPercentIsBelowOne() {
        var w=weight(null,"50",null,1,10,10);var s=four(List.of(w),List.of(classification(w,"COMPOUND","CHEST","대흉근")),0);
        assertThat(generate(s,Method.TWO,Split.EQUAL).plans().getFirst().weights().getFirst().setCount()).isEqualTo(1);
        var many=weight(null,"50",null,9,10,10);s=four(List.of(many),List.of(classification(many,"COMPOUND","CHEST","대흉근")),0);
        assertThat(generate(s,Method.TWO,Split.EQUAL).plans().getFirst().weights().getFirst().setCount()).isLessThanOrEqualTo(6);
    }
    @Test void undefinedCardioTargetsAndRoundedZeroAreExplicitlyHeld() {
        var c=new RoutineResponses.Cardio(UUID.randomUUID(),new RoutineResponses.Plan("TREADMILL","run",1200,"3",null,null,null),null);
        assertThat(generate(source(List.of(day(1,List.of(),List.of(c))),List.of()),Method.KEEP,Split.ORIGINAL).issues()).extracting(Issue::code).contains("CARDIO_TARGET_POLICY_REQUIRED");
        assertThat(generate(source(List.of(day(1,List.of(),List.of(cardio(30,null)))),List.of()),Method.KEEP,Split.ORIGINAL).issues()).extracting(Issue::code).contains("CARDIO_TIME_ROUNDS_TO_ZERO");
    }
    @Test void customDatesAreBoundedDistinctAndRestCannotHideDates() {
        var w=weight(null,"50",null,2,10,10);var s=four(List.of(w),List.of(classification(w,"COMPOUND","CHEST","대흉근")),0);
        var custom=new Choice(Method.TWO,Split.EQUAL,List.of(today.plusDays(1),today.plusDays(6)));assertThat(DeloadPlanGenerator.generate(s,custom).plans().getFirst().date()).isEqualTo(today.plusDays(1));
        for(var dates:List.of(List.of(today,today),List.of(today,today.plusDays(7)),List.of(today)))assertThatThrownBy(()->DeloadPlanGenerator.generate(s,new Choice(Method.TWO,Split.EQUAL,dates))).isInstanceOf(DeloadPeriodFailure.class);
        assertThatThrownBy(()->DeloadPlanGenerator.generate(s,new Choice(Method.REST,Split.REST,List.of(today)))).isInstanceOf(DeloadPeriodFailure.class);
    }
}
