package com.overload.overload_server.workoutsession;

import com.overload.overload_server.persistence.TransactionExecution;
import com.overload.overload_server.routine.RoutineRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;
import static com.overload.overload_server.workoutsession.WorkoutSessionFailure.Reason.*;

@Service
public class WorkoutSnapshotService {
    private final WorkoutSessionRepository sessions;
    private final WorkoutSnapshotRepository data;
    private final RoutineRepository routines;
    private final WorkoutAssessment assessments;
    private final Clock clock;
    private final TransactionTemplate writes;
    WorkoutSnapshotService(WorkoutSessionRepository sessions, WorkoutSnapshotRepository data, RoutineRepository routines, WorkoutAssessment assessments,ObjectProvider<Clock> clocks,
        @Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
        this.sessions = sessions;
        this.data = data;
        this.routines = routines;
        this.assessments=assessments;
        clock = clocks.getIfAvailable(Clock::systemUTC);
        writes = new TransactionTemplate(manager);
    }

    Ack save(UUID owner, UUID id, Save input) {
        var result=TransactionExecution.execute(writes, () -> {
            var locked=lock(owner,id,input);
            var session=locked.session();
            if (expired(session)) return null;
            guard(session,input.editorDeviceId(),input.writerEpoch());
            boolean duplicate=applySnapshot(session,input,locked.definitions(),locked.scope());
            return ack(session,duplicate);
        });
        if (result==null) throw failure(SESSION_AUTO_CLOSED);
        return result;
    }

    FinishAck finish(UUID owner,UUID id,Finish input) {
        var result=TransactionExecution.execute(writes, ()->{
            Save state=input.state();
            var locked=lock(owner,id,state);var session=locked.session();
            // Original FINISH confirmation is read-only even after explicit record deletion.
            if (!session.status.equals("FINISHED")&&expired(session)) return null;
            if (session.writerEpoch!=state.writerEpoch()||!session.editorDeviceId.equals(state.editorDeviceId())) throw failure(EDITOR_OWNERSHIP_LOST);
            if (session.status.equals("FINISHED")) {
                if (session.localRevision!=state.localRevision()||!hash(state).equals(session.lastSnapshotHash)||!input.endedAt().equals(session.endedAt)) throw failure(FINISH_REQUEST_MISMATCH);
                return finishAck(session,true);
            }
            sessions.requireSessionWrite(session);
            guard(session,state.editorDeviceId(),state.writerEpoch());
            afterStart(session,input.endedAt());
            var known=data.approvals(id).stream().map(r->r.id).collect(Collectors.toSet());
            for (var request:state.snapshot().approvalRequests()) if (!known.contains(request.id())&&request.occurredAt().isAfter(input.endedAt())) throw failure(CLOCK_CONTEXT_INVALID);
            boolean duplicateSnapshot=applySnapshot(session,state,locked.definitions(),locked.scope());
            for (var set:data.sets(id)) if (set.deletedAt==null&&set.completedAt.isAfter(input.endedAt())) throw failure(CLOCK_CONTEXT_INVALID);
            for (var cardio:data.cardio(id)) if (cardio.deletedAt==null&&cardio.occurredAt.isAfter(input.endedAt())) throw failure(CLOCK_CONTEXT_INVALID);
            if (duplicateSnapshot) { incrementable(session);session.version++; }
            Instant now=clock.instant().truncatedTo(ChronoUnit.MICROS);session.updatedAt=now;
            session.completionAssessment=json(assessments.assess(session,now));
            session.endedAt=input.endedAt();session.closedAt=now;session.status="FINISHED";
            data.flush();return finishAck(session,false);
        });
        if (result==null) throw failure(SESSION_AUTO_CLOSED);
        return result;
    }

