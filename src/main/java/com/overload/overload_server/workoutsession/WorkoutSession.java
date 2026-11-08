package com.overload.overload_server.workoutsession;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static com.overload.overload_server.workoutsession.WorkoutSessionFailure.Reason.*;

@Entity
@Table(name = "workout_sessions")
public class WorkoutSession {
    @Id UUID id;
    @Column(name = "user_id", nullable = false, updatable = false) UUID userId;
    @Column(name = "data_generation", nullable = false, updatable = false) long dataGeneration;
    @Column(name = "source_routine_id", updatable = false) UUID sourceRoutineId;
    @Column(name="schedule_version_at_start",updatable=false) Long scheduleVersionAtStart;
    @Column(nullable = false, columnDefinition = "text") String status;
    @Column(nullable = false) long version;
    @Column(name = "started_at", nullable = false, updatable = false) Instant startedAt;
    @Column(name = "workout_date", nullable = false, updatable = false) LocalDate workoutDate;
    @Column(name = "started_time_zone", nullable = false, updatable = false, columnDefinition = "text") String startedTimeZone;
    @Column(name = "started_device_id", nullable = false, updatable = false) UUID startedDeviceId;
    @Column(name = "editor_device_id", nullable = false) UUID editorDeviceId;
    @Column(name = "writer_epoch", nullable = false) long writerEpoch;
    @Column(name = "local_revision", nullable = false) long localRevision;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "initial_plan_snapshot", nullable = false, updatable = false, columnDefinition = "jsonb") String initialPlan;
    @Column(name = "last_workout_synced_at", nullable = false) Instant lastWorkoutSyncedAt;
    @Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="applied_plan_snapshot",nullable=false,columnDefinition="jsonb") String appliedPlan;
    @Column(name="last_snapshot_hash",columnDefinition="text") String lastSnapshotHash;
    @Column(name="last_snapshot_saved_at") Instant lastSnapshotSavedAt;
    @Column(name="ended_at") Instant endedAt;
    @Column(name="closed_at") Instant closedAt;
    @Column(name="deleted_at") Instant deletedAt;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="completion_assessment_snapshot",columnDefinition="jsonb") String completionAssessment;

    @Column(name="deload_period_id",updatable=false) UUID deloadPeriodId;
    @Column(name="deload_plan_id",updatable=false) UUID deloadPlanId;
    @Column(name="first_deload_activity_at",insertable=false,updatable=false) Instant firstDeloadActivityAt;
    @Column(name="abandoned_at") Instant abandonedAt;
    boolean deload(){return deloadPeriodId!=null;}
    protected WorkoutSession() { }

    boolean expireIfDue(Instant now) {
        if (!status.equals("ACTIVE") || now.isBefore(lastWorkoutSyncedAt.plus(Duration.ofHours(24)))) return false;
        if (version == Long.MAX_VALUE) throw new WorkoutSessionFailure(WRITE_CONFLICT);
        now=now.truncatedTo(ChronoUnit.MICROS);
        status = "AUTO_CLOSED"; closedAt = now; updatedAt = now; version++;
        return true;
    }

    static WorkoutSession start(UUID owner, WorkoutSessionRequests.Start input, InitialPlan plan, Instant now) {
        // Server metadata must survive PostgreSQL's microsecond round trip unchanged.
        now=now.truncatedTo(ChronoUnit.MICROS);
        var value = new WorkoutSession();
        value.id = input.id(); value.userId = owner; value.dataGeneration = input.dataGeneration();
        value.sourceRoutineId = input.routineId(); value.status = "ACTIVE";
        value.startedAt = input.startedAt(); value.workoutDate = input.workoutDate(); value.startedTimeZone = input.startedTimeZone();
        value.startedDeviceId = input.editorDeviceId(); value.editorDeviceId = input.editorDeviceId();
        value.initialPlan = plan.json(); value.lastWorkoutSyncedAt = now; value.createdAt = now; value.updatedAt = now;
        value.appliedPlan=WorkoutSnapshotData.json(WorkoutSnapshotData.initial(plan));
        return value;
    }

    boolean sameStart(WorkoutSessionRequests.Start input) {
        return !deload() && java.util.Objects.equals(sourceRoutineId,input.routineId()) && startedDeviceId.equals(input.editorDeviceId())
                && startedAt.equals(input.startedAt()) && workoutDate.equals(input.workoutDate()) && startedTimeZone.equals(input.startedTimeZone());
    }

    void takeover(WorkoutSessionRequests.Takeover input, Instant now) {
        if (deletedAt!=null) throw new WorkoutSessionFailure(SESSION_DELETED);
        if (!status.equals("ACTIVE")) throw new WorkoutSessionFailure(SESSION_NOT_ACTIVE);
        // Lost response retry confirms the immediately preceding takeover without moving counters or the deadline.
        if (input.expectedWriterEpoch() < Long.MAX_VALUE && writerEpoch == input.expectedWriterEpoch() + 1
                && editorDeviceId.equals(input.editorDeviceId())) return;
        if (writerEpoch != input.expectedWriterEpoch()) throw new WorkoutSessionFailure(EDITOR_OWNERSHIP_LOST);
        if (writerEpoch == Long.MAX_VALUE || version == Long.MAX_VALUE) throw new WorkoutSessionFailure(WRITE_CONFLICT);
        editorDeviceId = input.editorDeviceId(); writerEpoch++; localRevision = 0; version++; updatedAt = now;
        lastSnapshotHash=null; lastSnapshotSavedAt=null;
    }
}
