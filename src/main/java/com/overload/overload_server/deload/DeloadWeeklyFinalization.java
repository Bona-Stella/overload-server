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
    public enum Status { COMPLETE, PENDING, STALE, BUSY, DISABLED, AWAITING_TIME_ZONE, NOT_DUE, OUT_OF_ORDER, ALREADY_CONFIRMED }
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
    private record Scope(DeloadAnalysisState state,LocalDate first,LocalDate last,TreeMap<LocalDate,DeloadWeeklyRepository.Row> rows,Instant attempted) { }
    private List<Scope> scopes(UUID owner,long generation,DeloadAnalysisState current,Instant at,boolean currentOnly) {
        var stored=weekly.allRows(owner,generation);var earlier=weekly.earlierWeeks(owner,generation);var result=new ArrayList<Scope>();
        for(var entry:weekly.epochs(owner,generation)) {
            var state=entry.settings();if(state.timeZone()==null||currentOnly&&state.epoch()!=current.epoch())continue;
            var rows=stored.getOrDefault(state.epoch(),new TreeMap<>());
            var first=DeloadWeeklyPolicy.firstWeek(state,rows.values(),earlier.get(state.epoch()));
            var last=DeloadWeeklyPolicy.lastDue(at,state.timeZone());
            if(state.epoch()!=current.epoch()) {
                var published=rows.values().stream().filter(DeloadWeeklyRepository.Row::resolved).map(DeloadWeeklyRepository.Row::week).max(Comparator.naturalOrder()).orElse(null);
                if(published==null)continue;if(published.isBefore(last))last=published;
            }
            if(!first.isAfter(last))result.add(new Scope(state,first,last,rows,entry.attempted()));
        }
        result.sort(Comparator.comparing((Scope c)->c.state().epoch()!=current.epoch()).thenComparing(Scope::attempted).thenComparing(c->c.state().epoch()));
        return result;
    }
    private Scope markSuffix(UUID owner,long generation,Scope scope,Instant at) {
        var start=next(scope.first(),scope.rows());
        if(!start.isAfter(scope.last())) {
            weekly.markSuffix(owner,generation,scope.state().epoch(),start,scope.last(),at);
            return new Scope(scope.state(),scope.first(),scope.last(),index(weekly.rows(owner,generation,scope.state().epoch())),scope.attempted());
        }
        return scope;
    }
    private Progress process(UUID owner,long generation,Long expectedEpoch,LocalDate requested,int limit,boolean skipBusy) {
        if(!weekly.lock(owner,generation,skipBusy))return new Progress(0,Status.BUSY);
        var current=settings.current(owner,generation);var at=now();
        if(expectedEpoch!=null&&current.epoch()!=expectedEpoch)return new Progress(0,Status.STALE);
        if(!current.enabled())return new Progress(0,Status.DISABLED);
        if(current.timeZone()==null)return new Progress(0,Status.AWAITING_TIME_ZONE);
        weekly.invalidateSources(owner,generation,null,at);
        var work=scopes(owner,generation,current,at,requested!=null);int count=0,attempts=0;Status status=Status.COMPLETE;
        if(work.isEmpty())return new Progress(0,Status.NOT_DUE);
        for(var original:work) {
            if(attempts>=limit)break;var scope=markSuffix(owner,generation,original,at);var state=scope.state();var rows=scope.rows();
            if(requested!=null&&(requested.isBefore(scope.first())||requested.isAfter(scope.last())))return new Progress(0,Status.NOT_DUE);
            if(requested!=null&&rows.containsKey(requested)&&rows.get(requested).resolved()&&!rows.get(requested).dirty())return new Progress(0,Status.ALREADY_CONFIRMED);
            while(attempts<limit) {
                var week=next(scope.first(),rows);if(week.isAfter(scope.last()))break;
                if(requested!=null&&!requested.equals(week))return new Progress(count,Status.OUT_OF_ORDER);
                var row=prepare(owner,generation,state.epoch(),week,rows,at);attempts++;
                var calculated=candidates.calculateLocked(owner,generation,state.epoch(),week,at);var input=DeloadWeeklyPolicy.input(calculated);
                if(!DeloadWeeklyPolicy.resolvable(calculated)||at.isBefore(DeloadWeeklyPolicy.dueAt(week,state.timeZone()))) {
                    weekly.pending(owner,generation,state.epoch(),row,input,row.resolved(),at);status=Status.PENDING;break;
                }
                var checked=candidates.calculateLocked(owner,generation,state.epoch(),week,at);var latest=settings.current(owner,generation);var checkedInput=DeloadWeeklyPolicy.input(checked);
                if(!input.equals(checkedInput)||!DeloadWeeklyPolicy.resolvable(checked)||DeloadWeeklyPolicy.excluded(calculated)!=DeloadWeeklyPolicy.excluded(checked)||!latest.equals(current)) {
                    weekly.pending(owner,generation,state.epoch(),row,checkedInput,true,at);status=Status.STALE;break;
                }
                boolean excluded=DeloadWeeklyPolicy.excluded(checked);String resolved=excluded?"EXCLUDED":"CONFIRMED";
                var decision=excluded?null:DeloadWeeklyPolicy.decide(checked.e(),checked.n());
                if(row.status().equals(resolved)&&row.rule()==DeloadWeeklyPolicy.RULE_VERSION&&row.input().equals(checkedInput))weekly.clearUnchanged(owner,generation,state.epoch(),row,at);
                else if(excluded)weekly.exclude(owner,generation,state.epoch(),row,checkedInput,at);
                else weekly.confirm(owner,generation,state.epoch(),row,checked,checkedInput,decision,at);
                rows.put(week,new DeloadWeeklyRepository.Row(week,resolved,false,row.revision()+1,DeloadWeeklyPolicy.RULE_VERSION,excluded?null:checked.e(),excluded?null:checked.n(),excluded?null:decision.operation(),excluded?null:decision.contribution(),checkedInput));count++;
            }
        }
        return new Progress(count,status);
    }
    /** Preserve the first unresolved position and its suffix after a rolled-back window, without changing old scores. */
    private void retainPending(UUID owner,long generation) {
        if(!weekly.lock(owner,generation,true))return;var current=settings.current(owner,generation);
        if(!current.enabled()||current.timeZone()==null)return;var at=now();weekly.invalidateSources(owner,generation,null,at);
        for(var original:scopes(owner,generation,current,at,false)) {
            var scope=markSuffix(owner,generation,original,at);var next=next(scope.first(),scope.rows());if(next.isAfter(scope.last()))continue;
            var row=prepare(owner,generation,scope.state().epoch(),next,scope.rows(),at);
            weekly.pending(owner,generation,scope.state().epoch(),row,row.input(),true,at);return;
        }
    }
    /** Internal only. Re-audit source vectors, then stop at the first gap/PENDING/dirty row; G4-6 HTTP stays unchanged. */
    public Accumulation accumulated(UUID owner,long generation) {
        return writes.execute(tx->{
            if(!weekly.lock(owner,generation,false))throw new DeloadAnalysisFailure(DeloadAnalysisFailure.Reason.DATA_GENERATION_MISMATCH);
            var state=settings.current(owner,generation);
            if(!state.enabled()||state.timeZone()==null)return new Accumulation(state.epoch(),null,null,null,!state.enabled()?Status.DISABLED:Status.AWAITING_TIME_ZONE);
            var at=now();weekly.invalidateSources(owner,generation,null,at);
            var scopes=scopes(owner,generation,state,at,true);
            if(scopes.isEmpty())return new Accumulation(state.epoch(),ExactFraction.ZERO,null,null,Status.COMPLETE);
            var scope=markSuffix(owner,generation,scopes.getFirst(),at);
            var folded=DeloadWeeklyPolicy.fold(scope.first(),scope.last(),new ArrayList<>(scope.rows().values()));
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
        while(rows.containsKey(next)&&rows.get(next).resolved()&&!rows.get(next).dirty()&&rows.get(next).rule()==DeloadWeeklyPolicy.RULE_VERSION)next=next.plusWeeks(1);
        return next;
    }
    private Instant now() {return clock.instant().truncatedTo(ChronoUnit.MICROS);}
}