    private FinishAck finishAck(WorkoutSession session,boolean duplicate) {
        var assessment=JSON.readValue(session.completionAssessment,WorkoutAssessment.Result.class);
        return new FinishAck(session.userId,Long.toString(session.dataGeneration),session.id,session.status,Long.toString(session.writerEpoch),
                Long.toString(session.localRevision),assessment.sessionVersion(),Long.toString(session.version),session.deletedAt,session.lastSnapshotSavedAt,session.lastWorkoutSyncedAt,
                session.endedAt,session.closedAt,duplicate,data.results(session.id,data.approvals(session.id)),assessment);
    }

    private record Locked(WorkoutSession session, RoutineRepository.ApprovalScope scope,
            Map<UUID,com.overload.overload_server.catalog.StandardExercise> definitions) { }

    private Locked lock(UUID owner,UUID id,Save input) {
        owner(owner,input.dataGeneration());
        UUID source=data.source(owner,input.dataGeneration(),id);
        if(source==null&&!input.snapshot().approvalRequests().isEmpty())throw failure(DELOAD_APPROVAL_FORBIDDEN);
        var scope=input.snapshot().approvalRequests().isEmpty()?null:routines.lockForWorkoutApproval(owner,input.dataGeneration(),source);
        var definitions=data.definitions(owner,input.dataGeneration(),input.snapshot().sessionExercises().stream()
                .filter(e->e.kind().equals("WEIGHT")).map(Exercise::standardExerciseId).distinct().toList());
        return new Locked(find(owner,input.dataGeneration(),id),scope,definitions);
    }

    private boolean expired(WorkoutSession session) {
        if(session.deletedAt!=null)throw failure(SESSION_DELETED);
        if (session.expireIfDue(clock.instant())) data.flush();
        return session.status.equals("AUTO_CLOSED");
    }

    private boolean applySnapshot(WorkoutSession session,Save input,
            Map<UUID,com.overload.overload_server.catalog.StandardExercise> definitions,RoutineRepository.ApprovalScope scope) {
        UUID id=session.id;
        String hash = hash(input);
        if (input.localRevision()<session.localRevision)throw failure(REVISION_STALE);
        if (input.localRevision()==session.localRevision) {
            if (!hash.equals(session.lastSnapshotHash))throw failure(REVISION_PAYLOAD_MISMATCH);
            return true;
        }
        sessions.requireSessionWrite(session);
        incrementable(session);
        data.validateManufacturers(java.util.stream.Stream.concat(
                input.snapshot().appliedPlan().weightExercises().stream().map(WeightPlan::manufacturerId),
                input.snapshot().performedSets().stream().map(SetValue::manufacturerId)).toList());
        var items = index(data.exercises(id), e -> e.id);
        var oldPlan = JSON.readValue(session.appliedPlan, Plan.class);
        var previousCardio = index(oldPlan.cardioActivities(), CardioPlan::sessionActivityId);
        var plannedCardio = index(input.snapshot().appliedPlan().cardioActivities(), CardioPlan::sessionActivityId);
        var initial = index(InitialPlan.read(session.initialPlan).cardioActivities(), InitialPlan.Cardio::sessionActivityId);
        for (var item:input.snapshot().sessionExercises()) {
            var old = items.get(item.id());
            if (old!=null) {
                if (!old.value().equals(item))throw failure(IMMUTABLE_RECORD_CONTEXT);
                if (item.kind().equals("CARDIO") && plannedCardio.containsKey(item.id())) {
                    var plan = cardioIdentity(plannedCardio.get(item.id()));
                    if (!matchesStoredCardio(old, plan, previousCardio.get(item.id()), initial.get(item.id()))) {
                        throw failure(IMMUTABLE_RECORD_CONTEXT);
                    }
                }
            }
            else {
                available("session_exercises", item.id());
                String name = null;
                if (item.kind().equals("WEIGHT")) {
                    var definition = definitions.get(item.standardExerciseId());
                    if (definition==null||!definition.isSelectableForSetsReps())throw failure(EXERCISE_NOT_FOUND);
                    name = definition.getName();
                }
                var added = new SessionExercise(session, item, name);
                if (item.kind().equals("CARDIO")) {
                    var plan = cardioIdentity(plannedCardio.get(item.id()));
                    if (!Objects.equals(plan.standardExerciseId(), item.standardExerciseId()) || !data.selectableCardio(plan)) {
                        throw failure(INVALID_SNAPSHOT);
                    }
                    added.captureCardio(plan.activityId(), plan.kind(), plan.name());
                }
                data.insert(added);
                items.put(added.id, added);
            }
        }
        validatePlan(input.snapshot().appliedPlan(), items);
        var approvals = index(data.approvals(id), r -> r.id);
        var applied = approvals(session, input.snapshot().approvalRequests(), items, approvals, scope, false);
        boolean changed = facts(session, input.snapshot(), items, approvals);
        if (!oldPlan.equals(input.snapshot().appliedPlan())) {
            session.appliedPlan = json(input.snapshot().appliedPlan());
            changed = true;
        }
        completeApprovals(scope, applied);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        session.version++;
        session.updatedAt = now;
        session.localRevision = input.localRevision();
        session.lastSnapshotHash = hash;
        session.lastSnapshotSavedAt = now;
        if (changed)session.lastWorkoutSyncedAt = now;
        data.flush();
        return false;
    }

