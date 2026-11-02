package com.overload.overload_server.workoutsession;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name = "session_exercises")
class SessionExercise {
    @Id UUID id;
    @Column(name = "session_id", updatable = false) UUID sessionId;
    @Column(name = "user_id", updatable = false) UUID userId;
    @Column(name = "data_generation", updatable = false) long dataGeneration;
    @Column(updatable = false, columnDefinition = "text") String kind;
    @Column(name = "standard_exercise_id", updatable = false) UUID standardExerciseId;
    @Column(name = "name_at_addition", updatable = false, columnDefinition = "text") String nameAtAddition;
    protected SessionExercise() {
    }
    SessionExercise(WorkoutSession session, WorkoutSnapshotData.Exercise input, String name) {
        id = input.id();
        sessionId = session.id;
        userId = session.userId;
        dataGeneration = session.dataGeneration;
        kind = input.kind();
        standardExerciseId = input.standardExerciseId();
        nameAtAddition = name;
    }
    WorkoutSnapshotData.Exercise value() {
        return new WorkoutSnapshotData.Exercise(id, kind, standardExerciseId);
    }
}
@Entity @Table(name = "session_weight_approval_requests")
class WeightApprovalRequest {
    @Id UUID id;
    @Column(name = "session_id", updatable = false) UUID sessionId;
    @Column(name = "session_exercise_id", updatable = false) UUID exerciseId;
    @Column(updatable = false, columnDefinition = "text") String kind = "WEIGHT";
    @Column(updatable = false) long sequence;
    @Column(name = "previous_request_id", updatable = false) UUID previousId;
    @Column(name = "expected_routine_version", updatable = false) long expectedVersion;
    @Column(name = "previous_weight", updatable = false, precision = 20, scale = 6) BigDecimal previousWeight;
    @Column(name = "next_weight", updatable = false, precision = 20, scale = 6) BigDecimal nextWeight;
    @Column(name = "occurred_at", updatable = false) Instant occurredAt;
    @Column(columnDefinition = "text") String status;
    @Column(columnDefinition = "text") String reason;
    @Column(name = "routine_version_before") Long versionBefore;
    @Column(name = "routine_version_after") Long versionAfter;
    @Column(name = "resolved_at") Instant resolvedAt;
    @Column(name = "resolved_by_request_id") UUID resolvedBy;
    @Column(name = "created_after_closure", updatable = false) boolean createdAfterClosure;
    protected WeightApprovalRequest() {
    }
    WeightApprovalRequest(UUID session, WorkoutSnapshotData.Approval input) {
        id = input.id();
        sessionId = session;
        exerciseId = input.sessionExerciseId();
        sequence = input.sequence();
        previousId = input.previousRequestId();
        expectedVersion = input.expectedRoutineVersion();
        previousWeight = new BigDecimal(input.previousApprovedWeightKg());
        nextWeight = new BigDecimal(input.nextApprovedWeightKg());
        occurredAt = input.occurredAt();
    }
    WorkoutSnapshotData.Approval value() {
        return new WorkoutSnapshotData.Approval(id, exerciseId, sequence, previousId, expectedVersion,
            WorkoutSnapshotData.decimal(previousWeight), WorkoutSnapshotData.decimal(nextWeight), occurredAt);
    }
    void pending(String code) {
        status = "PENDING";
        reason = code;
    }
}
@Entity @Table(name = "performed_sets")
class PerformedSet {
    @Id UUID id;
    @Column(name = "session_id", updatable = false) UUID sessionId;
    @Column(name = "session_exercise_id", updatable = false) UUID exerciseId;
    @Column(updatable = false, columnDefinition = "text") String kind = "WEIGHT";
    @Column(updatable = false) int ordinal;
    @Column(name = "manufacturer_id", updatable = false) UUID manufacturerId;
    @Column(name = "load_type", updatable = false, columnDefinition = "text") String loadType;
    @Column(precision = 20, scale = 6) BigDecimal weight;
    int reps;
    @Column(name = "completed_at", updatable = false) Instant completedAt;
    @Column(name = "context_kind", updatable = false, columnDefinition = "text") String contextKind;
    @Column(name = "approval_request_id", updatable = false) UUID approvalRequestId;
    @Column(name = "approval_state_at_recording", updatable = false, columnDefinition = "text") String approvalState;
    @Column(name = "approved_weight_at_recording", updatable = false, precision = 20, scale = 6) BigDecimal approvedWeight;
    @Column(name = "deleted_at") Instant deletedAt;
    protected PerformedSet() {
    }
    PerformedSet(UUID session, WorkoutSnapshotData.SetValue input) {
        id = input.id();
        sessionId = session;
        exerciseId = input.sessionExerciseId();
        ordinal = input.ordinal();
        manufacturerId = input.manufacturerId();
        loadType = input.loadType();
        completedAt = input.completedAt();
        contextKind = input.weightContext().kind();
        approvalRequestId = input.weightContext().requestId();
        apply(input);
    }
    void apply(WorkoutSnapshotData.SetValue input) {
        weight = new BigDecimal(input.weightKg());
        reps = input.reps();
    }
    WorkoutSnapshotData.SetValue value() {
        return new WorkoutSnapshotData.SetValue(id, exerciseId, ordinal, WorkoutSnapshotData.decimal(weight), reps,
            completedAt, new WorkoutSnapshotData.Context(contextKind, approvalRequestId), manufacturerId, loadType);
    }
}
@Entity @Table(name = "cardio_results")
class CardioResult {
    @Id UUID id;
    @Column(name = "session_id", updatable = false) UUID sessionId;
    @Column(name = "session_activity_id", updatable = false) UUID activityId;
    @Column(updatable = false, columnDefinition = "text") String kind = "CARDIO";
    @Column(updatable = false, columnDefinition = "text") String status;
    @Column(name = "started_at", updatable = false) Instant startedAt;
    @Column(name = "occurred_at", updatable = false) Instant occurredAt;
    @Column(name = "timer_ms", updatable = false) Long timerMs;
    @Column(name = "active_ms") Long activeMs;
    Boolean corrected;
    @Column(precision = 20, scale = 6) BigDecimal distance;
    @Column(name = "average_bpm") Integer bpm;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition="jsonb") String measurement;
    @Column(name = "deleted_at") Instant deletedAt;
    protected CardioResult() {
    }
    CardioResult(UUID session, WorkoutSnapshotData.Cardio input) {
        id = input.id();
        sessionId = session;
        activityId = input.sessionActivityId();
        status = input.status();
        startedAt = input.startedAt();
        occurredAt = input.occurredAt();
        timerMs = input.result()==null?null:input.result().timerMs();
        apply(input);
    }
    void apply(WorkoutSnapshotData.Cardio input) {
        var result = input.result();
        activeMs = result==null?null:result.activeMs();
        corrected = result==null?null:result.corrected();
        distance = result==null||result.distanceKm()==null?null:new BigDecimal(result.distanceKm());
        bpm = result==null?null:result.averageBpm();
        measurement=result==null||result.measurement()==null?null:WorkoutSnapshotData.JSON.writeValueAsString(result.measurement());
    }
    WorkoutSnapshotData.Cardio value() {
        return new WorkoutSnapshotData.Cardio(id, activityId, status, startedAt, occurredAt,
            status.equals("SKIPPED")?null:new WorkoutSnapshotData.CardioValue(timerMs, activeMs, corrected, WorkoutSnapshotData.decimal(distance), bpm,
                measurement==null?null:WorkoutSnapshotData.JSON.readValue(measurement,WorkoutSnapshotData.CardioMeasurement.class)));
    }
}
@Entity @Table(name = "record_change_events") @org.hibernate.annotations.Immutable
class RecordChangeEvent {
    @Id UUID id;
    @Column(name = "session_id", updatable = false) UUID sessionId;
    @Column(name = "record_kind", updatable = false, columnDefinition = "text") String recordKind;
    @Column(name = "record_id", updatable = false) UUID recordId;
    @Column(name = "change_kind", updatable = false, columnDefinition = "text") String changeKind;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "before_value", updatable = false, columnDefinition = "jsonb") String beforeValue;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "after_value", updatable = false, columnDefinition = "jsonb") String afterValue;
    @Column(name = "recorded_at", updatable = false) Instant recordedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="change_context",updatable=false,columnDefinition="jsonb") String changeContext;
    protected RecordChangeEvent() {
    }
    RecordChangeEvent(UUID session, String kind, UUID record, Object before, Object after, Instant now) {
        this(session,kind,record,after==null?"DELETED":"CORRECTED",before,after,null,now);
    }
    RecordChangeEvent(UUID session,String kind,UUID record,String change,Object before,Object after,Object context,Instant now) {
        id = UUID.randomUUID();
        sessionId = session;
        recordKind = kind;
        recordId = record;
        changeKind = change;
        beforeValue = before==null?null:WorkoutSnapshotData.json(before);
        afterValue = after==null?null:WorkoutSnapshotData.json(after);
        recordedAt = now;
        changeContext=context==null?null:WorkoutSnapshotData.json(context);
    }
}
