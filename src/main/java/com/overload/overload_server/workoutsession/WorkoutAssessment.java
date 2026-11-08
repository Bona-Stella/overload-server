package com.overload.overload_server.workoutsession;

import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;

/** Pure domain evaluation plus scoped historical evidence lookup. Never changes approval or performance. */
@Service
public class WorkoutAssessment {
    public static final String RULE_VERSION="task009-completion-v1";
    static final int HISTORY_BATCH_SIZE=100;
    private final WorkoutSessionRepository sessions;
    private final WorkoutSnapshotRepository records;

    WorkoutAssessment(WorkoutSessionRepository sessions,WorkoutSnapshotRepository records) {
        this.sessions=sessions;this.records=records;
    }

    public record Context(UUID routineId,UUID sourcePlacementId,UUID standardExerciseId,int plannedSetCount,
            int minReps,int maxReps,String approvedWeightKg,String unit) { }
    public record Benchmark(UUID sessionId,String sessionVersion,Instant endedAt,List<UUID> performedSetIds,
            List<Integer> reps,String repsSum,String ruleVersion) { }
    public record ExerciseResult(UUID sessionExerciseId,boolean eligible,List<String> reasonCodes,Context comparisonContext,
            List<UUID> evaluatedPerformedSetIds,List<Integer> evaluatedReps,String repsSum,
            Benchmark benchmarkEvidence,String advice,String adviceReason) { }
    public record Result(int schemaVersion,String ruleVersion,Instant assessedAt,String sessionVersion,
            Boolean overallWeightPlanComplete,List<ExerciseResult> exercises) { }
    record Evaluation(Boolean complete,List<ExerciseResult> exercises) { }

    Result assess(WorkoutSession session,Instant assessedAt) {
        if(session.deload()) {
            var all=records.sets(session.id);var results=new ArrayList<ExerciseResult>();
            for(var w:InitialPlan.read(session.initialPlan).weightExercises()){var actual=all.stream().filter(v->v.deletedAt==null&&v.exerciseId.equals(w.sessionExerciseId())).toList();results.add(new ExerciseResult(w.sessionExerciseId(),false,List.of("DELOAD_SESSION"),null,actual.stream().map(v->v.id).toList(),actual.stream().map(v->v.reps).toList(),Long.toString(actual.stream().mapToLong(v->v.reps).sum()),null,"UNAVAILABLE","DELOAD_SESSION"));}
            sessions.flush();return new Result(2,"deload-record-only-v1",assessedAt,Long.toString(session.version),null,List.copyOf(results));
        }
        var current=evaluate(InitialPlan.read(session.initialPlan),JSON.readValue(session.appliedPlan,Plan.class),records.sets(session.id));
        var benchmarks=new HashMap<Context,Benchmark>();
        if (current.exercises().stream().anyMatch(ExerciseResult::eligible)) {
            var contexts=current.exercises().stream().filter(ExerciseResult::eligible).map(ExerciseResult::comparisonContext).collect(Collectors.toSet());
            var past=sessions.finishedBefore(session);
            // Bound each query's UUID parameters and temporary result lists; retain JPA-managed entities.
            for (int offset=0;offset<past.size();offset+=HISTORY_BATCH_SIZE) {
                var batch=past.subList(offset,Math.min(offset+HISTORY_BATCH_SIZE,past.size()));
                var sets=records.setsForSessions(batch.stream().map(s->s.id).toList()).stream().collect(Collectors.groupingBy(s->s.sessionId));
                for (var candidate:batch) {
                    var previous=evaluate(InitialPlan.read(candidate.initialPlan),JSON.readValue(candidate.appliedPlan,Plan.class),sets.getOrDefault(candidate.id,List.of()));
                    for (var prior:previous.exercises()) {
                        if (!prior.eligible()||!contexts.contains(prior.comparisonContext())) continue;
                        var evidence=new Benchmark(candidate.id,Long.toString(candidate.version),candidate.endedAt,prior.evaluatedPerformedSetIds(),
                                prior.evaluatedReps(),prior.repsSum(),RULE_VERSION);
                        benchmarks.merge(prior.comparisonContext(),evidence,(a,b)->benchmarkOrder(a,b)<=0?a:b);
                    }
                }
            }
        } else {
            // The history query also flushes pending FINISH session changes before result construction.
            sessions.flush();
        }
        var exercises=new ArrayList<ExerciseResult>();
        for (var item:current.exercises()) {
            Benchmark benchmark=item.eligible()?benchmarks.get(item.comparisonContext()):null;
            exercises.add(new ExerciseResult(item.sessionExerciseId(),item.eligible(),item.reasonCodes(),item.comparisonContext(),
                    item.evaluatedPerformedSetIds(),item.evaluatedReps(),item.repsSum(),benchmark,item.advice(),item.adviceReason()));
        }
        return new Result(2,RULE_VERSION,assessedAt,Long.toString(session.version),current.complete(),List.copyOf(exercises));
    }