    private static com.overload.overload_server.routine.RoutineRequests.Plan cardioIdentity(CardioPlan item) {
        if (item == null) throw failure(INVALID_SNAPSHOT);
        return com.overload.overload_server.routine.RoutineRequests.readCanonicalCardioPlan(item.plan());
    }

    private static boolean matchesStoredCardio(SessionExercise stored, com.overload.overload_server.routine.RoutineRequests.Plan plan,
                                               CardioPlan previous, InitialPlan.Cardio initial) {
        if (!Objects.equals(stored.standardExerciseId, plan.standardExerciseId())) return false;
        if (stored.standardExerciseId != null) {
            return Objects.equals(stored.cardioActivityId, plan.activityId())
                    && Objects.equals(stored.cardioKind, plan.kind()) && Objects.equals(stored.nameAtAddition, plan.name());
        }
        if (previous != null) return plan.sameCardioIdentity(cardioIdentity(previous));
        if (initial != null) {
            var original = initial.plan();
            return Objects.equals(original.activityId(), plan.activityId())
                    && (plan.activityId() == null || (original.name().equals(plan.name()) && original.kind().equals(plan.kind())));
        }
        return plan.activityId() == null;
    }

    Resolution resolve(UUID owner, UUID id, Resolve input) {
        var result=TransactionExecution.execute(writes, () -> {
            owner(owner, input.dataGeneration());
            UUID source = data.source(owner, input.dataGeneration(), id);
            if(source==null)throw failure(DELOAD_APPROVAL_FORBIDDEN);
            var scope = input.decision().equals("REAPPROVE")?routines.lockForWorkoutApproval(owner, input.dataGeneration(), source):null;
            var session = find(owner, input.dataGeneration(), id);
            if(session.deletedAt!=null)throw failure(SESSION_DELETED);
            if (session.expireIfDue(clock.instant())) { data.flush(); return null; }
            if (session.status.equals("AUTO_CLOSED")) return null;
            guard(session, input.editorDeviceId(), input.writerEpoch());
            return resolveLocked(session,input,scope);
        });
        if (result==null) throw failure(SESSION_AUTO_CLOSED);
        return result;
    }

