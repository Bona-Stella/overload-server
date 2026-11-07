package com.overload.overload_server.completion;

import com.overload.overload_server.routine.RoutineResponses;
import com.overload.overload_server.workoutsession.InitialPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class InitialPlanCompletionTests {
    final UUID session=UUID.randomUUID(),weight=UUID.randomUUID(),activity=UUID.randomUUID();
    InitialPlan.Weight weight(UUID id,int sets) {return new InitialPlan.Weight(id,UUID.randomUUID(),UUID.randomUUID(),"lift",null,null,sets,6,10,"60","60");}
    RoutineResponses.Plan goal(String distance,String mode,String value,String incline,String resistance) {
        return new RoutineResponses.Plan("TREADMILL","run",100,distance,mode==null?null:new RoutineResponses.Intensity(mode,value),incline,resistance);
    }
    InitialPlan plan(List<InitialPlan.Weight> weights,RoutineResponses.Plan cardio) {
        return new InitialPlan(2,UUID.randomUUID(),"original","0",weights,cardio==null?List.of():List.of(new InitialPlan.Cardio(activity,UUID.randomUUID(),cardio)));
    }
    InitialPlanCompletion.Result cardio(RoutineResponses.Plan goal,Long ms,String distance,String speed) {
        return InitialPlanCompletion.judge(session,plan(List.of(),goal),Map.of(),Map.of(activity,new InitialPlanCompletion.CardioEvidence(ms,decimal(distance),decimal(speed))));
    }
    BigDecimal decimal(String value) {return value==null?null:new BigDecimal(value);}
    @Test void allInitialWeightExercisesMustCompleteTheirOriginalSets() {
        UUID second=UUID.randomUUID();var p=plan(List.of(weight(weight,4),weight(second,2)),null);
        var counts=new HashMap<>(Map.of(weight,4,second,2));var before=Map.copyOf(counts);
        var result=InitialPlanCompletion.judge(session,p,counts,Map.of());
        assertThat(result.completed()).isEqualTo(2);assertThat(result.planned()).isEqualTo(2);assertThat(result.isExact()).isTrue();
        assertThat(result.lower()).isEqualTo(ExactFraction.of(1,1));assertThat(result.upper()).isEqualTo(result.lower());
        assertThat(counts).isEqualTo(before);assertThat(p.weightExercises().getFirst().setCount()).isEqualTo(4);
    }
    @Test void threeOfFourSetsAndExtraExercisesDoNotCompleteOriginalPlacement() {
        var p=plan(List.of(weight(weight,4)),null);var counts=Map.of(weight,3,UUID.randomUUID(),100);
        var result=InitialPlanCompletion.judge(session,p,counts,Map.of());
        assertThat(result.completed()).isZero();assertThat(result.planned()).isEqualTo(1);assertThat(result.lower()).isEqualTo(ExactFraction.ZERO);
        assertThat(counts.get(weight)).isEqualTo(3);
    }
    @Test void missingWeightFactsAreIncompleteAndExcessSetsDoNotIncreasePlannedCount() {
        var p=plan(List.of(weight(weight,2)),null);
        assertThat(InitialPlanCompletion.judge(session,p,Map.of(),Map.of()).completed()).isZero();
        assertThat(InitialPlanCompletion.judge(session,p,Map.of(weight,5),Map.of()).completed()).isEqualTo(1);
    }
    @Test void originalPlanIsTheOnlyInputEvenWhenCurrentRoutineAndAppliedPlanDiffer() {
        var original=plan(List.of(weight(weight,4)),null);var modified=plan(List.of(weight(weight,2)),null);
        assertThat(InitialPlanCompletion.judge(session,original,Map.of(weight,2),Map.of()).completed()).isZero();
        assertThat(InitialPlanCompletion.judge(session,modified,Map.of(weight,2),Map.of()).completed()).isEqualTo(1);
        assertThat(original.weightExercises().getFirst().setCount()).isEqualTo(4);
    }
    @Test void timeDistanceAndMeasuredSpeedMustMeetInitialTargets() {
        var g=goal("1","SPEED","8",null,null);
        var result=cardio(g,100000L,"1","8");assertThat(result.completed()).isEqualTo(1);assertThat(result.isExact()).isTrue();
        assertThat(cardio(g,99999L,"1","8").completed()).isZero();
        assertThat(cardio(g,100000L,"0.9999","8").completed()).isZero();
        assertThat(cardio(g,100000L,"1","7.9999").completed()).isZero();
    }
    @Test void paceComparisonRetainsTheExistingSpeedTimesPaceRule() {
        var g=goal(null,"PACE","7.5",null,null);
        assertThat(cardio(g,100000L,null,"8").completed()).isEqualTo(1);
        assertThat(cardio(g,100000L,null,"7.9").completed()).isZero();
    }
    @Test void missingRequiredCardioMeasurementsPreserveUnknownBounds() {
        var g=goal("1","SPEED","8",null,null);
        for(var r:List.of(cardio(g,null,"1","8"),cardio(g,100000L,null,"8"),cardio(g,100000L,"1",null))) {
            assertThat(r.completed()).isZero();assertThat(r.unknown()).isEqualTo(1);assertThat(r.isExact()).isFalse();
            assertThat(r.lower()).isEqualTo(ExactFraction.ZERO);assertThat(r.upper()).isEqualTo(ExactFraction.of(1,1));
        }
    }
    @Test void knownFailureStillWinsWhenAnotherRequiredMeasurementIsUnknown() {
        var r=cardio(goal("1","SPEED","8",null,null),100000L,null,"7");
        assertThat(r.unknown()).isZero();assertThat(r.isExact()).isTrue();assertThat(r.completed()).isZero();
    }
    @Test void targetInclineAndResistanceRemainUnknownWithoutActualEvidence() {
        for(var g:List.of(goal(null,null,null,"1",null),goal(null,null,null,null,"3"))) {
            assertThat(cardio(g,100000L,null,null).unknown()).isEqualTo(1);
        }
    }
    @Test void noCompletedCardioRecordIsIncompleteRatherThanAnInventedMeasuredSuccess() {
        var p=plan(List.of(),goal(null,null,null,null,null));
        var r=InitialPlanCompletion.judge(session,p,Map.of(),Map.of());
        assertThat(r.completed()).isZero();assertThat(r.unknown()).isZero();assertThat(r.isExact()).isTrue();
        assertThat(cardio(goal(null,null,null,null,null),1L,null,null).completed()).isZero();
    }
    @Test void mixedSessionHasExactBoundsAndIgnoresAdditionalCardio() {
        var p=plan(List.of(weight(weight,4)),goal("1",null,null,null,null));
        var evidence=Map.of(activity,new InitialPlanCompletion.CardioEvidence(100000L,null,null),UUID.randomUUID(),new InitialPlanCompletion.CardioEvidence(100000L,new BigDecimal("100"),null));
        var r=InitialPlanCompletion.judge(session,p,Map.of(weight,4),evidence);
        assertThat(r.planned()).isEqualTo(2);assertThat(r.completed()).isEqualTo(1);assertThat(r.unknown()).isEqualTo(1);
        assertThat(r.lower()).isEqualTo(ExactFraction.of(1,2));assertThat(r.upper()).isEqualTo(ExactFraction.of(1,1));
    }
    @Test void judgingSeparateHalfSessionsNeverCombinesTheirExercises() {
        UUID second=UUID.randomUUID();var p=plan(List.of(weight(weight,1),weight(second,1)),null);
        var a=InitialPlanCompletion.judge(session,p,Map.of(weight,1),Map.of());
        var b=InitialPlanCompletion.judge(UUID.randomUUID(),p,Map.of(second,1),Map.of());
        assertThat(a.lower()).isEqualTo(ExactFraction.of(1,2));assertThat(b.lower()).isEqualTo(a.lower());
        assertThat(a.completed()).isEqualTo(1);assertThat(b.completed()).isEqualTo(1);
    }
    @Test void emptyPlanIsNotAnExactSuccessfulCompletion() {
        var r=InitialPlanCompletion.judge(session,plan(List.of(),null),Map.of(),Map.of());
        assertThat(r.hasPlan()).isFalse();assertThat(r.isExact()).isFalse();assertThat(r.planned()).isZero();
        assertThat(r.lower()).isEqualTo(ExactFraction.ZERO);assertThat(r.upper()).isEqualTo(ExactFraction.ZERO);
    }
    @Test void damagedInputsCannotBecomeSuccessfulResults() {
        assertThatThrownBy(()->InitialPlanCompletion.judge(session,null,Map.of(),Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->InitialPlanCompletion.judge(session,new InitialPlan(2,null,null,null,null,List.of()),Map.of(),Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->InitialPlanCompletion.judge(session,plan(List.of(weight(weight,0)),null),Map.of(),Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->InitialPlanCompletion.judge(session,plan(List.of(weight(weight,1),weight(weight,1)),null),Map.of(),Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->InitialPlanCompletion.judge(session,plan(List.of(weight(weight,1)),null),Map.of(weight,-1),Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->cardio(goal("broken",null,null,null,null),100000L,"1",null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->cardio(goal(null,"UNKNOWN","8",null,null),100000L,null,"8")).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(longs={-1,-100})
    void negativeCardioTimeIsDamagedEvidence(long ms) {
        assertThatThrownBy(()->cardio(goal(null,null,null,null,null),ms,null,null)).isInstanceOf(IllegalArgumentException.class);
    }
}