    static int benchmarkOrder(Benchmark a,Benchmark b) {
        int sum=Long.compare(Long.parseLong(b.repsSum()),Long.parseLong(a.repsSum()));
        if (sum!=0) return sum;
        int time=b.endedAt().compareTo(a.endedAt());
        return time!=0?time:a.sessionId().toString().compareTo(b.sessionId().toString());
    }

    static Evaluation evaluate(InitialPlan initial,Plan applied,List<PerformedSet> stored) {
        var valid=new HashMap<UUID,List<PerformedSet>>();
        for (var set:stored) if (set.deletedAt==null) valid.computeIfAbsent(set.exerciseId,ignored->new ArrayList<>()).add(set);
        valid.values().forEach(list->list.sort(Comparator.comparingInt((PerformedSet s)->s.ordinal).thenComparing(s->s.id.toString())));
        Boolean complete=initial.weightExercises().isEmpty()?null:initial.weightExercises().stream()
                .allMatch(item->valid.getOrDefault(item.sessionExerciseId(),List.of()).size()>=item.setCount());
        var plans=new HashMap<UUID,WeightPlan>();applied.weightExercises().forEach(p->plans.put(p.sessionExerciseId(),p));
        var results=new ArrayList<ExerciseResult>();
        for (var original:initial.weightExercises()) {
            var reasons=new LinkedHashSet<String>();
            if (!Boolean.TRUE.equals(complete)) reasons.add("INCOMPLETE_INITIAL_WEIGHT_PLAN");
            var plan=plans.get(original.sessionExerciseId());
            if (plan==null) reasons.add("INITIAL_EXERCISE_REMOVED");
            else {
                if (plan.setCount()!=original.setCount()) reasons.add("SET_COUNT_MISMATCH");
                if (plan.minReps()!=original.minReps()||plan.maxReps()!=original.maxReps()) reasons.add("COMPARISON_CONTEXT_MISMATCH");
            }
            // First choose N surviving facts. Never skip a temporary or unresolved fact to obtain eligibility.
            var selected=valid.getOrDefault(original.sessionExerciseId(),List.of()).stream().limit(original.setCount()).toList();
            if (selected.size()<original.setCount()) reasons.add("INSUFFICIENT_PERFORMED_SETS");
            BigDecimal weight=null;
            for (var set:selected) {
                if (set.contextKind.equals("TODAY_ONLY")) reasons.add("TEMPORARY_WEIGHT");
                if (!Set.of("INITIAL_APPROVED","APPLIED").contains(set.approvalState)||set.approvedWeight==null) reasons.add("NO_APPROVED_CONTEXT");
                else if (set.weight.compareTo(set.approvedWeight)!=0) reasons.add("MIXED_WEIGHT");
                if (weight!=null&&weight.compareTo(set.weight)!=0) reasons.add("MIXED_WEIGHT");
                weight=set.weight;
            }
            boolean eligible=reasons.isEmpty();
            var reps=selected.stream().map(s->s.reps).toList();
            String advice="UNAVAILABLE",adviceReason="INELIGIBLE";
            if (eligible) {
                if (reps.stream().anyMatch(r->r<original.minReps())) { advice="DECREASE";adviceReason="BELOW_MIN_REPS"; }
                else if (reps.stream().allMatch(r->r>=original.maxReps())) { advice="INCREASE";adviceReason="ALL_AT_OR_ABOVE_MAX_REPS"; }
                else { advice="MAINTAIN";adviceReason="TARGET_NOT_EXCEEDED_BY_ALL_SETS"; }
            }
            Context context=eligible?new Context(initial.routineId(),original.sourcePlacementId(),original.standardExerciseId(),
                    original.setCount(),original.minReps(),original.maxReps(),decimal(weight),"KG"):null;
            results.add(new ExerciseResult(original.sessionExerciseId(),eligible,List.copyOf(reasons),context,
                    selected.stream().map(s->s.id).toList(),reps,Long.toString(reps.stream().mapToLong(Integer::longValue).sum()),null,advice,adviceReason));
        }
        return new Evaluation(complete,List.copyOf(results));
    }
}