    WorkoutRecordData.ResolutionAck resolveClosed(UUID owner,UUID id,WorkoutRecordData.ClosedResolve input) {
        return TransactionExecution.execute(writes, ()->{
            owner(owner,input.scope().dataGeneration());
            UUID source=data.source(owner,input.scope().dataGeneration(),id);
            if(source==null)throw failure(DELOAD_APPROVAL_FORBIDDEN);
            var scope=input.newApprovalRequest()==null?null:routines.lockForWorkoutApproval(owner,input.scope().dataGeneration(),source);
            var session=find(owner,input.scope().dataGeneration(),id);
            WorkoutRecordService.closed(session,input.scope().expectedVersion());
            long before=session.version;
            var request=new Resolve(input.scope().dataGeneration(),session.editorDeviceId,session.writerEpoch,session.version,
                    session.localRevision,input.sessionExerciseId(),input.pendingRequestIds(),input.decision(),input.occurredAt(),input.newApprovalRequest());
            var result=resolveLocked(session,request,scope);
            return new WorkoutRecordData.ResolutionAck(owner,Long.toString(session.dataGeneration),id,session.status,
                    Long.toString(session.version),before!=session.version,result.approvalResults());
        });
    }

    private Resolution resolveLocked(WorkoutSession session,Resolve input,RoutineRepository.ApprovalScope scope) {
        UUID id=session.id;
        var requests = index(data.approvals(id), r -> r.id);
        var selected = new ArrayList<WeightApprovalRequest>();
        for (UUID request:input.pendingRequestIds()) {
            var row = requests.get(request);
            if (row==null||!row.exerciseId.equals(input.sessionExerciseId()))throw failure(RESOLUTION_CONFLICT);
            selected.add(row);
        }
        // KEEP_CURRENT needs no receipt: immutable resolution facts remain queryable in the session.
        boolean confirmed = selected.stream().allMatch(r -> "NOT_APPLIED".equals(r.status)&&Objects.equals(r.resolvedAt, input.occurredAt())
            &&(input.newApprovalRequest()==null?"KEPT_CURRENT".equals(r.reason)&&r.resolvedBy==null:
            "REAPPROVED".equals(r.reason)&&input.newApprovalRequest().id().equals(r.resolvedBy)));
        if (confirmed) {
            if (input.newApprovalRequest()!=null) {
                var row = requests.get(input.newApprovalRequest().id());
                if (row==null||!row.value().equals(input.newApprovalRequest()))throw failure(APPROVAL_REQUEST_ID_CONFLICT);
            }
            return resolution(session);
        }
        sessions.requireSessionWrite(session);
        if (session.version!=input.expectedSessionVersion()||session.localRevision!=input.expectedLocalRevision())throw failure(RESOLUTION_CONFLICT);
        var pending = requests.values().stream().filter(r -> r.exerciseId.equals(input.sessionExerciseId())&&r.status.equals("PENDING")).map(r -> r.id).collect(Collectors.toSet());
        if (!pending.equals(new HashSet<>(input.pendingRequestIds())))throw failure(RESOLUTION_CONFLICT);
        incrementable(session);
        afterStart(session, input.occurredAt());
        for (var row:selected)if (input.occurredAt().isBefore(row.occurredAt))throw failure(CLOCK_CONTEXT_INVALID);
        if (input.newApprovalRequest()!=null) {
            var initial = initialWeight(session, input.sessionExerciseId());
            if (initial==null||!scope.available(initial.sourcePlacementId()))throw failure(SOURCE_NOT_APPROVABLE);
            if (scope.version()!=input.newApprovalRequest().expectedRoutineVersion()
                ||scope.weight(initial.sourcePlacementId()).compareTo(new BigDecimal(input.newApprovalRequest().previousApprovedWeightKg()))!=0)throw failure(RESOLUTION_CONFLICT);
        }
        for (var row:selected) {
            row.status = "NOT_APPLIED";
            row.reason = input.newApprovalRequest()==null?"KEPT_CURRENT":"REAPPROVED";
            row.resolvedAt = input.occurredAt();
            row.resolvedBy = input.newApprovalRequest()==null?null:input.newApprovalRequest().id();
        }
        if (input.newApprovalRequest()!=null) {
            var added = approvals(session, List.of(input.newApprovalRequest()), index(data.exercises(id), e -> e.id), requests, scope, true);
            if (added.size()!=1)throw failure(RESOLUTION_CONFLICT);
            completeApprovals(scope, added);
        }
        session.version++;
        session.updatedAt = clock.instant();
        data.flush();
        return resolution(session);
    }

