package com.overload.overload_server.deload;

import com.overload.overload_server.completion.ExactFraction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigInteger;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class DeloadWeeklyPolicyTests {
    @ParameterizedTest @CsvSource({"1,1,1,RESET,0,1","1,1,2,RESET,0,1","2,1,2,ADD,1,1","3,2,2,ADD,3,4","3,1,4,ADD,3,4","7,2,5,ADD,7,10","0,1,0,RESET,0,1"})
    void thresholdBeforeRatio(int numerator,int denominator,int n,String operation,int addN,int addD) {
        var decision=DeloadWeeklyPolicy.decide(ExactFraction.of(numerator,denominator),n);
        assertThat(decision.operation()).isEqualTo(operation);assertThat(decision.contribution()).isEqualTo(ExactFraction.of(addN,addD));
    }
    @Test void invalidBoundsCannotCreateAWeeklyOperation() {
        assertThatThrownBy(()->DeloadWeeklyPolicy.decide(ExactFraction.of(1,1),0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DeloadWeeklyPolicy.decide(ExactFraction.of(3,1),2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->DeloadWeeklyPolicy.decide(ExactFraction.ZERO,8)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void exactlyOneAndRoundedDisplayOneAreDifferentThresholds() {
        var above=new ExactFraction(new BigInteger("10000000000001"),new BigInteger("10000000000000"));
        assertThat(above.decimal()).isEqualTo("1");assertThat(DeloadWeeklyPolicy.decide(above,2).operation()).isEqualTo("ADD");
        assertThat(DeloadWeeklyPolicy.decide(above,2).contribution()).isEqualTo(new ExactFraction(above.numerator(),above.denominator().multiply(BigInteger.TWO)));
        assertThat(DeloadWeeklyPolicy.decide(ExactFraction.of(1,1),2).operation()).isEqualTo("RESET");
    }
    @ParameterizedTest @CsvSource({"Asia/Seoul,2026-11-08T19:00:00Z","America/Chicago,2026-11-09T10:00:00Z","Europe/Berlin,2026-11-09T03:00:00Z"})
    void fixedZoneMondayFourAmIsInclusive(String zone,String instant) {
        var week=LocalDate.of(2026,11,2);var due=Instant.parse(instant);
        assertThat(DeloadWeeklyPolicy.dueAt(week,zone)).isEqualTo(due);
        assertThat(DeloadWeeklyPolicy.lastDue(due.minusNanos(1),zone)).isEqualTo(week.minusWeeks(1));
        assertThat(DeloadWeeklyPolicy.lastDue(due,zone)).isEqualTo(week);
    }
    DeloadWeeklyRepository.Row row(LocalDate week,String operation,ExactFraction add,String state,boolean dirty) {
        return new DeloadWeeklyRepository.Row(week,state,dirty,1,1,null,null,operation,add,"input");
    }
    @Test void chronologicalFoldResetsAndNeverAppliesTheSameWeekTwice() {
        var first=LocalDate.of(2026,10,5);var rows=List.of(row(first,"ADD",ExactFraction.of(1,1),"CONFIRMED",false),row(first.plusWeeks(1),"RESET",ExactFraction.ZERO,"CONFIRMED",false),row(first.plusWeeks(2),"ADD",ExactFraction.of(3,4),"CONFIRMED",false));
        var a=DeloadWeeklyPolicy.fold(first,first.plusWeeks(2),rows);var b=DeloadWeeklyPolicy.fold(first,first.plusWeeks(2),rows);
        assertThat(a.weeks()).isEqualTo(ExactFraction.of(3,4));assertThat(a).isEqualTo(b);assertThat(a.blockedAt()).isNull();
    }
    @Test void gapsPendingAndDirtyStopBeforeLaterConfirmedResults() {
        var first=LocalDate.of(2026,10,5);var later=row(first.plusWeeks(1),"ADD",ExactFraction.of(1,1),"CONFIRMED",false);
        for(var rows:List.of(List.of(later),List.of(row(first,null,null,"PENDING",false),later),List.of(row(first,"ADD",ExactFraction.of(1,1),"CONFIRMED",true),later))) {
            var result=DeloadWeeklyPolicy.fold(first,first.plusWeeks(1),rows);assertThat(result.weeks()).isEqualTo(ExactFraction.ZERO);assertThat(result.blockedAt()).isEqualTo(first);assertThat(result.confirmedThrough()).isNull();
        }
    }
    @Test void inputIdentityUsesTheFullSourceVectorAndIgnoresObservationTime() {
        var f=new DeloadCandidateCalculatorTests();var facts=List.of(f.weight(0,1,1));
        var a=DeloadCandidateCalculator.calculate(f.owner,0,f.epoch(),f.monday,f.observed,facts,Map.of(),null,null);
        var b=DeloadCandidateCalculator.calculate(f.owner,0,f.epoch(),f.monday,f.observed.plusSeconds(60),facts,Map.of(),null,null);
        assertThat(DeloadWeeklyPolicy.input(a)).isEqualTo(DeloadWeeklyPolicy.input(b));
        var c=DeloadCandidateCalculator.calculate(f.owner,0,f.epoch(),f.monday,f.observed,facts,Map.of(f.monday.plusDays(1),f.daily("UNKNOWN",true)),null,null);
        assertThat(DeloadWeeklyPolicy.input(c)).isNotEqualTo(DeloadWeeklyPolicy.input(a));assertThat(c.sourceRevisions()).hasSize(7);
    }
    @Test void replacingAnOldResetReplaysItsTrustedPrefixInsteadOfAddingToTheLastTotal() {
        var first=LocalDate.of(2026,10,5);var rows=new ArrayList<DeloadWeeklyRepository.Row>();
        for(int i=0;i<3;i++)rows.add(row(first.plusWeeks(i),"ADD",ExactFraction.of(1,1),"CONFIRMED",false));
        rows.add(row(first.plusWeeks(3),"RESET",ExactFraction.ZERO,"CONFIRMED",false));
        rows.add(row(first.plusWeeks(4),"ADD",ExactFraction.of(1,1),"CONFIRMED",false));
        assertThat(DeloadWeeklyPolicy.fold(first,first.plusWeeks(4),rows).weeks()).isEqualTo(ExactFraction.of(1,1));
        rows.set(3,row(first.plusWeeks(3),"RESET",ExactFraction.ZERO,"CONFIRMED",true));
        var blocked=DeloadWeeklyPolicy.fold(first,first.plusWeeks(4),rows);assertThat(blocked.weeks()).isEqualTo(ExactFraction.of(3,1));assertThat(blocked.blockedAt()).isEqualTo(first.plusWeeks(3));
        rows.set(3,row(first.plusWeeks(3),"ADD",ExactFraction.of(3,4),"CONFIRMED",false));
        assertThat(DeloadWeeklyPolicy.fold(first,first.plusWeeks(4),rows).weeks()).isEqualTo(ExactFraction.of(19,4));
    }
    @Test void excludedWeekPreservesBothItsPrefixAndFollowingContributionButDirtyStillBlocks() {
        var first=LocalDate.of(2026,10,5);var rows=new ArrayList<>(List.of(
            row(first,"ADD",ExactFraction.of(1,1),"CONFIRMED",false),row(first.plusWeeks(1),null,null,"EXCLUDED",false),
            row(first.plusWeeks(2),"ADD",ExactFraction.of(3,4),"CONFIRMED",false)));
        assertThat(DeloadWeeklyPolicy.fold(first,first.plusWeeks(2),rows).weeks()).isEqualTo(ExactFraction.of(7,4));
        rows.set(1,row(first.plusWeeks(1),null,null,"EXCLUDED",true));var blocked=DeloadWeeklyPolicy.fold(first,first.plusWeeks(2),rows);
        assertThat(blocked.weeks()).isEqualTo(ExactFraction.of(1,1));assertThat(blocked.blockedAt()).isEqualTo(first.plusWeeks(1));
    }
    @Test void exclusionRequiresOnlyOutsideIntervalAndNeverResolvesUnknownActiveOrUninspectedInputs() {
        var f=new DeloadCandidateCalculatorTests();
        var outside=DeloadCandidateCalculator.calculate(f.owner,0,f.epoch(),f.monday.minusWeeks(1),f.observed,List.of(),Map.of(),null,null);
        assertThat(DeloadWeeklyPolicy.excluded(outside)).isTrue();assertThat(DeloadWeeklyPolicy.resolvable(outside)).isTrue();
        var unknown=DeloadCandidateCalculator.calculate(f.owner,0,f.epoch(),f.monday,f.observed,List.of(f.cardio(0,100000,null)),Map.of(),null,null);
        assertThat(DeloadWeeklyPolicy.excluded(unknown)).isFalse();assertThat(DeloadWeeklyPolicy.resolvable(unknown)).isFalse();
        var active=f.weight(0,0,1);active=f.alter(active,active.startedAt(),"ACTIVE",active.initialJson());
        var waiting=DeloadCandidateCalculator.calculate(f.owner,0,f.epoch(),f.monday,f.observed,List.of(active),Map.of(),null,f.monday);
        assertThat(DeloadWeeklyPolicy.excluded(waiting)).isFalse();assertThat(DeloadWeeklyPolicy.resolvable(waiting)).isFalse();
        var zero=DeloadCandidateCalculator.calculate(f.owner,0,f.epoch(),f.monday,f.observed,List.of(),Map.of(),null,null);
        assertThat(DeloadWeeklyPolicy.excluded(zero)).isFalse();assertThat(DeloadWeeklyPolicy.resolvable(zero)).isTrue();
        assertThat(DeloadWeeklyPolicy.decide(zero.e(),zero.n()).operation()).isEqualTo("RESET");
    }
}
