package com.overload.overload_server.workoutsession;

import com.fasterxml.jackson.annotation.JsonFormat;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class WorkoutSnapshotData {
    static final JsonMapper JSON = JsonMapper.builder().build();
    private WorkoutSnapshotData() {
    }

    public record Exercise(UUID id, String kind, UUID standardExerciseId) {
    }

    public record WeightPlan(UUID sessionExerciseId, int setCount, int minReps, int maxReps, UUID manufacturerId, String loadType) {
    }

    public record CardioPlan(UUID sessionActivityId, JsonNode plan) {
    }

    public record Plan(int schemaVersion, List<WeightPlan> weightExercises, List<CardioPlan> cardioActivities) {
    }

    public record Context(String kind, UUID requestId) {
    }

    public record SetValue(UUID id, UUID sessionExerciseId, int ordinal, String weightKg, int reps, Instant completedAt, Context weightContext, UUID manufacturerId, String loadType) {
    }

    public record CardioValue(Long timerMs, Long activeMs, Boolean corrected, String distanceKm, Integer averageBpm) {
    }

    public record Cardio(UUID id, UUID sessionActivityId, String status, Instant startedAt, Instant occurredAt, CardioValue result) {
    }

    public record Approval(UUID id, UUID sessionExerciseId, @JsonFormat(shape = JsonFormat.Shape.STRING) long sequence,
        UUID previousRequestId, @JsonFormat(shape = JsonFormat.Shape.STRING) long expectedRoutineVersion,
        String previousApprovedWeightKg, String nextApprovedWeightKg, Instant occurredAt) {
    }

    public record State(List<Exercise> sessionExercises, Plan appliedPlan, List<SetValue> performedSets, List<Cardio> cardioResults,
        List<UUID> deletedPerformedSetIds, List<UUID> deletedCardioResultIds, List<Approval> approvalRequests) {
    }

    public record Save(long dataGeneration, UUID editorDeviceId, long writerEpoch, long localRevision, State snapshot) {
    }
    public record Finish(Save state, Instant endedAt) { }
    public record FinishAck(UUID userId,String dataGeneration,UUID sessionId,String status,String writerEpoch,
            String acceptedLocalRevision,String version,String currentVersion,Instant deletedAt,Instant snapshotSavedAt,Instant lastWorkoutSyncedAt,
            Instant endedAt,Instant closedAt,boolean duplicate,List<ApprovalResult> approvalResults,
            WorkoutAssessment.Result completionAssessment) { }

    public record Resolve(long dataGeneration, UUID editorDeviceId, long writerEpoch, long expectedSessionVersion,
        long expectedLocalRevision, UUID sessionExerciseId, List<UUID> pendingRequestIds,
        String decision, Instant occurredAt, Approval newApprovalRequest) {
    }

    public record ApprovalResult(UUID id, UUID sessionExerciseId, String sequence, String status, String reason,
        UUID approvalEventId, String routineVersionBefore, String routineVersionAfter,
        Instant resolvedAt, UUID resolvedByRequestId) {
    }

    public record RecordedContext(UUID setId, String approvalStateAtRecording, String approvedWeightKgAtRecording) {
    }

    public record View(State state, List<ApprovalResult> approvalResults, List<RecordedContext> recordedContexts) {
    }

    public record Ack(UUID userId, String dataGeneration, UUID sessionId, String writerEpoch, String acceptedLocalRevision,
        String version, Instant snapshotSavedAt, Instant lastWorkoutSyncedAt, boolean duplicate,
        List<ApprovalResult> approvalResults) {
    }

    public record Resolution(UUID userId, String dataGeneration, UUID sessionId, String writerEpoch, String localRevision,
        String version, Instant lastWorkoutSyncedAt, List<ApprovalResult> approvalResults) {
    }

    static String json(Object value) {
        return JSON.writeValueAsString(value);
    }

    static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    static Plan initial(InitialPlan plan) {
        return new Plan(2, plan.weightExercises().stream().map(p -> new WeightPlan(p.sessionExerciseId(), p.setCount(), p.minReps(), p.maxReps(), p.manufacturerId(), p.loadType())).toList(),
            plan.cardioActivities().stream().map(p -> new CardioPlan(p.sessionActivityId(), JSON.valueToTree(p.plan()))).toList());
    }
}
