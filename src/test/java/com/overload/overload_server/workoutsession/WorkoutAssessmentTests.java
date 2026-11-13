package com.overload.overload_server.workoutsession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;
import static org.mockito.Mockito.*;

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
        var result=WorkoutAssessment.evaluate(initial(),plan(3),List.of(set(1,a),set(2,b),set(3,c)),true).exercises().getFirst();
        assertThat(result.eligible()).isTrue();assertThat(result.advice()).isEqualTo(advice);
    }
    @Test void firstNChoosesOrdinalBeforeApprovalFilterAndIgnoresExtraPerformance() {
        var a=set(1,10);var b=set(2,10);var c=set(3,10);var extra=set(4,1);
        extra.contextKind="TODAY_ONLY";extra.approvalState="TODAY_ONLY";extra.approvedWeight=null;
        var result=WorkoutAssessment.evaluate(initial(),plan(3),List.of(extra,c,a,b),true).exercises().getFirst();
        assertThat(result.evaluatedPerformedSetIds()).containsExactly(a.id,b.id,c.id);assertThat(result.advice()).isEqualTo("INCREASE");
        a.contextKind="TODAY_ONLY";a.approvalState="TODAY_ONLY";a.approvedWeight=null;
        result=WorkoutAssessment.evaluate(initial(),plan(3),List.of(extra,c,a,b),true).exercises().getFirst();
        assertThat(result.eligible()).isFalse();assertThat(result.reasonCodes()).contains("TEMPORARY_WEIGHT");assertThat(result.advice()).isEqualTo("UNAVAILABLE");
    }
    @Test void deletedPreApprovalFactIsRetainedButNewApprovedPerformancesCanQualify() {
        var old=set(1,8);old.deletedAt=Instant.parse("2026-10-31T10:01:00Z");
        var next=List.of(set(1,8),set(2,8),set(3,8));next.forEach(s->{s.weight=new BigDecimal("62.5");s.approvedWeight=new BigDecimal("62.5");s.approvalState="APPLIED";});
        var all=new ArrayList<>(next);all.add(old);
        var result=WorkoutAssessment.evaluate(initial(),plan(3),all,true).exercises().getFirst();
        assertThat(result.eligible()).isTrue();assertThat(result.comparisonContext().approvedWeightKg()).isEqualTo("62.5");
        assertThat(old.approvedWeight).isEqualByComparingTo("60");assertThat(old.deletedAt).isNotNull();
        next.getFirst().approvalState="PENDING";next.getFirst().approvedWeight=null;
        assertThat(WorkoutAssessment.evaluate(initial(),plan(3),all,true).exercises().getFirst().reasonCodes()).contains("NO_APPROVED_CONTEXT");
    }
    @Test void changingCountsThenReturningDoesNotLeaveAnyPermanentDisqualification() {
        var sets=List.of(set(1,8),set(2,8),set(3,8));
        assertThat(WorkoutAssessment.evaluate(initial(),plan(4),sets,true).exercises().getFirst().reasonCodes()).contains("SET_COUNT_MISMATCH");
        assertThat(WorkoutAssessment.evaluate(initial(),plan(3),sets,true).exercises().getFirst().eligible()).isTrue();
        var removed=new InitialPlan(2,routine,"test","0",List.of(initial().weightExercises().getFirst(),new InitialPlan.Weight(UUID.randomUUID(),UUID.randomUUID(),definition,"second",null,null,1,6,10,"60","60")),List.of());
        assertThat(WorkoutAssessment.evaluate(removed,plan(3),sets,true).exercises().getFirst().reasonCodes()).contains("INCOMPLETE_INITIAL_WEIGHT_PLAN");
    }
    @Test void provisionalGuidanceIsPerExerciseWhileOfficialEvaluationStillRequiresTheWholePlan() {
        var original=initial();var other=UUID.randomUUID();
        var extended=new InitialPlan(2,routine,"test","0",List.of(original.weightExercises().getFirst(),
                new InitialPlan.Weight(other,UUID.randomUUID(),definition,"second",null,null,1,6,10,"60","60")),List.of());
        var applied=new Plan(2,List.of(plan(3).weightExercises().getFirst(),new WeightPlan(other,1,6,10,null,null)),List.of());
        var facts=List.of(set(1,10),set(2,10),set(3,10));
        var provisional=WorkoutAssessment.evaluate(extended,applied,facts,false);
        assertThat(provisional.complete()).isFalse();
        assertThat(provisional.exercises().getFirst().advice()).isEqualTo("INCREASE");
        assertThat(provisional.exercises().get(1).eligible()).isFalse();
        assertThat(WorkoutAssessment.evaluate(extended,applied,facts,true).exercises().getFirst().reasonCodes())
                .contains("INCOMPLETE_INITIAL_WEIGHT_PLAN");
    }
    @Test void tiesUseUnsignedUuidOrderAfterRepsAndActualEndedAt() {
        var at=Instant.parse("2026-10-31T10:00:00Z");
        var a=new WorkoutAssessment.Benchmark(UUID.fromString("70000000-0000-4000-8000-000000000000"),"1",at,List.of(),List.of(),"30","test");
        var b=new WorkoutAssessment.Benchmark(UUID.fromString("80000000-0000-4000-8000-000000000000"),"1",at,List.of(),List.of(),"30","test");
        assertThat(WorkoutAssessment.benchmarkOrder(a,b)).isNegative();
        var recent=new WorkoutAssessment.Benchmark(b.sessionId(),"1",at.plusSeconds(1),List.of(),List.of(),"30","test");
        assertThat(WorkoutAssessment.benchmarkOrder(recent,a)).isNegative();
    }

    @ParameterizedTest @ValueSource(strings={"empty","extra-only","temporary","pending","mixed","deleted","count","range","removed","incomplete-second","partial","both","eligible","below-min","above-max"})
    void historyIsReadOnlyWhenTheExistingEvaluationHasAnEligibleExercise(String scenario) {
        var sessions=mock(WorkoutSessionRepository.class);var records=mock(WorkoutSnapshotRepository.class);
        var session=new WorkoutSession();session.id=UUID.randomUUID();session.version=7;
        var original=initial();var applied=plan(3);var stored=new ArrayList<>(List.of(set(1,8),set(2,8),set(3,8)));
        var pastSets=new ArrayList<>(List.of(set(1,8),set(2,8),set(3,8)));
        if(Set.of("incomplete-second","partial","both").contains(scenario)) {
            UUID second=UUID.randomUUID();var first=original.weightExercises().getFirst();
            original=new InitialPlan(2,routine,"test","0",List.of(first,new InitialPlan.Weight(second,UUID.randomUUID(),definition,"second",null,null,3,6,10,"60","60")),List.of());
            applied=WorkoutSnapshotData.initial(original);
            for(int n=1;n<=3;n++) {var next=set(n,8);next.exerciseId=second;stored.add(next);var prior=set(n,8);prior.exerciseId=second;pastSets.add(prior);}
        }
        var pastInitial=original;var pastApplied=WorkoutSnapshotData.initial(original);
        switch(scenario) {
            case "empty"->stored.clear();
            case "extra-only"->{original=new InitialPlan(2,routine,"test","0",List.of(),List.of());applied=WorkoutSnapshotData.initial(original);}
            case "temporary","partial"->{var first=stored.getFirst();first.contextKind="TODAY_ONLY";first.approvalState="TODAY_ONLY";first.approvedWeight=null;}
            case "pending"->{stored.getFirst().approvalState="PENDING";stored.getFirst().approvedWeight=null;}
            case "mixed"->stored.getFirst().weight=new BigDecimal("65");
            case "deleted"->stored.getFirst().deletedAt=Instant.parse("2026-10-31T10:01:00Z");
            case "count"->applied=plan(4);
            case "range"->applied=new Plan(2,List.of(new WeightPlan(item,3,6,11,null,null)),List.of());
            case "removed"->applied=new Plan(2,List.of(),List.of());
            case "incomplete-second"->stored.removeLast();
            case "below-min"->stored.getFirst().reps=5;
            case "above-max"->stored.forEach(s->s.reps=11);
        }
        session.initialPlan=original.json();session.appliedPlan=json(applied);
        var current=WorkoutAssessment.evaluate(original,applied,stored,true);
        boolean eligible=Set.of("partial","both","eligible","below-min","above-max").contains(scenario);
        assertThat(current.exercises().stream().anyMatch(WorkoutAssessment.ExerciseResult::eligible)).isEqualTo(eligible);
        if(scenario.equals("partial"))assertThat(current.exercises().stream().filter(WorkoutAssessment.ExerciseResult::eligible)).hasSize(1);
        when(records.sets(session.id)).thenReturn(stored);
        var assessedAt=Instant.parse("2026-11-01T00:00:00Z");
        for(int historyCount:List.of(0,2)) {
            var past=new ArrayList<WorkoutSession>();
            for(int n=0;n<historyCount;n++) {
                var prior=new WorkoutSession();prior.id=UUID.randomUUID();prior.version=n+1;prior.endedAt=assessedAt.minusSeconds(7200-n*3600);
                prior.initialPlan=pastInitial.json();prior.appliedPlan=json(pastApplied);past.add(prior);
            }
            var batchSets=new ArrayList<PerformedSet>();
            for(var prior:past)for(var source:pastSets) {
                var row=set(source.ordinal,source.reps);row.exerciseId=source.exerciseId;row.sessionId=prior.id;row.id=source.id;batchSets.add(row);
            }
            when(records.setsForSessions(past.stream().map(s->s.id).toList())).thenReturn(batchSets);
            when(sessions.finishedBefore(session)).thenReturn(past);
            var expected=originalResult(session,assessedAt,current,past,pastSets);
            clearInvocations(sessions,records);
            var actual=new WorkoutAssessment(sessions,records).assess(session,assessedAt);
            assertThat(actual).isEqualTo(expected);verify(records).sets(session.id);
            if(eligible) {verify(sessions).finishedBefore(session);if(historyCount>0)verify(records).setsForSessions(past.stream().map(s->s.id).toList());}
            else {verify(sessions,never()).finishedBefore(any());verify(records,never()).setsForSessions(anyList());}
            for(var prior:past)verify(records,never()).sets(prior.id);
        }
    }

    @ParameterizedTest @ValueSource(ints={0,1,99,100,101,200,201})
    void historyBatchesKeepEmptySessionsAndTheLastPartialBatch(int count) {
        var sessions=mock(WorkoutSessionRepository.class);var records=mock(WorkoutSnapshotRepository.class);
        var session=new WorkoutSession();session.id=UUID.randomUUID();session.initialPlan=initial().json();session.appliedPlan=json(plan(3));
        when(records.sets(session.id)).thenReturn(List.of(set(1,8),set(2,8),set(3,8)));
        var past=new ArrayList<WorkoutSession>();
        for(int n=0;n<count;n++) {var p=new WorkoutSession();p.id=UUID.randomUUID();p.initialPlan=initial().json();p.appliedPlan=json(plan(3));past.add(p);}
        when(sessions.finishedBefore(session)).thenReturn(past);
        var batches=new ArrayList<List<UUID>>();
        when(records.setsForSessions(anyList())).thenAnswer(call->{batches.add(List.copyOf(call.getArgument(0)));return List.of();});
        var result=new WorkoutAssessment(sessions,records).assess(session,Instant.EPOCH);
        assertThat(result.exercises().getFirst().eligible()).isTrue();assertThat(result.exercises().getFirst().benchmarkEvidence()).isNull();
        assertThat(batches.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(past.stream().map(p->p.id).toList());
        assertThat(batches).allSatisfy(ids->assertThat(ids.size()).isBetween(1,WorkoutAssessment.HISTORY_BATCH_SIZE));
        assertThat(batches).hasSize((count+WorkoutAssessment.HISTORY_BATCH_SIZE-1)/WorkoutAssessment.HISTORY_BATCH_SIZE);
        if(count>0) {past.getLast().initialPlan="[]";assertThatThrownBy(()->new WorkoutAssessment(sessions,records).assess(session,Instant.EPOCH)).isInstanceOf(tools.jackson.core.JacksonException.class);}
    }

    @ParameterizedTest @ValueSource(ints={101,200,201})
    void bestEvidenceCanComeFromTheLastBatchWhileOtherSessionsAreEmpty(int count) {
        var sessions=mock(WorkoutSessionRepository.class);var records=mock(WorkoutSnapshotRepository.class);
        var session=new WorkoutSession();session.id=UUID.randomUUID();session.initialPlan=initial().json();session.appliedPlan=json(plan(3));
        when(records.sets(session.id)).thenReturn(List.of(set(1,8),set(2,8),set(3,8)));
        var past=new ArrayList<WorkoutSession>();var stored=new HashMap<UUID,List<PerformedSet>>();
        for(int n=0;n<count;n++) {
            var prior=new WorkoutSession();prior.id=UUID.randomUUID();prior.initialPlan=initial().json();prior.appliedPlan=json(plan(3));prior.endedAt=Instant.EPOCH;past.add(prior);
            if(n==0||n==count-1) {var rows=List.of(set(1,n==0?8:10),set(2,n==0?8:10),set(3,n==0?8:10));rows.forEach(s->s.sessionId=prior.id);stored.put(prior.id,rows);}
        }
        when(sessions.finishedBefore(session)).thenReturn(past);
        when(records.setsForSessions(anyList())).thenAnswer(call->{List<UUID> ids=call.getArgument(0);return ids.stream().flatMap(id->stored.getOrDefault(id,List.of()).stream()).toList();});
        var evidence=new WorkoutAssessment(sessions,records).assess(session,Instant.EPOCH).exercises().getFirst().benchmarkEvidence();
        assertThat(evidence.sessionId()).isEqualTo(past.getLast().id);assertThat(evidence.repsSum()).isEqualTo("30");
        assertThat(evidence.performedSetIds()).containsExactlyElementsOf(stored.get(past.getLast().id).stream().map(s->s.id).toList());
    }

    @ParameterizedTest @ValueSource(strings={"initial","applied"})
    void currentPlanFailuresStillHappenBeforeAnyHistoryRead(String corrupt) {
        var sessions=mock(WorkoutSessionRepository.class);var records=mock(WorkoutSnapshotRepository.class);
        var session=new WorkoutSession();session.initialPlan=corrupt.equals("initial")?"[]":initial().json();session.appliedPlan=corrupt.equals("applied")?"[]":json(plan(3));
        assertThatThrownBy(()->new WorkoutAssessment(sessions,records).assess(session,Instant.EPOCH)).isInstanceOf(tools.jackson.core.JacksonException.class);
        verifyNoInteractions(sessions,records);
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void unreadableHistoricalPlanIsObservedOnlyWhenHistoricalEvidenceIsNeeded(boolean eligible) {
        var sessions=mock(WorkoutSessionRepository.class);var records=mock(WorkoutSnapshotRepository.class);
        var session=new WorkoutSession();session.id=UUID.randomUUID();session.initialPlan=initial().json();session.appliedPlan=json(plan(3));
        when(records.sets(session.id)).thenReturn(eligible?List.of(set(1,8),set(2,8),set(3,8)):List.of());
        var prior=new WorkoutSession();prior.initialPlan="{\"schemaVersion\":2,\"weightExercises\":\"broken\"}";
        when(sessions.finishedBefore(session)).thenReturn(List.of(prior));
        if(eligible)assertThatThrownBy(()->new WorkoutAssessment(sessions,records).assess(session,Instant.EPOCH)).isInstanceOf(tools.jackson.core.JacksonException.class);
        else {assertThat(new WorkoutAssessment(sessions,records).assess(session,Instant.EPOCH).overallWeightPlanComplete()).isFalse();verify(sessions,never()).finishedBefore(any());}
    }

    // Reference assembly from the original eager-history path, for identical-input result comparisons.
    WorkoutAssessment.Result originalResult(WorkoutSession session,Instant at,WorkoutAssessment.Evaluation current,List<WorkoutSession> past,List<PerformedSet> stored) {
        var results=new ArrayList<WorkoutAssessment.ExerciseResult>();
        for(var item:current.exercises()) {
            WorkoutAssessment.Benchmark best=null;
            for(var prior:past)for(var exercise:WorkoutAssessment.evaluate(InitialPlan.read(prior.initialPlan),JSON.readValue(prior.appliedPlan,Plan.class),stored,true).exercises()) {
                if(!item.eligible()||!exercise.eligible()||!exercise.comparisonContext().equals(item.comparisonContext()))continue;
                var evidence=new WorkoutAssessment.Benchmark(prior.id,Long.toString(prior.version),prior.endedAt,exercise.evaluatedPerformedSetIds(),exercise.evaluatedReps(),exercise.repsSum(),WorkoutAssessment.RULE_VERSION);
                if(best==null||WorkoutAssessment.benchmarkOrder(evidence,best)<0)best=evidence;
            }
            results.add(new WorkoutAssessment.ExerciseResult(item.sessionExerciseId(),item.eligible(),item.reasonCodes(),item.comparisonContext(),item.evaluatedPerformedSetIds(),item.evaluatedReps(),item.repsSum(),best,item.advice(),item.adviceReason()));
        }
        return new WorkoutAssessment.Result(2,WorkoutAssessment.RULE_VERSION,at,Long.toString(session.version),current.complete(),List.copyOf(results));
    }

    @ParameterizedTest @CsvSource({"10,10,11,INCREASE","11,10,9,MAINTAIN","10,10,5,DECREASE","6,5,5,DECREASE"})
    void postWorkoutReviewHighlightsOnlyTheContributingRepetitions(int a,int b,int c,String advice) {
        var facts=List.of(set(1,a),set(2,b),set(3,c));
        var result=WorkoutAssessment.review(initial(),plan(3),facts,"7",Instant.now()).exercises().getFirst();
        assertThat(result.advice()).isEqualTo(advice);
        assertThat(result.referenceWeightKg()).isEqualTo("60");
        assertThat(result.completedSetCount()).isEqualTo(3);
        for(int n=0;n<3;n++)assertThat(result.sets().get(n).contributes())
                .isEqualTo(advice.equals("INCREASE")||advice.equals("DECREASE")&&facts.get(n).reps<6);
    }
    @Test void incompleteReviewSuggestsReductionWithoutGrantingOfficialBenchmarkEligibility() {
        var facts=List.of(set(1,10),set(2,9));
        var result=WorkoutAssessment.review(initial(),plan(3),facts,"7",Instant.now()).exercises().getFirst();
        assertThat(result.advice()).isEqualTo("DECREASE");assertThat(result.incomplete()).isTrue();
        assertThat(result.completedSetCount()).isEqualTo(2);assertThat(result.plannedSetCount()).isEqualTo(3);
        assertThat(result.sets()).allMatch(s->!s.contributes());assertThat(result.sets()).hasSize(2);
        assertThat(WorkoutAssessment.evaluate(initial(),plan(3),facts,true).exercises().getFirst().eligible()).isFalse();
        facts.getFirst().reps=5;
        result=WorkoutAssessment.review(initial(),plan(3),facts,"7",Instant.now()).exercises().getFirst();
        assertThat(result.sets().getFirst().contributes()).isTrue();assertThat(result.sets().getLast().contributes()).isFalse();
    }
    @Test void unavailableContextsNeverTurnIntoPartialSetReductionAdvice() {
        for(String kind:List.of("empty","temporary","pending","mixed","targets","removed")) {
            var facts=new ArrayList<>(List.of(set(1,5),set(2,8)));var applied=plan(3);
            switch(kind) {
                case "empty"->facts.clear();
                case "temporary"->facts.getFirst().contextKind="TODAY_ONLY";
                case "pending"->{facts.getFirst().approvalState="PENDING";facts.getFirst().approvedWeight=null;}
                case "mixed"->facts.getFirst().weight=new BigDecimal("61");
                case "targets"->applied=plan(4);
                case "removed"->applied=new Plan(2,List.of(),List.of());
            }
            var result=WorkoutAssessment.review(initial(),applied,facts,"7",Instant.now()).exercises().getFirst();
            assertThat(result.advice()).as(kind).isEqualTo("UNAVAILABLE");
            assertThat(result.referenceWeightKg()).isNull();assertThat(result.sets()).allMatch(r->!r.contributes());
        }
    }
}
