package com.overload.overload_server.deload;

import com.overload.overload_server.routine.WorkoutCalendarRepository;
import com.overload.overload_server.statistics.StatisticsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;
import static com.overload.overload_server.deload.DeloadAnalysisFailure.Reason.*;

/** Internal G4-4 input, with one consistent snapshot. No endpoint, writer, scheduler or accumulated value. */
@Service
@Transactional(transactionManager="workoutTransactionManager",readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class DeloadWeeklyCandidates {
    private final DeloadPeriods periods;
    private final DeloadAnalysisRepository settings;
    private final StatisticsRepository facts;
    private final WorkoutCalendarRepository calendars;
    private final DeloadCandidateRepository evidence;
    public DeloadWeeklyCandidates(DeloadAnalysisRepository settings,StatisticsRepository facts,WorkoutCalendarRepository calendars,DeloadCandidateRepository evidence,DeloadPeriods periods) {
        this.periods=periods;this.settings=settings;this.facts=facts;this.calendars=calendars;this.evidence=evidence;
    }
    public DeloadCandidateCalculator.Week calculate(UUID owner,long generation,long epoch,LocalDate monday) {
        return load(owner,generation,epoch,monday,settings.now());
    }
    /** Caller holds the owner FOR UPDATE in a READ_COMMITTED workout transaction. */
    @Transactional(transactionManager="workoutTransactionManager",propagation=Propagation.MANDATORY)
    public DeloadCandidateCalculator.Week calculateLocked(UUID owner,long generation,long epoch,LocalDate monday,Instant observed) {
        return load(owner,generation,epoch,monday,observed);
    }
    private DeloadCandidateCalculator.Week load(UUID owner,long generation,long epoch,LocalDate monday,Instant observed) {
        if(monday==null||monday.getDayOfWeek()!=DayOfWeek.MONDAY)throw DeloadAnalysisRequests.invalid();
        if(settings.generation(owner,false)!=generation)throw new DeloadAnalysisFailure(DATA_GENERATION_MISMATCH);
        var history=settings.epochHistory(owner,generation,epoch);
        if(history.isEmpty()||history.getFirst().version()!=epoch||!history.getFirst().enabled())throw DeloadAnalysisRequests.invalid();
        var first=history.getFirst();var last=history.getLast();
        var until=history.stream().filter(s->!s.enabled()).map(DeloadAnalysisState::recordedAt).findFirst().orElse(null);
        var interval=new DeloadCandidateCalculator.Epoch(epoch,last.version(),first.epochStartedAt(),until,last.timeZone());
        var end=monday.plusDays(7);
        var sessions=interval.zone()==null?List.<StatisticsRepository.CompletionSession>of():facts.completionFacts(owner,generation,monday,end,interval.from(),interval.until());
        var result=DeloadCandidateCalculator.calculate(owner,generation,interval,monday,observed,sessions,evidence.daily(owner,generation,monday,end),
            calendars.control(owner,generation).orElse(null),evidence.firstScheduleDate(owner,generation));
        if(result.evaluationFrom()!=null&&periods.firstOverlap(owner,generation,result.evaluationFrom(),result.evaluationToExclusive()).isPresent())return result.hold(DeloadCandidateCalculator.Pending.DELOAD_PERIOD);
        return result;
    }
}