    private List<WeightApprovalRequest> approvals(WorkoutSession session, List<Approval> inputs, Map<UUID, SessionExercise> items,
        Map<UUID, WeightApprovalRequest> requests, RoutineRepository.ApprovalScope scope, boolean resolution) {
        long before = scope==null?0:scope.version();
        var applied = new ArrayList<WeightApprovalRequest>();
        for (var input:inputs) {
            var old = requests.get(input.id());
            if (old!=null) {
                if (!old.value().equals(input))throw failure(APPROVAL_REQUEST_ID_CONFLICT);
                continue;
            }
            available("session_weight_approval_requests", input.id());
            item(items, input.sessionExerciseId(), "WEIGHT");
            afterStart(session, input.occurredAt());
            long max = requests.values().stream().filter(r -> r.exerciseId.equals(input.sessionExerciseId())).mapToLong(r -> r.sequence).max().orElse(0);
            if (max==Long.MAX_VALUE)throw failure(WRITE_CONFLICT);
            if (input.sequence()<=max)throw failure(APPROVAL_SEQUENCE_CONFLICT);
            if (input.sequence()!=max+1)throw failure(APPROVAL_SEQUENCE_GAP);
            if (requests.values().stream().anyMatch(r -> r.exerciseId.equals(input.sessionExerciseId())&&input.occurredAt().isBefore(r.occurredAt)))throw failure(CLOCK_CONTEXT_INVALID);
            WeightApprovalRequest previous = input.previousRequestId()==null?null:requests.get(input.previousRequestId());
            if (input.previousRequestId()!=null&&(previous==null||!previous.exerciseId.equals(input.sessionExerciseId())||previous.sequence>=input.sequence()
                ||previous.nextWeight.compareTo(new BigDecimal(input.previousApprovedWeightKg()))!=0))throw failure(INVALID_SNAPSHOT);
            if (previous!=null&&input.occurredAt().isBefore(previous.occurredAt))throw failure(CLOCK_CONTEXT_INVALID);
            var row = new WeightApprovalRequest(session.id, input);
            row.createdAfterClosure=!session.status.equals("ACTIVE");
            var initial = initialWeight(session, row.exerciseId);
            String reason = null;
            if (initial==null)reason = "NO_SOURCE_PLACEMENT";
            else if (scope==null||!scope.available(initial.sourcePlacementId()))reason = "SOURCE_REMOVED";
            else if (previous!=null&&!previous.status.equals("APPLIED"))reason = previous.status.equals("PENDING")?"PREDECESSOR_PENDING":"PREDECESSOR_NOT_APPLIED";
            else if (requests.values().stream().anyMatch(r -> r.exerciseId.equals(row.exerciseId)&&r.status.equals("PENDING")))reason = "PREDECESSOR_PENDING";
            else if (!ownVersionPath(input.expectedRoutineVersion(), before, requests.values()))reason = "ROUTINE_CHANGED";
            else if (scope.weight(initial.sourcePlacementId()).compareTo(row.previousWeight)!=0)reason = "ROUTINE_CHANGED";
            if (reason!=null) {
                if (resolution)throw failure(RESOLUTION_CONFLICT);
                row.pending(reason);
            }
            else {
                row.status = "APPLIED";
                row.versionBefore = before;
                row.versionAfter = before;
                applied.add(row);
            }
            data.insert(row);
            requests.put(row.id, row);
            if (reason==null)scope.apply(initial.sourcePlacementId(), session.id, row.exerciseId, row.id, InitialPlan.read(session.initialPlan).routineName(), initial.exerciseName(), row.nextWeight, row.occurredAt, clock.instant());
        }
        return applied;
    }

