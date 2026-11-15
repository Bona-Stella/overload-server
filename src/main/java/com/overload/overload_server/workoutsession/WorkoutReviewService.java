package com.overload.overload_server.workoutsession;

import com.overload.overload_server.routine.RoutineRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotRequests.*;
import static com.overload.overload_server.workoutsession.WorkoutSessionFailure.Reason.*;

/** Frozen finish advice and explicit, online approval facts. Never changes recorded performance. */
@Service
public class WorkoutReviewService {
    public record Item(UUID sessionExerciseId,UUID sourcePlacementId,UUID standardExerciseId,String name,String loadType,
                       String recommendedWeightKg,WorkoutAssessment.ReviewExercise guidance) { }
    public record Frozen(int schemaVersion,String sessionVersion,Instant assessedAt,List<Item> exercises) { }
    public record Applied(UUID id,UUID eventId,UUID sessionExerciseId,UUID sourcePlacementId,String previousWeightKg,
                          String nextWeightKg,Instant occurredAt,Instant recordedAt,String source) { }
    public record Current(UUID sessionExerciseId,String approvedWeightKg,boolean available) { }
    public record View(UUID userId,String dataGeneration,UUID sessionId,String version,Frozen frozen,
                       String routineVersion,List<Current> current,List<Applied> approvals) { }
    record Choice(UUID id,UUID sessionExerciseId,String previousWeightKg,String nextWeightKg) { }
    record Approve(long generation,long expectedVersion,long expectedRoutineVersion,List<Choice> approvals) { }
    private final WorkoutSessionRepository sessions;
    private final WorkoutSnapshotRepository data;
    private final RoutineRepository routines;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes,reads;
    private final Clock clock;
    WorkoutReviewService(WorkoutSessionRepository sessions,WorkoutSnapshotRepository data,RoutineRepository routines,JdbcTemplate jdbc,
                         ObjectProvider<Clock> clocks,@Qualifier("workoutTransactionManager") PlatformTransactionManager manager){
        this.sessions=sessions;this.data=data;this.routines=routines;this.jdbc=jdbc;clock=clocks.getIfAvailable(Clock::systemUTC);
        writes=new TransactionTemplate(manager);reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    static Approve parse(String raw){
        var b=parseBody(raw);fields(b,"dataGeneration","expectedVersion","expectedRoutineVersion","confirm","approvals");
        if(!b.get("confirm").isBoolean()||!b.get("confirm").asBoolean())throw invalid();
        var choices=new ArrayList<Choice>();
        for(var v:array(b,"approvals")){fields(v,"id","sessionExerciseId","previousWeightKg","nextWeightKg");choices.add(new Choice(uuid(v,"id"),uuid(v,"sessionExerciseId"),decimal(v,"previousWeightKg"),decimal(v,"nextWeightKg")));}
        if(choices.isEmpty())throw invalid();unique(choices.stream().map(Choice::id).toList());unique(choices.stream().map(Choice::sessionExerciseId).toList());
        return new Approve(counter(b,"dataGeneration"),counter(b,"expectedVersion"),counter(b,"expectedRoutineVersion"),List.copyOf(choices));
    }
    private static tools.jackson.databind.JsonNode parseBody(String raw){return WorkoutSnapshotRequests.parse(raw);}
    static Frozen freeze(WorkoutSession session,WorkoutAssessment.WeightReview review){
        var initial=InitialPlan.read(session.initialPlan);var applied=JSON.readValue(session.appliedPlan,Plan.class);
        boolean changed=initial.weightExercises().stream().anyMatch(p->applied.weightExercises().stream().noneMatch(a->a.sessionExerciseId().equals(p.sessionExerciseId())&&Objects.equals(a.manufacturerId(),p.manufacturerId())&&Objects.equals(a.loadType(),p.loadType())));
        var rows=new ArrayList<Item>();
        for(var original:review.exercises()){
            var g=original;var exerciseId=g.sessionExerciseId();
            var p=initial.weightExercises().stream().filter(v->v.sessionExerciseId().equals(exerciseId)).findFirst().orElseThrow();
            if(session.deload()||changed)g=new WorkoutAssessment.ReviewExercise(g.sessionExerciseId(),"UNAVAILABLE",List.of(session.deload()?"DELOAD_SESSION":"SESSION_COMBINATION_CHANGED"),null,g.plannedSetCount(),g.completedSetCount(),g.minReps(),g.maxReps(),g.incomplete(),g.sets());
            String next=null;
            if(g.referenceWeightKg()!=null&&(g.advice().equals("INCREASE")||g.advice().equals("DECREASE"))){
                var step=Set.of("PLATE","STACK").contains(Objects.toString(p.loadType(),""))?new BigDecimal("5"):new BigDecimal("2");
                var value=new BigDecimal(g.referenceWeightKg());next=WorkoutSnapshotData.decimal((g.advice().equals("INCREASE")?value.add(step):value.subtract(step)).max(BigDecimal.ZERO));
            }
            rows.add(new Item(p.sessionExerciseId(),p.sourcePlacementId(),p.standardExerciseId(),p.exerciseName(),p.loadType(),next,g));
        }
        return new Frozen(1,review.sessionVersion(),review.assessedAt(),List.copyOf(rows));
    }
    View get(UUID owner,UUID id){return reads.execute(tx->{long generation=sessions.generation(owner,false);return view(find(owner,generation,id,false));});}
    View approve(UUID owner,UUID id,Approve input){return writes.execute(tx->{
        long generation=sessions.lockOwnerForStart(owner);if(generation!=input.generation())throw fail(DATA_GENERATION_MISMATCH);
        UUID source=data.source(owner,generation,id);
        var scope=source==null?null:routines.lockForWorkoutApproval(owner,generation,source);
        var session=find(owner,generation,id,true);var requests=data.approvals(id);
        // Confirmation of a committed request stays read-only even after later corrections or routine changes.
        boolean duplicate=true;
        for(var c:input.approvals()){
            var old=requests.stream().filter(a->a.id.equals(c.id())).findFirst().orElse(null);
            if(old==null){duplicate=false;continue;}
            if(!old.finalReview||!old.exerciseId.equals(c.sessionExerciseId())||old.previousWeight.compareTo(new BigDecimal(c.previousWeightKg()))!=0||old.nextWeight.compareTo(new BigDecimal(c.nextWeightKg()))!=0||old.expectedVersion!=input.expectedRoutineVersion())throw fail(APPROVAL_REQUEST_ID_CONFLICT);
        }
        if(duplicate)return view(session);
        if(session.deletedAt!=null)throw fail(SESSION_DELETED);
        if(!session.status.equals("FINISHED"))throw fail(SESSION_NOT_CLOSED);
        if(session.deload()||scope==null)throw fail(DELOAD_APPROVAL_FORBIDDEN);
        sessions.requireSessionWrite(session);
        if(session.version!=input.expectedVersion())throw fail(SESSION_VERSION_CONFLICT);
        if(scope.version()!=input.expectedRoutineVersion()||session.completionWeightReview==null)throw fail(RESOLUTION_CONFLICT);
        var frozen=JSON.readValue(session.completionWeightReview,Frozen.class);var initial=InitialPlan.read(session.initialPlan);
        var added=new ArrayList<WeightApprovalRequest>();var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        if(now.isBefore(session.closedAt))throw fail(CLOCK_CONTEXT_INVALID);
        for(var c:input.approvals()){
            if(requests.stream().anyMatch(a->a.id.equals(c.id())))throw fail(APPROVAL_REQUEST_ID_CONFLICT);
            if(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM session_weight_approval_requests WHERE id=?)",Boolean.class,c.id()))throw fail(APPROVAL_REQUEST_ID_CONFLICT);
            var item=frozen.exercises().stream().filter(v->v.sessionExerciseId().equals(c.sessionExerciseId())).findFirst().orElseThrow(()->fail(SOURCE_NOT_APPROVABLE));
            var p=initial.weightExercises().stream().filter(v->v.sessionExerciseId().equals(c.sessionExerciseId())).findFirst().orElseThrow(()->fail(SOURCE_NOT_APPROVABLE));
            var g=item.guidance();
            if(item.recommendedWeightKg()==null||!g.reasonCodes().isEmpty()||!scope.matches(p.sourcePlacementId(),p.standardExerciseId(),p.manufacturerId(),p.loadType(),p.setCount(),p.minReps(),p.maxReps()))throw fail(SOURCE_NOT_APPROVABLE);
            if(requests.stream().anyMatch(a->a.exerciseId.equals(c.sessionExerciseId())&&(a.finalReview||a.status.equals("PENDING"))))throw fail(RESOLUTION_CONFLICT);
            var previous=new BigDecimal(c.previousWeightKg());var next=new BigDecimal(c.nextWeightKg());
            if(scope.weight(p.sourcePlacementId()).compareTo(previous)!=0||previous.compareTo(new BigDecimal(g.referenceWeightKg()))!=0||previous.compareTo(next)==0)throw fail(RESOLUTION_CONFLICT);
            if(requests.stream().anyMatch(a->a.exerciseId.equals(c.sessionExerciseId())&&a.occurredAt.isAfter(now)))throw fail(CLOCK_CONTEXT_INVALID);
            long sequence=requests.stream().filter(a->a.exerciseId.equals(c.sessionExerciseId())).mapToLong(a->a.sequence).max().orElse(0);
            if(sequence==Long.MAX_VALUE)throw fail(WRITE_CONFLICT);
            var row=new WeightApprovalRequest(id,new Approval(c.id(),c.sessionExerciseId(),sequence+1,null,input.expectedRoutineVersion(),c.previousWeightKg(),c.nextWeightKg(),now));
            row.finalReview=true;row.createdAfterClosure=true;row.status="APPLIED";row.versionBefore=scope.version();row.versionAfter=scope.version();
            data.insert(row);added.add(row);
            scope.apply(p.sourcePlacementId(),id,p.sessionExerciseId(),row.id,initial.routineName(),p.exerciseName(),next,now,now);
        }
        if(session.version==Long.MAX_VALUE||scope.version()==Long.MAX_VALUE)throw fail(WRITE_CONFLICT);
        scope.complete(now);for(var row:added)row.versionAfter=scope.version();session.version++;session.updatedAt=now;data.flush();return view(session);
    });}
    private WorkoutSession find(UUID owner,long generation,UUID id,boolean lock){return sessions.find(owner,generation,id,lock).orElseThrow(()->fail(SESSION_NOT_FOUND));}
    private View view(WorkoutSession session){
        var frozen=session.completionWeightReview==null?null:JSON.readValue(session.completionWeightReview,Frozen.class);
        var versions=jdbc.query("SELECT version FROM routines WHERE id=? AND user_id=? AND data_generation=? AND deleted_at IS NULL",(r,i)->r.getLong(1),session.sourceRoutineId,session.userId,session.dataGeneration);
        var current=new ArrayList<Current>();
        if(frozen!=null)for(var item:frozen.exercises()){
            var p=InitialPlan.read(session.initialPlan).weightExercises().stream().filter(v->v.sessionExerciseId().equals(item.sessionExerciseId())).findFirst().orElseThrow();
            var weights=jdbc.query("SELECT approved_weight FROM routine_weight_placements WHERE id=? AND routine_id=? AND deleted_at IS NULL AND standard_exercise_id=? AND manufacturer_id IS NOT DISTINCT FROM ? AND load_type IS NOT DISTINCT FROM ? AND set_count=? AND min_reps=? AND max_reps=?",(r,i)->r.getBigDecimal(1),item.sourcePlacementId(),session.sourceRoutineId,item.standardExerciseId(),p.manufacturerId(),p.loadType(),p.setCount(),p.minReps(),p.maxReps());
            current.add(new Current(item.sessionExerciseId(),weights.isEmpty()?null:WorkoutSnapshotData.decimal(weights.getFirst()),!versions.isEmpty()&&!weights.isEmpty()&&!session.deload()&&session.deletedAt==null));
        }
        var approvals=jdbc.query("SELECT a.id,a.session_exercise_id,e.id,e.placement_id,e.previous_weight,e.next_weight,e.occurred_at,e.recorded_at,a.final_review,a.created_after_closure FROM session_weight_approval_requests a JOIN progress_change_events e ON e.approval_request_id=a.id AND e.session_id=a.session_id AND e.session_exercise_id=a.session_exercise_id WHERE a.session_id=? AND a.status='APPLIED' AND e.user_id=? AND e.data_generation=? ORDER BY e.recorded_at,e.id",(r,i)->new Applied(r.getObject(1,UUID.class),r.getObject(3,UUID.class),r.getObject(2,UUID.class),r.getObject(4,UUID.class),WorkoutSnapshotData.decimal(r.getBigDecimal(5)),WorkoutSnapshotData.decimal(r.getBigDecimal(6)),r.getTimestamp(7).toInstant(),r.getTimestamp(8).toInstant(),r.getBoolean(9)?"WORKOUT_REVIEW":r.getBoolean(10)?"CLOSED_PENDING_RESOLUTION":"WORKOUT"),session.id,session.userId,session.dataGeneration);
        return new View(session.userId,Long.toString(session.dataGeneration),session.id,Long.toString(session.version),frozen,versions.isEmpty()?null:Long.toString(versions.getFirst()),List.copyOf(current),approvals);
    }
    private static WorkoutSessionFailure fail(WorkoutSessionFailure.Reason reason){return new WorkoutSessionFailure(reason);}
}
