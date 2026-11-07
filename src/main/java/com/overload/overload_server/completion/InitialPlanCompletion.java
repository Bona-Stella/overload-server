package com.overload.overload_server.completion;

import com.overload.overload_server.workoutsession.InitialPlan;
import java.math.BigDecimal;
import java.util.*;

/** Pure single-session judgment. Callers supply scoped, nondeleted sets and COMPLETED cardio facts. */
public final class InitialPlanCompletion {
    private InitialPlanCompletion() { }
    /** A null measurement means unavailable evidence, not a fabricated zero. */
    public record CardioEvidence(Long activeMs,BigDecimal distance,BigDecimal actualSpeedKmh) { }
    public record Result(UUID session,int completed,int unknown,int planned) {
        public Result {
            if(session==null||completed<0||unknown<0||planned<0||completed>planned||unknown>planned-completed)
                throw new IllegalArgumentException("Invalid completion counts");
        }
        public boolean hasPlan() { return planned>0; }
        public boolean isExact() { return hasPlan()&&unknown==0; }
        public ExactFraction lower() { return hasPlan()?ExactFraction.of(completed,planned):ExactFraction.ZERO; }
        public ExactFraction upper() { return hasPlan()?ExactFraction.of(completed+unknown,planned):ExactFraction.ZERO; }
    }
    /** No AppliedPlan, current routine, schedule, date aggregation or actual-activity eligibility is inferred here. */
    public static Result judge(UUID session,InitialPlan initial,Map<UUID,Integer> sets,Map<UUID,CardioEvidence> cardio) {
        if(session==null||initial==null||initial.weightExercises()==null||initial.cardioActivities()==null||sets==null||cardio==null)
            throw new IllegalArgumentException("Completion inputs required");
        int done=0,unknown=0;var ids=new HashSet<UUID>();
        for(var weight:initial.weightExercises()) {
            if(weight==null||weight.sessionExerciseId()==null||!ids.add(weight.sessionExerciseId())||weight.setCount()<=0)
                throw new IllegalArgumentException("Invalid InitialPlan weight");
            Integer count=sets.getOrDefault(weight.sessionExerciseId(),0);
            if(count==null||count<0)throw new IllegalArgumentException("Invalid performed set count");
            if(count>=weight.setCount())done++;
        }
        for(var activity:initial.cardioActivities()) {
            if(activity==null||activity.sessionActivityId()==null||!ids.add(activity.sessionActivityId())||activity.plan()==null||activity.plan().targetSeconds()<=0)
                throw new IllegalArgumentException("Invalid InitialPlan cardio");
            var goal=activity.plan();
            BigDecimal distance=goal.targetDistanceKm()==null?null:positive(goal.targetDistanceKm());
            BigDecimal intensity=goal.intensity()==null?null:positive(goal.intensity().value());
            if(goal.intensity()!=null&&(goal.intensity().mode()==null||!Set.of("SPEED","PACE").contains(goal.intensity().mode())))throw new IllegalArgumentException("Invalid intensity mode");
            var actual=cardio.get(activity.sessionActivityId());if(actual==null)continue;
            if(actual.activeMs()!=null&&actual.activeMs()<0||actual.distance()!=null&&actual.distance().signum()<0||actual.actualSpeedKmh()!=null&&actual.actualSpeedKmh().signum()<0)
                throw new IllegalArgumentException("Invalid actual cardio measurement");
            if(actual.activeMs()!=null&&actual.activeMs()<goal.targetSeconds()*1000L)continue;
            if(distance!=null&&actual.distance()!=null&&actual.distance().compareTo(distance)<0)continue;
            boolean intensityUnknown=false;
            if(intensity!=null) {
                if(actual.actualSpeedKmh()==null)intensityUnknown=true;
                else if(goal.intensity().mode().equals("SPEED")?actual.actualSpeedKmh().compareTo(intensity)<0:
                    actual.actualSpeedKmh().multiply(intensity).compareTo(BigDecimal.valueOf(60))<0)continue;
            }
            if(actual.activeMs()==null||distance!=null&&actual.distance()==null||intensityUnknown||goal.inclinePercent()!=null||goal.resistance()!=null)unknown++;
            else done++;
        }
        return new Result(session,done,unknown,initial.weightExercises().size()+initial.cardioActivities().size());
    }
    private static BigDecimal positive(String raw) {
        try {
            var value=new BigDecimal(raw);
            if(value.signum()<=0)throw new IllegalArgumentException("Positive target required");
            return value;
        } catch(NullPointerException failure) { throw new IllegalArgumentException("Target value required",failure); }
    }
}