    private static boolean ownVersionPath(long expected, long current, Collection<WeightApprovalRequest> requests) {
        if (expected>current)return false;
        long next = expected;
        var edges = new HashSet<Long>();
        for (var r:requests)if ("APPLIED".equals(r.status)&&r.versionBefore!=null&&r.versionAfter!=null
            &&r.versionBefore<Long.MAX_VALUE&&r.versionAfter==r.versionBefore+1)edges.add(r.versionBefore);
        while (next<current) {
            if (!edges.contains(next))return false;
            next++;
        }
        return true;
    }

    private void completeApprovals(RoutineRepository.ApprovalScope scope, List<WeightApprovalRequest> rows) {
        if (scope==null)return;
        if (scope.changed()&&scope.version()==Long.MAX_VALUE)throw failure(WRITE_CONFLICT);
        scope.complete(clock.instant());
        for (var row:rows)row.versionAfter = scope.version();
    }

    private boolean facts(WorkoutSession session, State state, Map<UUID, SessionExercise> items, Map<UUID, WeightApprovalRequest> approvals) {
        var oldSets = index(data.sets(session.id), s -> s.id);
        var oldCardio = index(data.cardio(session.id), s -> s.id);
        var sets = index(state.performedSets(), SetValue::id);
        var cardio = index(state.cardioResults(), Cardio::id);
        var deleteSets = new HashSet<>(state.deletedPerformedSetIds());
        var deleteCardio = new HashSet<>(state.deletedCardioResultIds());
        if (sets.keySet().stream().anyMatch(deleteSets::contains)||cardio.keySet().stream().anyMatch(deleteCardio::contains))throw failure(INVALID_SNAPSHOT);
        for (var s:oldSets.values())if (s.deletedAt==null&&!sets.containsKey(s.id)&&!deleteSets.contains(s.id))throw failure(SNAPSHOT_INCOMPLETE);
        for (var c:oldCardio.values())if (c.deletedAt==null&&!cardio.containsKey(c.id)&&!deleteCardio.contains(c.id))throw failure(SNAPSHOT_INCOMPLETE);
        var plannedWeights = state.appliedPlan().weightExercises().stream().map(WeightPlan::sessionExerciseId).collect(Collectors.toSet());
        var plannedCardio = index(state.appliedPlan().cardioActivities(), CardioPlan::sessionActivityId);
        // Removal is only legal after explicit cancellation of every existing performance; no implicit deletion.
        if (sets.values().stream().anyMatch(s -> !plannedWeights.contains(s.sessionExerciseId()))||cardio.values().stream().anyMatch(c -> !plannedCardio.containsKey(c.sessionActivityId())))throw failure(PERFORMED_EXERCISE_IN_USE);
        Instant now = clock.instant();
        boolean changed = false;
        for (UUID id:deleteSets) {
            var row = oldSets.get(id);
            if (row==null)throw failure(RECORD_NOT_FOUND);
            if (row.deletedAt==null) {
                audit(session, "SET", id, row.value(), null);
                row.deletedAt = now;
                changed = true;
            }
        }
        for (UUID id:deleteCardio) {
            var row = oldCardio.get(id);
            if (row==null)throw failure(RECORD_NOT_FOUND);
            if (row.deletedAt==null) {
                audit(session, "CARDIO", id, row.value(), null);
                row.deletedAt = now;
                changed = true;
            }
        }
        data.flush();
        // vacate active ordinal/activity uniqueness before inserting replacements
        var ordinals = new HashSet<String>();
        for (var input:state.performedSets()) {
            item(items, input.sessionExerciseId(), "WEIGHT");
            afterStart(session, input.completedAt());
            if (!ordinals.add(input.sessionExerciseId()+":"+input.ordinal()))throw failure(INVALID_SNAPSHOT);
            var row = oldSets.get(input.id());
            if (row!=null) {
                if (row.deletedAt!=null)throw failure(RECORD_DELETED);
                var old = row.value();
                input=input.preserveOmittedLoad(old);
                if (!old.sessionExerciseId().equals(input.sessionExerciseId())||old.ordinal()!=input.ordinal()
                    ||!old.completedAt().equals(input.completedAt())||!old.weightContext().equals(input.weightContext())
                    ||!Objects.equals(old.manufacturerId(), input.manufacturerId())||!Objects.equals(old.loadType(), input.loadType()))throw failure(IMMUTABLE_RECORD_CONTEXT);
                if (!old.equals(input)) {
                    audit(session, "SET", row.id, old, input);
                    row.apply(input);
                    changed = true;
                }
            }
            else {
                available("performed_sets", input.id());
                row = new PerformedSet(session.id, input);
                context(session, row, approvals);
                data.insert(row);
                changed = true;
            }
        }
        var activities = new HashSet<UUID>();
        for (var input:state.cardioResults()) {
            item(items, input.sessionActivityId(), "CARDIO");
            if (!activities.add(input.sessionActivityId()))throw failure(INVALID_SNAPSHOT);
            validateCardio(session,input,plannedCardio.get(input.sessionActivityId()).plan());
            var row = oldCardio.get(input.id());
            if (row!=null) {
                if (row.deletedAt!=null)throw failure(RECORD_DELETED);
                var old = row.value();
                if(input.result()!=null){input=new Cardio(input.id(),input.sessionActivityId(),input.status(),input.startedAt(),input.occurredAt(),CardioMeasurements.preserveOmitted(old.result(),input.result()));validateCardio(session,input,plannedCardio.get(input.sessionActivityId()).plan());}
                if (!old.sessionActivityId().equals(input.sessionActivityId())||!old.status().equals(input.status())||!Objects.equals(old.startedAt(), input.startedAt())
                    ||!old.occurredAt().equals(input.occurredAt())||!Objects.equals(old.result()==null?null:old.result().timerMs(), input.result()==null?null:input.result().timerMs()))throw failure(IMMUTABLE_RECORD_CONTEXT);
                if (!old.equals(input)) {
                    audit(session, "CARDIO", row.id, old, input);
                    row.apply(input);
                    changed = true;
                }
            }
            else {
                available("cardio_results", input.id());
                data.insert(new CardioResult(session.id, input));
                changed = true;
            }
        }
        return changed;
    }

