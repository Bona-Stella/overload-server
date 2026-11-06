package com.overload.overload_server.statistics;

import com.overload.overload_server.routine.*;
import com.overload.overload_server.workoutsession.InitialPlan;
import java.math.*;
import java.time.*;
import java.util.*;
import static com.overload.overload_server.statistics.StatisticsOverview.PlannedDay;

/** Schedule evidence supplies the denominator; each candidate is judged against its own InitialPlan. */
final class PlanningCalculator {
    record Revision(Instant recordedAt,boolean deleted,RoutineResponses.Detail plan) { }
    record Anchor(UUID id,LocalDate date,Instant startedAt,InitialPlan initial,Long scheduleVersion) {
        Anchor(UUID id,LocalDate date,Instant at,InitialPlan plan){this(id,date,at,plan,null);}
    }
    record Daily(UUID firstSession,Instant firstMissed,String missedSchedule,UUID missedRoutine,String missedPlan,String state,long revision,boolean dirty) { }
    record Evidence(List<WeeklySchedule.Version> schedules,List<Revision> plans,List<Anchor> anchors,
                    Long boundaryVersion,Instant boundary,Map<LocalDate,Daily> daily,Set<LocalDate> activeDates) {
        Evidence(List<WeeklySchedule.Version> schedules,List<Revision> plans,List<Anchor> anchors){this(schedules,plans,anchors,null,null,Map.of(),Set.of());}
    }
    record Result(List<PlannedDay> days,int knownPlannedDays,int unknownDays,int unverifiableDays,Fraction effective) { }
    private record Candidate(UUID session,int completed,int unknown,int planned) {
        Fraction lower(){return planned==0?Fraction.ZERO:Fraction.of(completed,planned);}
        Fraction upper(){return planned==0?Fraction.ZERO:Fraction.of(completed+unknown,planned);}
    }
    record Fraction(BigInteger numerator,BigInteger denominator) implements Comparable<Fraction> {
        static final Fraction ZERO=of(0,1);
        static Fraction of(int n,int d){return new Fraction(BigInteger.valueOf(n),BigInteger.valueOf(d));}
        Fraction add(Fraction b){var n=numerator.multiply(b.denominator).add(b.numerator.multiply(denominator));var d=denominator.multiply(b.denominator);var g=n.gcd(d);return new Fraction(n.divide(g),d.divide(g));}
        public int compareTo(Fraction b){return numerator.multiply(b.denominator).compareTo(b.numerator.multiply(denominator));}
        String decimal(){return new BigDecimal(numerator).divide(new BigDecimal(denominator),12,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
        String percent(int days){return new BigDecimal(numerator).multiply(BigDecimal.valueOf(100)).divide(new BigDecimal(denominator).multiply(BigDecimal.valueOf(days)),6,RoundingMode.HALF_UP).min(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();}
    }
    static Result calculate(UUID owner,long generation,StatisticsQuery query,StatisticsCalculator.Input facts,Evidence evidence) {
        var result=new ArrayList<PlannedDay>();int planned=0,unknown=0,unverifiable=0;var effective=Fraction.ZERO;
        var sessionsByDay=new HashMap<LocalDate,List<StatisticsCalculator.Session>>();
        facts.sessions().forEach(s->sessionsByDay.computeIfAbsent(s.date(),ignored->new ArrayList<>()).add(s));
        var counts=new HashMap<UUID,Map<UUID,Integer>>();
        facts.sets().forEach(s->counts.computeIfAbsent(s.sessionId(),ignored->new HashMap<>()).merge(s.exerciseId(),1,Integer::sum));
        var cardio=new HashMap<UUID,Map<UUID,StatisticsCalculator.CardioFact>>();
        facts.cardio().forEach(c->cardio.computeIfAbsent(c.sessionId(),ignored->new HashMap<>()).put(c.activityId(),c));
        var registration=evidence.schedules().stream().filter(s->s.version().equals("1")).map(WeeklySchedule.Version::effectiveFrom).findFirst().orElse(null);
        var firstMonday=registration==null?null:registration.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for(LocalDate date:query.fromDate().datesUntil(query.toDateExclusive()).toList()) {
            var daily=evidence.daily().get(date);
            var pinned=firstStart(evidence,date);
            var schedule=pinned!=null?atStart(evidence,pinned):daily!=null&&daily.firstMissed()!=null?
                    evidence.schedules().stream().filter(s->s.version().equals(daily.missedSchedule())).findFirst().orElse(null):atDayEnd(evidence,date);
            if(schedule==null){
                if(registration!=null&&date.isBefore(registration)&&!date.isBefore(firstMonday))
                    result.add(new PlannedDay(date,"BEFORE_REGISTRATION",null,null,null,null,null,null));
                else {unknown++;result.add(new PlannedDay(date,"UNKNOWN",null,null,null,null,null,null));}
                continue;
            }
            UUID routine=schedule.assignments().stream().filter(a->a.dayOfWeek()==date.getDayOfWeek().getValue()).map(WeeklySchedule.Assignment::routineId).findFirst().orElse(null);
            if(routine==null){result.add(rest(date,schedule.version()));continue;}
            RoutineResponses.Detail plan;
            if(pinned!=null)plan=detail(owner,generation,pinned.initial());
            else {
                Instant cutoff=date.plusDays(1).atStartOfDay(ZoneId.of(schedule.timeZone())).toInstant();
                var revision=evidence.plans().stream().filter(p->p.plan().id().equals(routine)&&p.recordedAt().isBefore(cutoff)
                            &&(daily==null||daily.firstMissed()==null||daily.missedPlan()==null||p.plan().version().equals(daily.missedPlan())))
                        .max(Comparator.comparing(Revision::recordedAt).thenComparing(p->new BigInteger(p.plan().version()))).orElse(null);
                if(revision==null){unknown++;result.add(new PlannedDay(date,"UNKNOWN",schedule.version(),null,null,null,null,null));continue;}
                plan=revision.plan();
            }
            planned++;
            var candidates=sessionsByDay.getOrDefault(date,List.of()).stream().filter(s->s.initial().routineId().equals(routine))
                    .map(s->judge(s,counts.getOrDefault(s.id(),Map.of()),cardio.getOrDefault(s.id(),Map.of()))).filter(c->c.planned()>0).toList();
            var best=candidates.stream().max(Comparator.comparing(Candidate::lower).thenComparing(c->c.session().toString())).orElse(null);
            var lower=best==null?Fraction.ZERO:best.lower();
            var upper=candidates.stream().map(Candidate::upper).max(Comparator.naturalOrder()).orElse(Fraction.ZERO);
            int size=plan.weightPlacements().size()+plan.cardioPlacements().size();
            boolean pending=evidence.activeDates().contains(date);
            boolean known=!pending&&upper.compareTo(lower)==0&&size>0;
            if(!known)unverifiable++;else effective=effective.add(lower);
            result.add(new PlannedDay(date,pending?"PENDING":known?"PLANNED":"UNVERIFIABLE",schedule.version(),plan,
                    best==null?size:best.planned(),known?(best==null?0:best.completed()):null,known?lower.decimal():null,best==null?null:best.session(),
                    daily==null?null:daily.dirty()?"PENDING":daily.state(),daily==null?null:Long.toString(daily.revision())));
        }
        return new Result(List.copyOf(result),planned,unknown,unverifiable,effective);
    }
    private static PlannedDay rest(LocalDate day,String version){return new PlannedDay(day,"REST",version,null,0,0,"0",null);}
    static Anchor firstStart(Evidence e,LocalDate day){
        var d=e.daily().get(day);
        if(d!=null&&d.firstSession()!=null)return e.anchors().stream().filter(a->a.id().equals(d.firstSession())).findFirst().orElse(null);
        if(d!=null&&d.firstMissed()!=null)return null;
        return e.anchors().stream().filter(a->a.date().equals(day)&&scheduled(e,a)).min(Comparator.comparing(Anchor::startedAt).thenComparing(Anchor::id)).orElse(null);
    }
    private static UUID assigned(WeeklySchedule.Version s,LocalDate day){return s==null?null:s.assignments().stream().filter(a->a.dayOfWeek()==day.getDayOfWeek().getValue()).map(WeeklySchedule.Assignment::routineId).findFirst().orElse(null);}
    private static WeeklySchedule.Version legacy(Evidence e,LocalDate day){return e.schedules().stream().filter(s->(e.boundaryVersion()==null||Long.parseLong(s.version())<e.boundaryVersion())&&!s.effectiveFrom().isAfter(day)).max(Comparator.comparing(WeeklySchedule.Version::effectiveFrom).thenComparing(s->new BigInteger(s.version()))).orElse(null);}
    private static WeeklySchedule.Version atStart(Evidence e,Anchor a){
        if(a.scheduleVersion()!=null)return e.schedules().stream().filter(s->s.version().equals(a.scheduleVersion().toString())).findFirst().orElse(null);
        return e.boundary()==null||a.startedAt().isBefore(e.boundary())?legacy(e,a.date()):null;
    }
    private static boolean scheduled(Evidence e,Anchor a){return a.initial().routineId().equals(assigned(atStart(e,a),a.date()));}
    private static WeeklySchedule.Version atDayEnd(Evidence e,LocalDate day){
        if(e.boundaryVersion()==null)return legacy(e,day);
        String zone=e.schedules().stream().filter(s->s.version().equals(e.boundaryVersion().toString())).map(WeeklySchedule.Version::timeZone).findFirst().orElse("UTC");
        Instant end=day.plusDays(1).atStartOfDay(ZoneId.of(zone)).toInstant();
        if(!end.isAfter(e.boundary()))return legacy(e,day);
        return e.schedules().stream().filter(s->Long.parseLong(s.version())>=e.boundaryVersion()&&s.recordedAt().isBefore(end)).max(Comparator.comparing(WeeklySchedule.Version::recordedAt).thenComparing(s->new BigInteger(s.version()))).orElse(null);
    }
    private static Candidate judge(StatisticsCalculator.Session session,Map<UUID,Integer> sets,Map<UUID,StatisticsCalculator.CardioFact> cardio) {
        int done=0,unknown=0;
        for(var weight:session.initial().weightExercises())if(sets.getOrDefault(weight.sessionExerciseId(),0)>=weight.setCount())done++;
        for(var activity:session.initial().cardioActivities()) {
            var actual=cardio.get(activity.sessionActivityId());if(actual==null)continue;
            var goal=activity.plan();if(actual.activeMs()<goal.targetSeconds()*1000L)continue;
            if(goal.targetDistanceKm()!=null&&actual.distance()!=null&&actual.distance().compareTo(new BigDecimal(goal.targetDistanceKm()))<0)continue;
            boolean intensityUnknown=false;
            if(goal.intensity()!=null){
                if(actual.actualSpeedKmh()==null)intensityUnknown=true;
                else if(goal.intensity().mode().equals("SPEED")?actual.actualSpeedKmh().compareTo(new BigDecimal(goal.intensity().value()))<0:
                        actual.actualSpeedKmh().multiply(new BigDecimal(goal.intensity().value())).compareTo(BigDecimal.valueOf(60))<0)continue;
            }
            if(goal.targetDistanceKm()!=null&&actual.distance()==null||intensityUnknown||goal.inclinePercent()!=null||goal.resistance()!=null)unknown++;
            else done++;
        }
        return new Candidate(session.id(),done,unknown,session.initial().weightExercises().size()+session.initial().cardioActivities().size());
    }
    private static RoutineResponses.Detail detail(UUID owner,long generation,InitialPlan initial) {
        return new RoutineResponses.Detail(owner,Long.toString(generation),initial.routineId(),initial.routineVersion(),initial.routineName(),
                initial.weightExercises().stream().map(w->new RoutineResponses.Weight(w.sourcePlacementId(),w.standardExerciseId(),w.exerciseName(),w.manufacturerId(),w.loadType(),w.setCount(),w.minReps(),w.maxReps(),w.initialWeightKg(),w.approvedWeightKg(),null)).toList(),
                initial.cardioActivities().stream().map(c->new RoutineResponses.Cardio(c.sourcePlacementId(),c.plan(),null)).toList());
    }
}
