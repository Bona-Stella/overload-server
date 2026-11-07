package com.overload.overload_server.deload;

import com.overload.overload_server.completion.ExactFraction;
import com.overload.overload_server.routine.WorkoutCalendarRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** Reads published evidence in the caller's snapshot. No owner lock, source judgment, dirty write or initialization. */
@Component
class DeloadAnalysisQuery {
    static final ExactFraction THRESHOLD=ExactFraction.of(8,1);
    record Result(String status,ExactFraction weeks,LocalDate from,LocalDate through,LocalDate blockedAt,boolean recommendationReady) { }
    private final DeloadWeeklyRepository weekly;
    private final DeloadCandidateRepository evidence;
    private final WorkoutCalendarRepository calendars;
    private final Clock clock;
    DeloadAnalysisQuery(DeloadWeeklyRepository weekly,DeloadCandidateRepository evidence,WorkoutCalendarRepository calendars,ObjectProvider<Clock> clocks) {
        this.weekly=weekly;this.evidence=evidence;this.calendars=calendars;clock=clocks.getIfAvailable(Clock::systemUTC);
    }
    @Transactional(transactionManager="workoutTransactionManager",propagation=Propagation.MANDATORY,readOnly=true)
    public Result read(UUID owner,long generation,DeloadAnalysisState state) {
        if(!state.enabled())return empty("DISABLED");
        if(state.timeZone()==null)return empty("AWAITING_TIME_ZONE");
        var rows=weekly.auditedRows(owner,generation,state.epoch());
        var earlier=weekly.earlierWeeks(owner,generation,state.epoch()).get(state.epoch());
        var first=DeloadWeeklyPolicy.firstWeek(state,rows,earlier);var last=DeloadWeeklyPolicy.lastDue(clock.instant(),state.timeZone());
        var folded=DeloadWeeklyPolicy.fold(first,last,rows);
        boolean awaiting=folded.blockedAt()!=null&&awaitingReview(owner,generation,state,folded.blockedAt());
        return project(first,last,rows,folded,awaiting);
    }
    private boolean awaitingReview(UUID owner,long generation,DeloadAnalysisState state,LocalDate week) {
        var registered=evidence.firstScheduleDate(owner,generation);if(registered==null)return false;
        var from=week;var end=week.plusDays(6);var epochDate=state.epochStartedAt().atZone(ZoneId.of(state.timeZone())).toLocalDate();
        if(epochDate.isAfter(from))from=epochDate;if(registered.isAfter(from))from=registered;if(from.isAfter(end))return false;
        var control=calendars.control(owner,generation).orElse(null);
        if(control==null||!control.timeZone().equals(state.timeZone())||control.reviewFrom().isAfter(from)||control.reviewedThrough().isBefore(end))return true;
        return evidence.daily(owner,generation,from,end.plusDays(1)).values().stream().anyMatch(d->d.dirty()||d.reviewedAt()==null||!Set.of("PERFORMED","MISSED","EMPTY").contains(d.state()));
    }
    static Result project(LocalDate first,LocalDate last,List<DeloadWeeklyRepository.Row> rows,DeloadWeeklyPolicy.Fold folded,boolean awaitingReview) {
        if(first.isAfter(last))return empty("INITIAL");
        var blocked=folded.blockedAt();String status="CONFIRMED";
        if(blocked!=null) {
            boolean replay=rows.stream().anyMatch(r->!r.week().isBefore(blocked)&&!r.week().isAfter(last)&&(r.resolved()||r.dirty()));
            status=replay?"RECALCULATING":awaitingReview?"AWAITING_REVIEW":"PENDING";
        }
        var through=folded.confirmedThrough();var weeks=through==null?null:folded.weeks();
        return new Result(status,weeks,through==null?null:first,through,blocked,
            blocked==null&&weeks!=null&&weeks.compareTo(THRESHOLD)>=0);
    }
    private static Result empty(String status) {return new Result(status,null,null,null,null,false);}
}