    static void validateCardio(WorkoutSession session,Cardio input,tools.jackson.databind.JsonNode plan) {
        afterStart(session,input.occurredAt());
        if(input.startedAt()!=null) {
            afterStart(session,input.startedAt());
            if(input.occurredAt().isBefore(input.startedAt()))throw failure(CLOCK_CONTEXT_INVALID);
        }
        if(input.result()!=null) {
            if(input.result().timerMs()>Duration.between(input.startedAt(),input.occurredAt()).toMillis())throw failure(CLOCK_CONTEXT_INVALID);
            if(input.result().distanceKm()!=null&&!Set.of("TREADMILL","BIKE","OUTDOOR").contains(plan.get("kind").asString()))throw failure(INVALID_SNAPSHOT);
            if(input.result().measurement()!=null&&input.result().measurement().actualSpeedKmh()!=null&&!Set.of("TREADMILL","BIKE","OUTDOOR").contains(plan.get("kind").asString()))throw failure(INVALID_SNAPSHOT);
        }
    }

    static void context(WorkoutSession session, PerformedSet set, Map<UUID, WeightApprovalRequest> requests) {
        if(session.deload()&&!set.contextKind.equals("TODAY_ONLY"))throw failure(DELOAD_APPROVAL_FORBIDDEN);
        if (set.contextKind.equals("INITIAL_APPROVED")) {
            var initial = initialWeight(session, set.exerciseId);
            if (initial==null)throw failure(INVALID_SNAPSHOT);
            set.approvalState = "INITIAL_APPROVED";
            set.approvedWeight = new BigDecimal(initial.approvedWeightKg());
        }
        else if (set.contextKind.equals("TODAY_ONLY"))set.approvalState = "TODAY_ONLY";
        else {
            var request = requests.get(set.approvalRequestId);
            if (request==null||!request.exerciseId.equals(set.exerciseId))throw failure(INVALID_SNAPSHOT);
            if (set.completedAt.isBefore(request.occurredAt))throw failure(CLOCK_CONTEXT_INVALID);
            set.approvalState = request.status;
            if (request.status.equals("APPLIED"))set.approvedWeight = request.nextWeight;
        }
    }

