package com.overload.overload_server.workoutsession;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;
import static com.overload.overload_server.workoutsession.WorkoutRecordData.*;
import static com.overload.overload_server.workoutsession.WorkoutSessionFailure.Reason.*;

@Service
public class WorkoutRecordService {
    private final WorkoutSessionRepository sessions;
    private final WorkoutSnapshotRepository data;
    private final WorkoutAssessment assessments;
    private final Clock clock;
    private final TransactionTemplate writes,reads;

    WorkoutRecordService(WorkoutSessionRepository sessions,WorkoutSnapshotRepository data,WorkoutAssessment assessments,
            ObjectProvider<Clock> clocks,@Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
        this.sessions=sessions;this.data=data;this.assessments=assessments;clock=clocks.getIfAvailable(Clock::systemUTC);
        writes=new TransactionTemplate(manager);reads=new TransactionTemplate(manager);
        reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    WorkoutRecordData.Ack edit(UUID owner,UUID id,Edit input) {
        return writes.execute(tx->{
            var session=lock(owner,id,input.scope());var events=new ArrayList<UUID>();
            var sets=index(data.sets(id),s->s.id);var cardio=index(data.cardio(id),c->c.id);
            for(var correction:input.setCorrections()) {
                var row=present(sets,correction.id());alive(row.deletedAt);
                WorkoutSnapshotRequests.validateBodyweightLoad(row.loadType,correction.bodyweightLoad());
                var before=row.value();var after=new SetValue(row.id,row.exerciseId,row.ordinal,correction.weightKg(),correction.reps(),row.completedAt,before.weightContext(),row.manufacturerId,row.loadType,correction.bodyweightLoad()).preserveOmittedLoad(before);
                if(!before.equals(after)) {
                    audit(session,"SET",row.id,"CORRECTED",before,after,null,events);row.apply(after);
                }
            }
            for(var correction:input.cardioCorrections()) {
                var row=present(cardio,correction.id());alive(row.deletedAt);
                if(!row.status.equals("COMPLETED"))throw failure(INVALID_SNAPSHOT);
                if(!correction.corrected()&&correction.activeMs()!=row.timerMs)throw failure(INVALID_SNAPSHOT);
                var before=row.value();var after=new Cardio(row.id,row.activityId,row.status,row.startedAt,row.occurredAt,
                        CardioMeasurements.preserveOmitted(before.result(),new CardioValue(row.timerMs,correction.activeMs(),correction.corrected(),correction.distanceKm(),correction.averageBpm(),correction.measurement())));
                WorkoutSnapshotService.validateCardio(session,after,cardioPlan(session,row.activityId));
                if(!before.equals(after)) {
                    audit(session,"CARDIO",row.id,"CORRECTED",before,after,null,events);row.apply(after);
                }
            }
            for(UUID key:input.deletedPerformedSetIds()) {
                var row=present(sets,key);
                if(row.deletedAt==null) { audit(session,"SET",key,"DELETED",row.value(),null,null,events);row.deletedAt=now(); }
            }
            for(UUID key:input.deletedCardioResultIds()) {
                var row=present(cardio,key);
                if(row.deletedAt==null) { audit(session,"CARDIO",key,"DELETED",row.value(),null,null,events);row.deletedAt=now(); }
            }
            changed(session,!events.isEmpty());data.flush();return ack(session,events,List.of(),List.of());
        });
    }

    WorkoutRecordData.Ack recover(UUID owner,UUID id,Recovery input) {
        return writes.execute(tx->{
            var session=lock(owner,id,input.scope());var events=new ArrayList<UUID>();var added=new ArrayList<UUID>();var existing=new ArrayList<UUID>();
            data.validateManufacturers(input.performedSets().stream().map(SetValue::manufacturerId).toList());
            long epoch=Long.parseLong(input.origin().writerEpoch());
            if(epoch>session.writerEpoch||epoch==0&&!input.origin().editorDeviceId().equals(session.startedDeviceId)
                    ||epoch==session.writerEpoch&&!input.origin().editorDeviceId().equals(session.editorDeviceId))throw failure(INVALID_RECOVERY_ORIGIN);
            var items=index(data.exercises(id),e->e.id);var sets=index(data.sets(id),s->s.id);var cardio=index(data.cardio(id),c->c.id);
            var requests=index(data.approvals(id),r->r.id);
            var slots=new HashSet<String>();sets.values().stream().filter(s->s.deletedAt==null).forEach(s->slots.add(s.exerciseId+":"+s.ordinal));
            var activities=cardio.values().stream().filter(c->c.deletedAt==null).map(c->c.activityId).collect(Collectors.toSet());
            var recoveredClaims=new HashMap<UUID,Context>();
            for(var event:data.changes(id))if(event.recordKind.equals("SET")&&event.changeKind.equals("RECOVERED")) {
                recoveredClaims.put(event.recordId,JSON.treeToValue(JSON.readTree(event.changeContext).get("claimedWeightContext"),Context.class));
            }
            for(var value:input.performedSets()) {
                item(items,value.sessionExerciseId(),"WEIGHT");time(session,value.completedAt());
                var old=sets.get(value.id());
                if(old!=null) {
                    alive(old.deletedAt);
                    var oldValue=old.value();
                    var claim=recoveredClaims.getOrDefault(old.id,oldValue.weightContext());
                    var expected=new SetValue(old.id,old.exerciseId,old.ordinal,decimal(old.weight),old.reps,old.completedAt,claim,old.manufacturerId,old.loadType,oldValue.bodyweightLoad());
                    if(!expected.equals(value))throw failure(RECORD_ID_CONFLICT);
                    existing.add(old.id);continue;
                }
                available("performed_sets",value.id());
                if(!slots.add(value.sessionExerciseId()+":"+value.ordinal()))throw failure(RECORD_SLOT_CONFLICT);
                var row=new PerformedSet(id,value);recoverContext(session,row,requests);
                data.insert(row);added.add(row.id);
                var details=Map.of("origin",input.origin(),"claimedWeightContext",value.weightContext());
                audit(session,"SET",row.id,"RECOVERED",null,row.value(),details,events);
            }
            for(var value:input.cardioResults()) {
                item(items,value.sessionActivityId(),"CARDIO");time(session,value.occurredAt());
                WorkoutSnapshotService.validateCardio(session,value,cardioPlan(session,value.sessionActivityId()));
                var old=cardio.get(value.id());
                if(old!=null) {
                    alive(old.deletedAt);if(!old.value().equals(value))throw failure(RECORD_ID_CONFLICT);
                    existing.add(old.id);continue;
                }
                available("cardio_results",value.id());
                if(!activities.add(value.sessionActivityId()))throw failure(RECORD_SLOT_CONFLICT);
                data.insert(new CardioResult(id,value));added.add(value.id());
                audit(session,"CARDIO",value.id(),"RECOVERED",null,value,Map.of("origin",input.origin()),events);
            }
            changed(session,!events.isEmpty());data.flush();return ack(session,events,added,existing);
        });
    }

    WorkoutRecordData.Ack delete(UUID owner,UUID id,Scope scope) {
        return writes.execute(tx->{
            var session=lock(owner,id,scope);var events=new ArrayList<UUID>();
            var at=now();var before=new LinkedHashMap<String,Object>();before.put("deletedAt",null);
            audit(session,"SESSION",id,"DELETED",before,Map.of("deletedAt",at),null,events);
            session.deletedAt=at;changed(session,true);data.flush();return ack(session,events,List.of(),List.of());
        });
    }

    Assessment assessment(UUID owner,UUID id) {
        return reads.execute(tx->{
            var session=find(owner,id);requireClosed(session);
            String reason=session.deletedAt!=null?"SESSION_DELETED":session.status.equals("AUTO_CLOSED")?"AUTO_CLOSED":null;
            var current=reason==null?new CurrentAssessment("EVALUATED",List.of(),null,assessments.assess(session,now()))
                    :new CurrentAssessment("EXCLUDED",List.of(reason),"UNAVAILABLE",null);
            return new Assessment(owner,Long.toString(session.dataGeneration),id,session.status,Long.toString(session.version),
                    session.completionAssessment==null?null:JSON.readTree(session.completionAssessment),current);
        });
    }
    Changes changes(UUID owner,UUID id) {
        return reads.execute(tx->{
            var session=find(owner,id);
            var events=data.changes(id).stream().map(e->new Change(e.id,e.recordKind,e.recordId,e.changeKind,
                    tree(e.beforeValue),tree(e.afterValue),tree(e.changeContext),e.recordedAt)).toList();
            return new Changes(owner,Long.toString(session.dataGeneration),id,Long.toString(session.version),events);
        });
    }

    private void recoverContext(WorkoutSession session,PerformedSet row,Map<UUID,WeightApprovalRequest> requests) {
        boolean verified=row.contextKind.equals("TODAY_ONLY")
                ||row.contextKind.equals("INITIAL_APPROVED")&&WorkoutSnapshotService.initialWeight(session,row.exerciseId)!=null;
        if(row.contextKind.equals("APPROVAL_REQUEST")) {
            var request=requests.get(row.approvalRequestId);
            if(request!=null&&request.exerciseId.equals(row.exerciseId)) {
                if(row.completedAt.isBefore(request.occurredAt))throw failure(CLOCK_CONTEXT_INVALID);
                verified=!request.createdAfterClosure&&Set.of("APPLIED","PENDING").contains(request.status);
            }
        }
        if(verified)WorkoutSnapshotService.context(session,row,requests);
        else { row.contextKind="UNVERIFIED";row.approvalRequestId=null;row.approvalState="UNVERIFIED";row.approvedWeight=null; }
    }
    private JsonNode cardioPlan(WorkoutSession session,UUID activity) {
        var plan=JSON.readValue(session.appliedPlan,Plan.class).cardioActivities().stream().filter(p->p.sessionActivityId().equals(activity)).findFirst();
        if(plan.isPresent())return plan.get().plan();
        return InitialPlan.read(session.initialPlan).cardioActivities().stream().filter(p->p.sessionActivityId().equals(activity))
                .<JsonNode>map(p->JSON.valueToTree(p.plan())).findFirst().orElseThrow(()->failure(RECOVERY_CONTEXT_UNAVAILABLE));
    }
    private void time(WorkoutSession session,Instant at) {
        if(at.isBefore(session.startedAt)||session.status.equals("FINISHED")&&at.isAfter(session.endedAt))throw failure(CLOCK_CONTEXT_INVALID);
    }
    private WorkoutSession lock(UUID owner,UUID id,Scope scope) {
        if(sessions.generation(owner,true)!=scope.dataGeneration())throw failure(DATA_GENERATION_MISMATCH);
        sessions.requireGeneralWrite(owner,scope.dataGeneration());
        var session=sessions.find(owner,scope.dataGeneration(),id,true).orElseThrow(()->failure(SESSION_NOT_FOUND));
        closed(session,scope.expectedVersion());return session;
    }
    private WorkoutSession find(UUID owner,UUID id) {
        return sessions.find(owner,sessions.generation(owner,false),id,false).orElseThrow(()->failure(SESSION_NOT_FOUND));
    }
    static void closed(WorkoutSession session,long expected) {
        if(session.deletedAt!=null)throw failure(SESSION_DELETED);
        requireClosed(session);
        if(session.version!=expected)throw failure(SESSION_VERSION_CONFLICT);
    }
    private static void requireClosed(WorkoutSession session) {
        if(!Set.of("FINISHED","AUTO_CLOSED").contains(session.status))throw failure(SESSION_NOT_CLOSED);
    }
    private void changed(WorkoutSession session,boolean changed) {
        if(changed) { if(session.version==Long.MAX_VALUE)throw failure(WRITE_CONFLICT);session.version++;session.updatedAt=now(); }
    }
    private void audit(WorkoutSession session,String kind,UUID record,String change,Object before,Object after,Map<String,?> details,List<UUID> ids) {
        if(session.version==Long.MAX_VALUE)throw failure(WRITE_CONFLICT);
        var context=new LinkedHashMap<String,Object>();context.put("schemaVersion",1);context.put("sessionVersion",Long.toString(session.version+1));
        if(details!=null)context.putAll(details);
        var event=new RecordChangeEvent(session.id,kind,record,change,before,after,context,now());data.insert(event);ids.add(event.id);
    }
    private WorkoutRecordData.Ack ack(WorkoutSession session,List<UUID> events,List<UUID> recovered,List<UUID> present) {
        return new WorkoutRecordData.Ack(session.userId,Long.toString(session.dataGeneration),session.id,session.status,Long.toString(session.version),
                !events.isEmpty(),List.copyOf(events),List.copyOf(recovered),List.copyOf(present),session.deletedAt);
    }
    private static void item(Map<UUID,SessionExercise> items,UUID id,String kind) {
        if(!items.containsKey(id)||!items.get(id).kind.equals(kind))throw failure(INVALID_SNAPSHOT);
    }
    private void available(String table,UUID id) { if(data.occupied(table,id))throw failure(RECORD_ID_CONFLICT); }
    private static void alive(Instant deletedAt) { if(deletedAt!=null)throw failure(RECORD_DELETED); }
    private static <T>T present(Map<UUID,T> items,UUID id) { if(!items.containsKey(id))throw failure(RECORD_NOT_FOUND);return items.get(id); }
    private static <T>Map<UUID,T> index(List<T> items,Function<T,UUID> id) { return items.stream().collect(Collectors.toMap(id,Function.identity())); }
    private static JsonNode tree(String value) { return value==null?null:JSON.readTree(value); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static WorkoutSessionFailure failure(WorkoutSessionFailure.Reason reason) { return new WorkoutSessionFailure(reason); }
}
