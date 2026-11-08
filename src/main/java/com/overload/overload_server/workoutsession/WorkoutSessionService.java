package com.overload.overload_server.workoutsession;

import com.overload.overload_server.persistence.TransactionExecution;
import com.overload.overload_server.routine.RoutineRepository;
import com.overload.overload_server.routine.WorkoutCalendarRepository;
import com.overload.overload_server.deload.DeloadAnalysisService;
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
    private final WorkoutCalendarRepository calendars;
    private final DeloadAnalysisService analysis;
    private final com.overload.overload_server.deload.DeloadTemporaryPlans temporary;
    private final TransactionTemplate starts, writes, reads;

    WorkoutSessionService(WorkoutSessionRepository sessions, RoutineRepository routines, WorkoutSnapshotRepository snapshots,WorkoutCalendarRepository calendars,ObjectProvider<Clock> clocks,DeloadAnalysisService analysis,
                          @Qualifier("workoutTransactionManager") PlatformTransactionManager manager,com.overload.overload_server.deload.DeloadTemporaryPlans temporary) {
        this.temporary=temporary;
        this.sessions = sessions; this.routines = routines; this.snapshots=snapshots;this.calendars=calendars;this.analysis=analysis; this.clock = clocks.getIfAvailable(Clock::systemUTC);
        starts = new TransactionTemplate(manager); starts.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes = new TransactionTemplate(manager);
        reads = new TransactionTemplate(manager); reads.setReadOnly(true); reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    WorkoutSessionResponses.Started startDeload(UUID owner,WorkoutSessionRequests.DeloadStart request) {
        return TransactionExecution.execute(starts,()->{
            var input=request.facts();if(sessions.lockOwnerForStart(owner)!=input.dataGeneration())throw new WorkoutSessionFailure(DATA_GENERATION_MISMATCH);
            if(sessions.exists(owner,input.dataGeneration(),input.id())) {
                var old=find(owner,input.dataGeneration(),input.id(),true);
                if(!old.deload()||!old.deloadPlanId.equals(request.planId())||!old.startedDeviceId.equals(input.editorDeviceId())||!old.startedAt.equals(input.startedAt())||!old.workoutDate.equals(input.workoutDate())||!old.startedTimeZone.equals(input.startedTimeZone())||!InitialPlan.read(old.initialPlan).routineVersion().equals(Long.toString(request.planVersion())))throw new WorkoutSessionFailure(SESSION_ID_CONFLICT);
                old.expireIfDue(clock.instant());sessions.flush();return new WorkoutSessionResponses.Started(false,response(old));
            }
            if(sessions.occupied(input.id()))throw new WorkoutSessionFailure(SESSION_ID_CONFLICT);
            var active=sessions.activeId(owner,input.dataGeneration());
            if(active.isPresent()&&sessions.activeNotDue(owner,input.dataGeneration(),clock.instant().minus(java.time.Duration.ofHours(24))))throw new WorkoutSessionFailure(ACTIVE_SESSION_EXISTS);
            var plan=temporary.forStart(owner,input.dataGeneration(),request.planId(),request.planVersion(),input.workoutDate(),input.startedTimeZone(),input.startedAt());
            if(active.isPresent()){var old=find(owner,input.dataGeneration(),active.get(),true);old.expireIfDue(clock.instant());sessions.flush();if(old.status.equals("ACTIVE"))throw new WorkoutSessionFailure(ACTIVE_SESSION_EXISTS);}
            var session=WorkoutSession.start(owner,input,InitialPlan.deload(plan),clock.instant());session.deloadPeriodId=plan.periodId();session.deloadPlanId=plan.planId();
            sessions.insert(session);sessions.flush();snapshots.initialize(session);sessions.flush();return new WorkoutSessionResponses.Started(true,response(session));
        });
    }
    Object period(UUID owner,UUID id){return TransactionExecution.execute(reads,()->{long g=sessions.generation(owner,false);return java.util.Map.of("userId",owner,"dataGeneration",Long.toString(g),"periodId",id,"sessions",sessions.period(owner,g,id).stream().map(this::response).toList());});}
    WorkoutSessionResponses.Detail abandon(UUID owner,UUID id,WorkoutSessionRequests.Abandon input) {
        return TransactionExecution.execute(starts,()->{
            if(sessions.lockOwnerForStart(owner)!=input.generation())throw new WorkoutSessionFailure(DATA_GENERATION_MISMATCH);
            var session=find(owner,input.generation(),id,true);if(!session.deload())throw new WorkoutSessionFailure(INVALID_SNAPSHOT);
            if(!session.editorDeviceId.equals(input.device())||session.writerEpoch!=input.epoch())throw new WorkoutSessionFailure(EDITOR_OWNERSHIP_LOST);
            if(session.localRevision!=input.revision())throw new WorkoutSessionFailure(REVISION_STALE);
            if(session.abandonedAt!=null){if(input.version()==Long.MAX_VALUE||session.version!=input.version()+1)throw new WorkoutSessionFailure(SESSION_VERSION_CONFLICT);return response(session);}
            if(session.version!=input.version())throw new WorkoutSessionFailure(SESSION_VERSION_CONFLICT);
            if(sessions.hadActivity(id))throw new WorkoutSessionFailure(DELOAD_ACTIVITY_RECORDED);
            if(session.version==Long.MAX_VALUE)throw new WorkoutSessionFailure(WRITE_CONFLICT);
            var at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);if(at.isBefore(session.startedAt))throw new WorkoutSessionFailure(CLOCK_CONTEXT_INVALID);
            if(session.status.equals("ACTIVE")){session.status="AUTO_CLOSED";session.closedAt=at;}
            session.abandonedAt=at;if(session.deletedAt==null)session.deletedAt=at;session.updatedAt=at;session.version++;sessions.flush();return response(session);
        });
    }

    WorkoutSessionResponses.Started start(UUID owner, WorkoutSessionRequests.Start input) {
        return TransactionExecution.execute(starts, () -> {
            // Serialize START with source/schedule management before reading any source state.
            if(sessions.lockOwnerForStart(owner)!=input.dataGeneration())throw new WorkoutSessionFailure(DATA_GENERATION_MISMATCH);
            if (sessions.exists(owner,input.dataGeneration(),input.id())) {
                var previous=find(owner,input.dataGeneration(),input.id(),true);
                if (!previous.sameStart(input)) throw new WorkoutSessionFailure(SESSION_ID_CONFLICT);
                previous.expireIfDue(clock.instant()); sessions.flush();
                return new WorkoutSessionResponses.Started(false, response(previous));
            }
            sessions.requireGeneralWrite(owner,input.dataGeneration());
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
            session.scheduleVersionAtStart=calendars.origin(owner,input.dataGeneration(),input.startedAt(),input.workoutDate());
            analysis.bindForStart(owner,input.dataGeneration(),input.startedTimeZone());
            sessions.insert(session); sessions.flush();
            calendars.recordStart(owner,input.dataGeneration(),session.id,session.scheduleVersionAtStart,session.workoutDate,session.sourceRoutineId);
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
                    InitialPlan.read(s.initialPlan).routineName(),s.status,Long.toString(s.version),s.workoutDate,s.startedAt,s.endedAt,s.closedAt,s.deletedAt,s.deloadPeriodId,s.deloadPlanId)).toList();
            var last=values.isEmpty()?null:values.getLast();
            return new WorkoutSessionResponses.ClosedPage(owner,Long.toString(generation),values,
                    rows.size()>page.limit()?new WorkoutSessionResponses.ClosedCursor(last.closedAt(),last.id()):null);
        });
    }
    WorkoutSessionResponses.Detail takeover(UUID owner, UUID id, WorkoutSessionRequests.Takeover input) {
        var result=TransactionExecution.execute(writes, () -> {
            owner(owner, input.dataGeneration());
            var session = find(owner, input.dataGeneration(), id, true);
            sessions.requireSessionWrite(session);
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