    private static void validatePlan(Plan plan, Map<UUID, SessionExercise> items) {
        for (var p:plan.weightExercises())item(items, p.sessionExerciseId(), "WEIGHT");
        for (var p:plan.cardioActivities())item(items, p.sessionActivityId(), "CARDIO");
    }

    private static SessionExercise item(Map<UUID, SessionExercise> items, UUID id, String kind) {
        var item = items.get(id);
        if (item==null||!item.kind.equals(kind))throw failure(INVALID_SNAPSHOT);
        return item;
    }

    private void audit(WorkoutSession s, String kind, UUID id, Object before, Object after) {
        data.insert(new RecordChangeEvent(s.id, kind, id, before, after, clock.instant()));
    }

    static InitialPlan.Weight initialWeight(WorkoutSession s, UUID id) {
        return InitialPlan.read(s.initialPlan).weightExercises().stream().filter(p -> p.sessionExerciseId().equals(id)).findFirst().orElse(null);
    }

    private void available(String table, UUID id) {
        if (data.occupied(table, id)) {
            throw failure(table.equals("session_weight_approval_requests") ? APPROVAL_REQUEST_ID_CONFLICT : RECORD_ID_CONFLICT);
        }
    }

    private void owner(UUID user, long expected) {
        if (sessions.generation(user, true)!=expected)throw failure(DATA_GENERATION_MISMATCH);
    }

    private WorkoutSession find(UUID user, long generation, UUID id) {
        return sessions.find(user, generation, id, true).orElseThrow(() -> failure(SESSION_NOT_FOUND));
    }

    private static void guard(WorkoutSession s, UUID device, long epoch) {
        if(s.deletedAt!=null)throw failure(SESSION_DELETED);
        if (!s.status.equals("ACTIVE"))throw failure(SESSION_NOT_ACTIVE);
        if (s.writerEpoch!=epoch||!s.editorDeviceId.equals(device))throw failure(EDITOR_OWNERSHIP_LOST);
    }

    private static void incrementable(WorkoutSession s) {
        if (s.version==Long.MAX_VALUE)throw failure(WRITE_CONFLICT);
    }

    private static void afterStart(WorkoutSession s, Instant time) {
        if (time.isBefore(s.startedAt))throw failure(CLOCK_CONTEXT_INVALID);
    }

    private Ack ack(WorkoutSession s, boolean duplicate) {
        return new Ack(s.userId, Long.toString(s.dataGeneration), s.id, Long.toString(s.writerEpoch), Long.toString(s.localRevision), Long.toString(s.version), s.lastSnapshotSavedAt, s.lastWorkoutSyncedAt, duplicate, data.results(s.id, data.approvals(s.id)));
    }

    private Resolution resolution(WorkoutSession s) {
        return new Resolution(s.userId, Long.toString(s.dataGeneration), s.id, Long.toString(s.writerEpoch), Long.toString(s.localRevision), Long.toString(s.version), s.lastWorkoutSyncedAt, data.results(s.id, data.approvals(s.id)));
    }

    private static String hash(Save input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(input).getBytes(StandardCharsets.UTF_8)));
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }


    private static <T>Map<UUID, T> index(List<T> list, Function<T, UUID> key) {
        return list.stream().collect(Collectors.toMap(key, Function.identity()));
    }

    private static WorkoutSessionFailure failure(WorkoutSessionFailure.Reason reason) {
        return new WorkoutSessionFailure(reason);
    }
}
