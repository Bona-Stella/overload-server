package com.overload.overload_server.deload;

import com.overload.overload_server.completion.*;
import com.overload.overload_server.routine.WorkoutCalendarRepository;
import com.overload.overload_server.statistics.StatisticsRepository.CompletionSession;
import com.overload.overload_server.workoutsession.InitialPlan;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

/** Read-only candidate calculation, never publication or accumulation. Dates remain the stored workoutDate. */
public final class DeloadCandidateCalculator {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private DeloadCandidateCalculator() { }
    public enum Pending { DELOAD_PERIOD, AWAITING_TIME_ZONE, OUTSIDE_ON_INTERVAL, OPEN_DATE, ACTIVE, INVALID_PLAN,
        UNKNOWN_COMPLETION, REVIEW_INCOMPLETE, REVIEW_UNKNOWN, REVIEW_DIRTY, CALENDAR_ZONE_CONFLICT }
    public record Epoch(long id,long settingsVersion,Instant from,Instant until,String zone) { }
    public record DailyEvidence(String state,long revision,boolean dirty,Instant reviewedAt,Instant firstMissedAt,
            Long missedScheduleVersion,UUID missedRoutine,String missedPlanVersion) { }
    public record Candidate(UUID session,long version,long localRevision,String snapshotHash,
            InitialPlanCompletion.Result completion) { }
    public record Day(LocalDate date,List<Candidate> candidates,UUID selectedSession,boolean counted,
            ExactFraction lower,ExactFraction upper,boolean active,DailyEvidence g3,boolean reviewRequired,
            boolean reviewComplete,Set<Pending> pending) { }
    /** e is available only when inputs are complete. Bounds/n describe the observed snapshot, not a confirmed week. */
    public record Week(UUID owner,long generation,Epoch epoch,LocalDate weekStart,LocalDate weekEndExclusive,
            LocalDate evaluationFrom,LocalDate evaluationToExclusive,Instant observedAt,LocalDate firstScheduleDate,
            WorkoutCalendarRepository.Control reviewBoundary,Map<LocalDate,Long> sourceRevisions,List<Day> days,
            ExactFraction lowerE,ExactFraction upperE,ExactFraction e,int n,boolean requiredReviewsComplete,Set<Pending> pending) {
        public boolean calculable() { return pending.isEmpty(); }
        Week hold(Pending reason){var reasons=EnumSet.copyOf(pending.isEmpty()?EnumSet.noneOf(Pending.class):pending);reasons.add(reason);return new Week(owner,generation,epoch,weekStart,weekEndExclusive,evaluationFrom,evaluationToExclusive,observedAt,firstScheduleDate,reviewBoundary,sourceRevisions,days,lowerE,upperE,null,n,false,Set.copyOf(reasons));}
        /** A ratio is not an ADD contribution; G4-4 must first apply the separate E threshold. */
        public ExactFraction rate() {
            return e==null||n==0?null:new ExactFraction(e.numerator(),e.denominator().multiply(java.math.BigInteger.valueOf(n)));
        }
    }
    static Week calculate(UUID owner,long generation,Epoch epoch,LocalDate monday,Instant observedAt,
            List<CompletionSession> sessions,Map<LocalDate,DailyEvidence> daily,
            WorkoutCalendarRepository.Control calendar,LocalDate firstScheduleDate) {
        if(monday==null||monday.getDayOfWeek()!=DayOfWeek.MONDAY)throw new IllegalArgumentException("Monday required");
        var end=monday.plusDays(7);var reasons=EnumSet.noneOf(Pending.class);
        var revisions=new TreeMap<LocalDate,Long>();
        monday.datesUntil(end).forEach(date->revisions.put(date,daily.containsKey(date)?daily.get(date).revision():0L));
        var sourceRevisions=Map.copyOf(revisions);
        ZoneId zone;
        try { zone=epoch.zone()==null?null:ZoneId.of(epoch.zone()); } catch(DateTimeException invalid) { zone=null; }
        if(zone==null) {
            reasons.add(Pending.AWAITING_TIME_ZONE);
            return new Week(owner,generation,epoch,monday,end,null,null,observedAt,firstScheduleDate,calendar,sourceRevisions,List.of(),ExactFraction.ZERO,ExactFraction.ZERO,null,0,false,Set.copyOf(reasons));
        }
        var byDay=new HashMap<LocalDate,List<CompletionSession>>();
        sessions.stream().filter(s->!s.date().isBefore(monday)&&s.date().isBefore(end)
            &&!s.startedAt().isBefore(epoch.from())&&(epoch.until()==null||s.startedAt().isBefore(epoch.until())))
            .forEach(s->byDay.computeIfAbsent(s.date(),ignored->new ArrayList<>()).add(s));
        var days=new ArrayList<Day>();var lowerE=ExactFraction.ZERO;var upperE=ExactFraction.ZERO;int n=0;boolean reviewsComplete=true;
        for(var date=monday;date.isBefore(end);date=date.plusDays(1)) {
            var start=date.atStartOfDay(zone).toInstant();var stop=date.plusDays(1).atStartOfDay(zone).toInstant();
            boolean onDate=stop.isAfter(epoch.from())&&(epoch.until()==null||epoch.until().isAfter(epoch.from())&&start.isBefore(epoch.until()));
            var scoped=byDay.getOrDefault(date,List.of());
            if(!onDate&&scoped.isEmpty())continue;
            var pending=EnumSet.noneOf(Pending.class);var candidates=new ArrayList<Candidate>();boolean active=false,damaged=false;
            for(var session:scoped) {
                if(session.status().equals("ACTIVE")){active=true;continue;}
                if(!Set.of("FINISHED","AUTO_CLOSED").contains(session.status()))continue;
                boolean actual=session.sets().values().stream().anyMatch(c->c>0)
                    ||session.cardio().values().stream().anyMatch(c->c.activeMs()!=null&&c.activeMs()>0);
                if(!actual)continue;
                InitialPlanCompletion.Result completion=null;
                try {
                    completion=InitialPlanCompletion.judge(session.id(),JSON.readValue(session.initialJson(),InitialPlan.class),session.sets(),session.cardio());
                    if(!completion.hasPlan())damaged=true;
                } catch(RuntimeException invalid) { damaged=true; }
                candidates.add(new Candidate(session.id(),session.version(),session.localRevision(),session.snapshotHash(),completion));
            }
            if(!onDate&&candidates.isEmpty()&&!active)continue;
            var best=candidates.stream().filter(c->c.completion()!=null&&c.completion().hasPlan())
                .max(Comparator.comparing((Candidate c)->c.completion().lower()).thenComparing(c->c.session().toString())).orElse(null);
            var lower=best==null?ExactFraction.ZERO:best.completion().lower();
            var upper=candidates.stream().filter(c->c.completion()!=null&&c.completion().hasPlan())
                .map(c->c.completion().upper()).max(Comparator.naturalOrder()).orElse(ExactFraction.ZERO);
            if(damaged){pending.add(Pending.INVALID_PLAN);upper=ExactFraction.of(1,1);}
            if(upper.compareTo(lower)>0)pending.add(Pending.UNKNOWN_COMPLETION);
            if(active)pending.add(Pending.ACTIVE);
            var g3=daily.get(date);
            boolean reviewRequired=onDate&&firstScheduleDate!=null&&!date.isBefore(firstScheduleDate);
            boolean missed=reviewRequired&&g3!=null&&g3.state().equals("MISSED")&&!g3.dirty()&&g3.reviewedAt()!=null
                &&g3.firstMissedAt()!=null&&!g3.firstMissedAt().isBefore(epoch.from());
            boolean counted=!candidates.isEmpty()||missed;
            if(counted){n++;lowerE=lowerE.add(lower);upperE=upperE.add(upper);}
            boolean checked=true;
            if(onDate) {
                if(stop.isAfter(observedAt))pending.add(Pending.OPEN_DATE);
                if(reviewRequired) {
                    checked=calendar!=null&&!date.isBefore(calendar.reviewFrom())&&!date.isAfter(calendar.reviewedThrough());
                    if(!checked)pending.add(Pending.REVIEW_INCOMPLETE);
                    if(calendar!=null&&!zone.getId().equals(calendar.timeZone())){checked=false;pending.add(Pending.CALENDAR_ZONE_CONFLICT);}
                    if(g3!=null) {
                        if(g3.dirty()){checked=false;pending.add(Pending.REVIEW_DIRTY);}
                        if(g3.reviewedAt()==null||!Set.of("PERFORMED","MISSED","EMPTY").contains(g3.state())){checked=false;pending.add(Pending.REVIEW_UNKNOWN);}
                    }
                }
            }
            reviewsComplete&=checked;reasons.addAll(pending);
            days.add(new Day(date,List.copyOf(candidates),best==null?null:best.session(),counted,lower,upper,active,g3,reviewRequired,checked,Set.copyOf(pending)));
        }
        if(days.isEmpty()){reasons.add(Pending.OUTSIDE_ON_INTERVAL);reviewsComplete=false;}
        return new Week(owner,generation,epoch,monday,end,days.isEmpty()?null:days.getFirst().date(),days.isEmpty()?null:days.getLast().date().plusDays(1),observedAt,firstScheduleDate,calendar,sourceRevisions,
            List.copyOf(days),lowerE,upperE,reasons.isEmpty()?lowerE:null,n,reviewsComplete,Set.copyOf(reasons));
    }
}
