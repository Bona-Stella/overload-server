package com.overload.overload_server.deload;

import com.overload.overload_server.routine.RoutineResponses;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.overload.overload_server.deload.DeloadPlan.*;

/** Pure initial generation. Never writes source routines or infers joint mechanics from exercise names. */
final class DeloadPlanGenerator {
    private DeloadPlanGenerator(){}
    private record Candidate(int day,UUID routine,String version,RoutineResponses.Weight weight,Classification classification,int priority){}
    static List<Option> options(Source source) {
        var choices=new ArrayList<Choice>();choices.add(new Choice(Method.REST,Split.REST,List.of()));int n=source.days().size();
        if(n>=1&&n<=6)choices.add(new Choice(Method.KEEP,Split.ORIGINAL,List.of()));
        if(n>=4&&n<=6){choices.add(new Choice(Method.THREE,Split.PPL,List.of()));choices.add(new Choice(Method.THREE,Split.EQUAL,List.of()));}
        if(n>=3&&n<=6){choices.add(new Choice(Method.TWO,Split.FRONT_BACK,List.of()));choices.add(new Choice(Method.TWO,Split.EQUAL,List.of()));}
        return choices.stream().map(c->{var g=generate(source,c);return new Option(c.method(),c.split(),dates(source,c),g.ready(),g.issues());}).toList();
    }
    static Generated generate(Source source,Choice choice) {
        if(!allowed(source.days().size(),choice))throw DeloadPeriodFailure.invalid();
        var dates=dates(source,choice);var issues=new ArrayList<Issue>();
        if(choice.method()==Method.REST)return new Generated(List.of(),List.of());
        var classes=new HashMap<UUID,Classification>();source.classifications().forEach(c->classes.put(c.id(),c));
        if(choice.method()==Method.KEEP) {
            var plans=new ArrayList<Plan>();
            for(int i=0;i<source.days().size();i++) {
                var day=source.days().get(i);var r=day.routine();var weights=r.weightPlacements().stream().map(w->reduce(day.weekday(),r,w)).toList();
                var cardio=r.cardioPlacements().stream().map(c->reduceCardio(day.weekday(),r,c,issues)).filter(Objects::nonNull).toList();
                if(!weights.isEmpty()||!cardio.isEmpty())plans.add(new Plan(r.name(),dates.get(i),weights,cardio));
            }
            return new Generated(List.copyOf(plans),List.copyOf(issues));
        }
        var candidates=new ArrayList<Candidate>();var seen=new HashSet<UUID>();int priority=0,totalExercises=0;long totalSets=0;
        for(var day:source.days())for(var weight:day.routine().weightPlacements()) {
            totalExercises++;totalSets+=weight.setCount();if(!seen.add(weight.standardExerciseId()))continue;
            var classification=classes.get(weight.standardExerciseId());
            if(classification==null||classification.jointType()==null||classification.primary().isEmpty())issues.add(new Issue("CLASSIFICATION_REQUIRED",weight.standardExerciseId()));
            candidates.add(new Candidate(day.weekday(),day.routine().id(),day.routine().version(),weight,classification,priority++));
        }
        if(!issues.isEmpty())return new Generated(List.of(),List.copyOf(issues));
        var selected=new ArrayList<Candidate>();var covered=new HashSet<UUID>();
        for(var c:candidates)if(c.classification().primary().stream().anyMatch(m->!covered.contains(m.id()))) {
            selected.add(c);c.classification().primary().forEach(m->covered.add(m.id()));
        }
        int count=choice.method()==Method.THREE?3:2;var buckets=new ArrayList<List<Candidate>>();for(int i=0;i<count;i++)buckets.add(new ArrayList<>());
        List<String> labels;
        if(choice.split()==Split.EQUAL) {
            labels=count==3?List.of("A","B","C"):List.of("A","B");
            for(int i=0;i<selected.size();i++)buckets.get(i%count).add(selected.get(i));
        } else {
            var groups=new HashMap<UUID,String>();
            for(var c:selected) {
                var group=group(c.classification(),choice.split());if(group==null)issues.add(new Issue("SPLIT_CLASSIFICATION_REQUIRED",c.weight().standardExerciseId()));else groups.put(c.weight().standardExerciseId(),group);
            }
            var needed=choice.split()==Split.PPL?List.of("PUSH","PULL","LEGS"):List.of("FRONT","BACK");
            if(!new HashSet<>(groups.values()).containsAll(needed))issues.add(new Issue("SPLIT_UNAVAILABLE",null));
            if(choice.split()==Split.PPL) {
                var order=new LinkedHashSet<String>();for(var day:source.days())if(!day.routine().weightPlacements().isEmpty()) {
                    var w=day.routine().weightPlacements().getFirst();String g=group(classes.get(w.standardExerciseId()),Split.PPL);if(g!=null)order.add(g);
                }
                if(!order.containsAll(needed))issues.add(new Issue("SPLIT_ORDER_UNRESOLVED",null));labels=List.copyOf(order);
            } else labels=needed;
            if(!issues.isEmpty())return new Generated(List.of(),List.copyOf(issues));
            for(var c:selected)buckets.get(labels.indexOf(groups.get(c.weight().standardExerciseId()))).add(c);
        }
        int exerciseCap=Math.min(totalExercises/source.days().size()+(count==3?1:2),count==3?6:7);
        long setCap=Math.max(1,totalSets*7/(10L*source.days().size()));
        var cardioDays=source.days().stream().filter(d->!d.routine().cardioPlacements().isEmpty()).toList();
        var cardioSlots=new ArrayList<Integer>();for(int i=0;i<count;i++)if(!labels.get(i).equals("LEGS"))cardioSlots.add(i);
        int wanted=Math.min(cardioDays.size(),2);if(count==2)wanted=Math.min(cardioDays.size(),2);
        var plans=new ArrayList<Plan>();
        for(int i=0;i<count;i++) {
            var bucket=buckets.get(i);if(bucket.size()>exerciseCap)bucket=new ArrayList<>(bucket.subList(0,exerciseCap));
            var weights=new ArrayList<Weight>();for(var c:bucket)weights.add(reduce(c.day(),c.routine(),c.version(),c.weight()));
            long sum=weights.stream().mapToLong(Weight::setCount).sum();
            for(int j=weights.size()-1;sum>setCap&&j>=0;j--) {
                var w=weights.get(j);int remove=(int)Math.min(sum-setCap,w.setCount()-1L);
                if(remove>0){weights.set(j,new Weight(w.sourceWeekday(),w.sourceRoutineId(),w.sourceVersion(),w.original(),w.weightKg(),w.setCount()-remove,w.minReps(),w.maxReps()));sum-=remove;}
            }
            while(sum>setCap&&!weights.isEmpty())sum-=weights.removeLast().setCount();
            weights.sort(Comparator.comparingInt(w->jointOrder(classes.get(w.original().standardExerciseId()).jointType())));
            var cardio=new ArrayList<Cardio>();if(wanted>0&&cardioSlots.subList(0,wanted).contains(i)) {
                var d=cardioDays.getFirst();var c=reduceCardio(d.weekday(),d.routine(),d.routine().cardioPlacements().getFirst(),issues);if(c!=null)cardio.add(c);
            }
            if(!weights.isEmpty()||!cardio.isEmpty())plans.add(new Plan(labels.get(i),dates.get(i),List.copyOf(weights),List.copyOf(cardio)));
        }
        return new Generated(List.copyOf(plans),List.copyOf(issues));
    }
    static boolean allowed(int n,Choice c) {
        return c.method()==Method.REST&&c.split()==Split.REST||n>=1&&n<=6&&c.method()==Method.KEEP&&c.split()==Split.ORIGINAL
            ||n>=4&&n<=6&&c.method()==Method.THREE&&Set.of(Split.PPL,Split.EQUAL).contains(c.split())
            ||n>=3&&n<=6&&c.method()==Method.TWO&&Set.of(Split.FRONT_BACK,Split.EQUAL).contains(c.split());
    }
    static List<LocalDate> dates(Source source,Choice choice) {
        var proposed=new ArrayList<LocalDate>();
        switch(choice.method()) {
            case REST -> { }
            case KEEP -> source.days().forEach(d->proposed.add(source.today().plusDays(Math.floorMod(d.weekday()-source.today().getDayOfWeek().getValue(),7))));
            case THREE -> {proposed.add(source.today());proposed.add(source.today().plusDays(2));proposed.add(source.today().plusDays(4));}
            case TWO -> {proposed.add(source.today());proposed.add(source.today().plusDays(3));}
        }
        var result=choice.dates().isEmpty()?proposed:choice.dates();
        if(result.size()!=proposed.size()||new HashSet<>(result).size()!=result.size()||result.stream().anyMatch(d->d.isBefore(source.today())||d.isAfter(source.today().plusDays(6))))throw DeloadPeriodFailure.invalid();
        return List.copyOf(result);
    }
    private static Weight reduce(int day,RoutineResponses.Detail r,RoutineResponses.Weight w){return reduce(day,r.id(),r.version(),w);}
    private static Weight reduce(int day,UUID routine,String version,RoutineResponses.Weight w) {
        var original=new BigDecimal(w.approvedWeightKg()==null?w.initialWeightKg():w.approvedWeightKg());var target=original;
        if(!"BODYWEIGHT".equals(w.loadType())) {
            int step=w.loadType()==null?1:switch(w.loadType()){case "PLATE","STACK"->5;case "DUMBBELL"->2;default->1;};
            target=original.multiply(new BigDecimal("0.85")).divide(BigDecimal.valueOf(step),0,RoundingMode.FLOOR).multiply(BigDecimal.valueOf(step));if(target.signum()==0)target=original;
        }
        int sets=w.setCount()/2+w.setCount()%2;int min=w.minReps(),max=w.maxReps();
        if(w.setCount()%2!=0){min=reps(w.setCount(),min,sets);max=reps(w.setCount(),max,sets);}
        return new Weight(day,routine,version,w,target.stripTrailingZeros().toPlainString(),sets,min,max);
    }
    private static int reps(int sets,int reps,int reduced){return Math.max(1,BigDecimal.valueOf((long)sets*reps).divide(BigDecimal.valueOf(2L*reduced),0,RoundingMode.HALF_UP).intValueExact());}
    private static Cardio reduceCardio(int day,RoutineResponses.Detail r,RoutineResponses.Cardio c,List<Issue> issues) {
        var p=c.plan();if(p.targetDistanceKm()!=null||p.inclinePercent()!=null||p.resistance()!=null||p.intensity()!=null&&!p.intensity().mode().equals("SPEED")) {
            issues.add(new Issue("CARDIO_TARGET_POLICY_REQUIRED",c.id()));return null;
        }
        int seconds=BigDecimal.valueOf(p.targetSeconds()).divide(BigDecimal.valueOf(120),0,RoundingMode.HALF_UP).intValueExact()*60;
        if(seconds==0){issues.add(new Issue("CARDIO_TIME_ROUNDS_TO_ZERO",c.id()));return null;}
        var intensity=p.intensity()==null?null:new RoutineResponses.Intensity("SPEED",new BigDecimal(p.intensity().value()).multiply(new BigDecimal("0.85")).setScale(1,RoundingMode.HALF_UP).toPlainString());
        return new Cardio(day,r.id(),r.version(),c,new RoutineResponses.Plan(p.kind(),p.name(),seconds,null,intensity,null,null,p.activityId()));
    }
    private static int jointOrder(String joint){return switch(joint){case "COMPOUND"->0;case "ISOLATION"->1;default->2;};}
    private static String group(Classification c,Split split) {
        if(c==null||c.primary().isEmpty())return null;var groups=new HashSet<String>();
        for(var m:c.primary()) {var g=region(m,split);if(g==null)return null;groups.add(g);}return groups.size()==1?groups.iterator().next():null;
    }
    private static String region(Muscle m,Split split) {
        if(m.group().equals("CHEST"))return split==Split.PPL?"PUSH":"FRONT";
        if(m.group().equals("BACK"))return split==Split.PPL?"PULL":"BACK";
        if(split==Split.PPL&&m.group().equals("LEGS"))return "LEGS";
        String n=m.name().replace(" ","");
        if(Set.of("전면삼각근","측면삼각근","전면어깨","측면어깨","삼두","삼두근").contains(n))return split==Split.PPL?"PUSH":"FRONT";
        if(Set.of("후면삼각근","후면어깨","이두","이두근").contains(n))return split==Split.PPL?"PULL":"BACK";
        if(split==Split.FRONT_BACK){if(Set.of("대퇴사두근","복근","복직근").contains(n))return "FRONT";if(Set.of("햄스트링","둔근","종아리").contains(n))return "BACK";}
        return null;
    }
}
