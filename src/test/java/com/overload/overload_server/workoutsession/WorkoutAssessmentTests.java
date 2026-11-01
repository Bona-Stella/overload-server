package com.overload.overload_server.workoutsession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;

class WorkoutAssessmentTests {
    final UUID item=UUID.randomUUID(),placement=UUID.randomUUID(),definition=UUID.randomUUID(),routine=UUID.randomUUID();
    InitialPlan initial() { return new InitialPlan(2,routine,"test","0",List.of(new InitialPlan.Weight(item,placement,definition,"test",null,null,3,6,10,"60","60")),List.of()); }
    Plan plan(int count) { return new Plan(2,List.of(new WeightPlan(item,count,6,10,null,null)),List.of()); }
    PerformedSet set(int ordinal,int reps) {
        var set=new PerformedSet(UUID.randomUUID(),new SetValue(UUID.randomUUID(),item,ordinal,"60",reps,Instant.parse("2026-10-31T10:00:00Z"),new Context("INITIAL_APPROVED",null),null,null));
        set.approvalState="INITIAL_APPROVED";set.approvedWeight=new BigDecimal("60");return set;
    }
    @ParameterizedTest @CsvSource({"5,8,8,DECREASE","6,6,6,MAINTAIN","10,10,9,MAINTAIN","10,10,10,INCREASE","11,12,10,INCREASE","15,8,8,MAINTAIN"})
    void rangeDoesNotDisqualifyComparablePerformanceButControlsAdvice(int a,int b,int c,String advice) {
        var result=WorkoutAssessment.evaluate(initial(),plan(3),List.of(set(1,a),set(2,b),set(3,c))).exercises().getFirst();
        assertThat(result.eligible()).isTrue();assertThat(result.advice()).isEqualTo(advice);
    }
    @Test void firstNChoosesOrdinalBeforeApprovalFilterAndIgnoresExtraPerformance() {
        var a=set(1,10);var b=set(2,10);var c=set(3,10);var extra=set(4,1);
        extra.contextKind="TODAY_ONLY";extra.approvalState="TODAY_ONLY";extra.approvedWeight=null;
        var result=WorkoutAssessment.evaluate(initial(),plan(3),List.of(extra,c,a,b)).exercises().getFirst();
        assertThat(result.evaluatedPerformedSetIds()).containsExactly(a.id,b.id,c.id);assertThat(result.advice()).isEqualTo("INCREASE");
        a.contextKind="TODAY_ONLY";a.approvalState="TODAY_ONLY";a.approvedWeight=null;
        result=WorkoutAssessment.evaluate(initial(),plan(3),List.of(extra,c,a,b)).exercises().getFirst();
        assertThat(result.eligible()).isFalse();assertThat(result.reasonCodes()).contains("TEMPORARY_WEIGHT");assertThat(result.advice()).isEqualTo("UNAVAILABLE");
    }
    @Test void deletedPreApprovalFactIsRetainedButNewApprovedPerformancesCanQualify() {
        var old=set(1,8);old.deletedAt=Instant.parse("2026-10-31T10:01:00Z");
        var next=List.of(set(1,8),set(2,8),set(3,8));next.forEach(s->{s.weight=new BigDecimal("62.5");s.approvedWeight=new BigDecimal("62.5");s.approvalState="APPLIED";});
        var all=new ArrayList<>(next);all.add(old);
        var result=WorkoutAssessment.evaluate(initial(),plan(3),all).exercises().getFirst();
        assertThat(result.eligible()).isTrue();assertThat(result.comparisonContext().approvedWeightKg()).isEqualTo("62.5");
        assertThat(old.approvedWeight).isEqualByComparingTo("60");assertThat(old.deletedAt).isNotNull();
        next.getFirst().approvalState="PENDING";next.getFirst().approvedWeight=null;
        assertThat(WorkoutAssessment.evaluate(initial(),plan(3),all).exercises().getFirst().reasonCodes()).contains("NO_APPROVED_CONTEXT");
    }
    @Test void changingCountsThenReturningDoesNotLeaveAnyPermanentDisqualification() {
        var sets=List.of(set(1,8),set(2,8),set(3,8));
        assertThat(WorkoutAssessment.evaluate(initial(),plan(4),sets).exercises().getFirst().reasonCodes()).contains("SET_COUNT_MISMATCH");
        assertThat(WorkoutAssessment.evaluate(initial(),plan(3),sets).exercises().getFirst().eligible()).isTrue();
        var removed=new InitialPlan(2,routine,"test","0",List.of(initial().weightExercises().getFirst(),new InitialPlan.Weight(UUID.randomUUID(),UUID.randomUUID(),definition,"second",null,null,1,6,10,"60","60")),List.of());
        assertThat(WorkoutAssessment.evaluate(removed,plan(3),sets).exercises().getFirst().reasonCodes()).contains("INCOMPLETE_INITIAL_WEIGHT_PLAN");
    }
    @Test void tiesUseUnsignedUuidOrderAfterRepsAndActualEndedAt() {
        var at=Instant.parse("2026-10-31T10:00:00Z");
        var a=new WorkoutAssessment.Benchmark(UUID.fromString("70000000-0000-4000-8000-000000000000"),"1",at,List.of(),List.of(),"30","test");
        var b=new WorkoutAssessment.Benchmark(UUID.fromString("80000000-0000-4000-8000-000000000000"),"1",at,List.of(),List.of(),"30","test");
        assertThat(WorkoutAssessment.benchmarkOrder(a,b)).isNegative();
        var recent=new WorkoutAssessment.Benchmark(b.sessionId(),"1",at.plusSeconds(1),List.of(),List.of(),"30","test");
        assertThat(WorkoutAssessment.benchmarkOrder(recent,a)).isNegative();
    }
}
