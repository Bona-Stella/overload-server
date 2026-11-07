package com.overload.overload_server.deload;

import com.overload.overload_server.completion.ExactFraction;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Existing review tick invokes this after G3. At most 20 owners / four weeks per owner, without a new scheduler. */
@Service
public class DeloadWeeklyFinalization {
    public enum Status { COMPLETE, PENDING, STALE, BUSY, DISABLED, AWAITING_TIME_ZONE, NOT_DUE, OUT_OF_ORDER, RECALCULATION_REQUIRED, ALREADY_CONFIRMED }
    public record Progress(int confirmed,Status status) { }
    public record Accumulation(long epoch,ExactFraction weeks,LocalDate confirmedThrough,LocalDate blockedAt,Status status) { }
    private final DeloadWeeklyRepository weekly;
    private final DeloadAnalysisRepository settings;
    private final DeloadWeeklyCandidates candidates;
    private final TransactionTemplate writes;
    private final Clock clock;
    public DeloadWeeklyFinalization(DeloadWeeklyRepository weekly,DeloadAnalysisRepository settings,DeloadWeeklyCandidates candidates,
            ObjectProvider<Clock> clocks,@Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
        this.weekly=weekly;this.settings=settings;this.candidates=candidates;clock=clocks.getIfAvailable(Clock::systemUTC);
        writes=new TransactionTemplate(manager);writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public int runBatch() {
        int confirmed=0;
        for(var owner:weekly.targets(now()))try {
            confirmed+=Objects.requireNonNull(writes.execute(tx->process(owner.id(),owner.generation(),null,null,4,true))).confirmed();
        } catch(RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Weekly analysis rolled back type={}",failure.getClass().getSimpleName());
            try {writes.executeWithoutResult(tx->retainPending(owner.id(),owner.generation()));}
            catch(RuntimeException retryFailure) {org.slf4j.LoggerFactory.getLogger(getClass()).warn("Weekly pending marker unavailable type={}",retryFailure.getClass().getSimpleName());}
        }
        return confirmed;
    }
    /** Internal idempotent single-week entry point, also used by isolated concurrency verification. */
    public Progress finalizeWeek(UUID owner,long generation,long epoch,LocalDate week) {
        if(week==null||week.getDayOfWeek()!=DayOfWeek.MONDAY)throw DeloadAnalysisRequests.invalid();
        return writes.execute(tx->process(owner,generation,epoch,week,1,false));
    }
    private Progress process(UUID owner,long generation,Long expectedEpoch,LocalDate requested,int limit,boolean skipBusy) {
        if(!weekly.lock(owner,generation,skipBusy))return new Progress(0,Status.BUSY);
        var state=settings.current(owner,generation);var at=now();
        if(expectedEpoch!=null&&state.epoch()!=expectedEpoch)return new Progress(0,Status.STALE);
        if(!state.enabled())return new Progress(0,Status.DISABLED);
        if(state.timeZone()==null)return new Progress(0,Status.AWAITING_TIME_ZONE);
        var first=weekly.firstWeek(owner,generation,state);var last=DeloadWeeklyPolicy.lastDue(at,state.timeZone());
        if(first.isAfter(last)||requested!=null&&(requested.isBefore(first)||requested.isAfter(last)))return new Progress(0,Status.NOT_DUE);
        weekly.invalidateSources(owner,generation,state.epoch(),at);
        var rows=index(weekly.rows(owner,generation,state.epoch()));
        if(rows.values().stream().anyMatch(r->r.status().equals("CONFIRMED")&&r.dirty()))return new Progress(0,Status.RECALCULATION_REQUIRED);
        if(requested!=null&&rows.containsKey(requested)&&rows.get(requested).status().equals("CONFIRMED"))return new Progress(0,Status.ALREADY_CONFIRMED);
        int count=0;
        for(int i=0;i<limit;i++) {
            var week=next(first,rows);
            if(week.isAfter(last))return new Progress(count,Status.COMPLETE);
            if(requested!=null&&!requested.equals(week))return new Progress(count,Status.OUT_OF_ORDER);
            var row=prepare(owner,generation,state.epoch(),week,rows,at);
            var calculated=candidates.calculateLocked(owner,generation,state.epoch(),week,at);
            var input=DeloadWeeklyPolicy.input(calculated);
            if(!calculated.calculable()||!calculated.requiredReviewsComplete()||at.isBefore(DeloadWeeklyPolicy.dueAt(week,state.timeZone()))) {
                weekly.pending(owner,generation,state.epoch(),row,input,false,at);return new Progress(count,Status.PENDING);
            }
            var checked=candidates.calculateLocked(owner,generation,state.epoch(),week,at);
            var latest=settings.current(owner,generation);var checkedInput=DeloadWeeklyPolicy.input(checked);
            if(!input.equals(checkedInput)||!checked.calculable()||!latest.equals(state)) {
                weekly.pending(owner,generation,state.epoch(),row,checkedInput,true,at);return new Progress(count,Status.STALE);
            }
            var decision=DeloadWeeklyPolicy.decide(checked.e(),checked.n());
            weekly.confirm(owner,generation,state.epoch(),row,checked,checkedInput,decision,at);
            rows.put(week,new DeloadWeeklyRepository.Row(week,"CONFIRMED",false,row.revision()+1,DeloadWeeklyPolicy.RULE_VERSION,checked.e(),checked.n(),decision.operation(),decision.contribution(),checkedInput));
            count++;
        }
        return new Progress(count,Status.COMPLETE);
    }
    /** Existing PENDING survives rollback; a first-time failed gap gets only a retry marker, never a score. */
    private void retainPending(UUID owner,long generation) {
        if(!weekly.lock(owner,generation,true))return;
        var state=settings.current(owner,generation);if(!state.enabled()||state.timeZone()==null)return;
        var at=now();weekly.invalidateSources(owner,generation,state.epoch(),at);
        var rows=index(weekly.rows(owner,generation,state.epoch()));
        if(rows.values().stream().anyMatch(r->r.status().equals("CONFIRMED")&&r.dirty()))return;
        var next=next(weekly.firstWeek(owner,generation,state),rows);
        if(next.isAfter(DeloadWeeklyPolicy.lastDue(at,state.timeZone())))return;
        var row=prepare(owner,generation,state.epoch(),next,rows,at);
        weekly.pending(owner,generation,state.epoch(),row,row.input(),true,at);
    }
    /** Internal only. Re-audit source vectors, then stop at the first gap/PENDING/dirty row; G4-6 HTTP stays unchanged. */
    public Accumulation accumulated(UUID owner,long generation) {
        return writes.execute(tx->{
            if(!weekly.lock(owner,generation,false))throw new DeloadAnalysisFailure(DeloadAnalysisFailure.Reason.DATA_GENERATION_MISMATCH);
            var state=settings.current(owner,generation);
            if(!state.enabled()||state.timeZone()==null)return new Accumulation(state.epoch(),null,null,null,!state.enabled()?Status.DISABLED:Status.AWAITING_TIME_ZONE);
            var at=now();weekly.invalidateSources(owner,generation,state.epoch(),at);
            var folded=DeloadWeeklyPolicy.fold(weekly.firstWeek(owner,generation,state),DeloadWeeklyPolicy.lastDue(at,state.timeZone()),weekly.rows(owner,generation,state.epoch()));
            return new Accumulation(state.epoch(),folded.weeks(),folded.confirmedThrough(),folded.blockedAt(),folded.blockedAt()==null?Status.COMPLETE:Status.PENDING);
        });
    }
    private DeloadWeeklyRepository.Row prepare(UUID owner,long generation,long epoch,LocalDate week,Map<LocalDate,DeloadWeeklyRepository.Row> rows,Instant at) {
        if(!rows.containsKey(week)) {
            weekly.reserve(owner,generation,epoch,week,at);
            rows.put(week,new DeloadWeeklyRepository.Row(week,"PENDING",false,0,DeloadWeeklyPolicy.RULE_VERSION,null,null,null,null,"{\"hash\":\"PENDING\",\"revisions\":{}}"));
        }
        return rows.get(week);
    }
    private static TreeMap<LocalDate,DeloadWeeklyRepository.Row> index(List<DeloadWeeklyRepository.Row> rows) {
        var map=new TreeMap<LocalDate,DeloadWeeklyRepository.Row>();rows.forEach(r->map.put(r.week(),r));return map;
    }
    private static LocalDate next(LocalDate first,Map<LocalDate,DeloadWeeklyRepository.Row> rows) {
        var next=first;
        while(rows.containsKey(next)&&rows.get(next).status().equals("CONFIRMED")&&!rows.get(next).dirty())next=next.plusWeeks(1);
        return next;
    }
    private Instant now() {return clock.instant().truncatedTo(ChronoUnit.MICROS);}
}
