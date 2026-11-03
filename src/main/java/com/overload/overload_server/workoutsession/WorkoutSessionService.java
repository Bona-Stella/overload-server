package com.overload.overload_server.workoutsession;

import com.overload.overload_server.persistence.TransactionExecution;
import com.overload.overload_server.routine.RoutineRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.UUID;
import static com.overload.overload_server.workoutsession.WorkoutSessionFailure.Reason.*;

@Service
public class WorkoutSessionService {
    private final WorkoutSessionRepository sessions;
    private final RoutineRepository routines;
    private final WorkoutSnapshotRepository snapshots;
    private final Clock clock;
    private final TransactionTemplate starts, writes, reads;

    WorkoutSessionService(WorkoutSessionRepository sessions, RoutineRepository routines, WorkoutSnapshotRepository snapshots,ObjectProvider<Clock> clocks,
                          @Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
        this.sessions = sessions; this.routines = routines; this.snapshots=snapshots; this.clock = clocks.getIfAvailable(Clock::systemUTC);
        starts = new TransactionTemplate(manager); starts.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes = new TransactionTemplate(manager);
        reads = new TransactionTemplate(manager); reads.setReadOnly(true); reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    WorkoutSessionResponses.Started start(UUID owner, WorkoutSessionRequests.Start input) {
        return TransactionExecution.execute(starts, () -> {
            owner(owner, input.dataGeneration());
            if (sessions.exists(owner,input.dataGeneration(),input.id())) {
                var previous=find(owner,input.dataGeneration(),input.id(),true);
                if (!previous.sameStart(input)) throw new WorkoutSessionFailure(SESSION_ID_CONFLICT);
                previous.expireIfDue(clock.instant()); sessions.flush();
                return new WorkoutSessionResponses.Started(false, response(previous));
            }
            if (sessions.occupied(input.id())) throw new WorkoutSessionFailure(SESSION_ID_CONFLICT);
            var active=sessions.activeId(owner,input.dataGeneration());
            if (active.isPresent()&&sessions.activeNotDue(owner,input.dataGeneration(),clock.instant().minus(java.time.Duration.ofHours(24))))
                throw new WorkoutSessionFailure(ACTIVE_SESSION_EXISTS);
            // Preserve routine -> definition -> session order, including expiry during a new START.
            var source = routines.planForWorkoutStart(owner, input.dataGeneration(), input.routineId())
                    .orElseThrow(() -> new WorkoutSessionFailure(ROUTINE_NOT_FOUND));
            if (active.isPresent()) {
                var previous=find(owner,input.dataGeneration(),active.get(),true);
                previous.expireIfDue(clock.instant()); sessions.flush();
                if (previous.status.equals("ACTIVE")) throw new WorkoutSessionFailure(ACTIVE_SESSION_EXISTS);
            }
            var session = WorkoutSession.start(owner, input, InitialPlan.copy(source), clock.instant());
            sessions.insert(session); sessions.flush();
            snapshots.initialize(session);sessions.flush();
            return new WorkoutSessionResponses.Started(true, response(session));
        });
    }
    WorkoutSessionResponses.Active active(UUID owner) {
        expireForRead(owner,null);
        return TransactionExecution.execute(reads, () -> {
            long generation = sessions.generation(owner, false);
            return new WorkoutSessionResponses.Active(owner, Long.toString(generation),
                    sessions.active(owner, generation).map(this::response).orElse(null));
        });
    }
    WorkoutSessionResponses.Detail detail(UUID owner, UUID id) {
        expireForRead(owner,id);
        return TransactionExecution.execute(reads, () -> response(find(owner, sessions.generation(owner, false), id, false)));
    }
    WorkoutSessionResponses.ClosedPage closed(UUID owner,WorkoutSessionRequests.ClosedQuery page) {
        return TransactionExecution.execute(reads,()->{
            long generation=sessions.generation(owner,false);
            var rows=sessions.closed(owner,generation,page);
            var values=rows.stream().limit(page.limit()).map(s->new WorkoutSessionResponses.ClosedSummary(s.id,
                    InitialPlan.read(s.initialPlan).routineName(),s.status,Long.toString(s.version),s.workoutDate,s.startedAt,s.endedAt,s.closedAt,s.deletedAt)).toList();
            var last=values.isEmpty()?null:values.getLast();
            return new WorkoutSessionResponses.ClosedPage(owner,Long.toString(generation),values,
                    rows.size()>page.limit()?new WorkoutSessionResponses.ClosedCursor(last.closedAt(),last.id()):null);
        });
    }
    WorkoutSessionResponses.Detail takeover(UUID owner, UUID id, WorkoutSessionRequests.Takeover input) {
        var result=TransactionExecution.execute(writes, () -> {
            owner(owner, input.dataGeneration());
            var session = find(owner, input.dataGeneration(), id, true);
            if(session.deletedAt!=null)throw new WorkoutSessionFailure(SESSION_DELETED);
            if (session.expireIfDue(clock.instant())) { sessions.flush(); return null; }
            if (session.status.equals("AUTO_CLOSED")) return null;
            session.takeover(input, clock.instant()); sessions.flush();
            return response(session);
        });
        if (result==null) throw new WorkoutSessionFailure(SESSION_AUTO_CLOSED);
        return result;
    }
    private void expireForRead(UUID owner,UUID id) {
        TransactionExecution.execute(writes,()->{
            long generation=sessions.generation(owner,true);
            var target=id==null?sessions.activeId(owner,generation):java.util.Optional.of(id);
            target.flatMap(value->sessions.find(owner,generation,value,true)).ifPresent(value->value.expireIfDue(clock.instant()));
            sessions.flush(); return null;
        });
    }
    int expireBatch() {
        int closed=0;
        var candidates=sessions.expiryCandidates(clock.instant().minus(java.time.Duration.ofHours(24)));
        for (var candidate:candidates) {
            try {
                boolean changed=Boolean.TRUE.equals(TransactionExecution.execute(writes,()->{
                    var generation=sessions.currentGeneration(candidate.owner());
                    if (generation.isEmpty()||generation.get()!=candidate.generation()) return false;
                    var session=sessions.lockSkippingBusy(candidate);
                    boolean expired=session.isPresent()&&session.get().expireIfDue(clock.instant());
                    sessions.flush(); return expired;
                }));
                if (changed) closed++;
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(WorkoutSessionService.class).warn("Workout expiry transaction failed type={}",failure.getClass().getSimpleName());
            }
        }
        return closed;
    }
    private WorkoutSessionResponses.Detail response(WorkoutSession value){return WorkoutSessionResponses.detail(value,snapshots.view(value));}
    private void owner(UUID owner, long expected) {
        if (sessions.generation(owner, true) != expected) throw new WorkoutSessionFailure(DATA_GENERATION_MISMATCH);
    }
    private WorkoutSession find(UUID owner, long generation, UUID id, boolean lock) {
        return sessions.find(owner, generation, id, lock).orElseThrow(() -> new WorkoutSessionFailure(SESSION_NOT_FOUND));
    }
}
