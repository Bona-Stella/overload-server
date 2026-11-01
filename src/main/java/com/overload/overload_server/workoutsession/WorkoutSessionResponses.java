package com.overload.overload_server.workoutsession;

import java.time.*;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class WorkoutSessionResponses {
    private WorkoutSessionResponses() { }
    public record Detail(UUID userId, String dataGeneration, UUID id, String status, String version,
                         Instant startedAt, LocalDate workoutDate, String startedTimeZone, UUID editorDeviceId,
                         String writerEpoch, String localRevision, Instant lastWorkoutSyncedAt, InitialPlan initialPlan,
                         Instant lastSnapshotSavedAt, WorkoutSnapshotData.View snapshot,
                         Instant endedAt, Instant closedAt, JsonNode completionAssessment, Instant deletedAt) { }
    public record Active(UUID userId, String dataGeneration, Detail session) { }
    public record ClosedSummary(UUID id, String routineName, String status, String version, LocalDate workoutDate,
                                Instant startedAt, Instant endedAt, Instant closedAt, Instant deletedAt) { }
    public record ClosedPage(UUID userId, String dataGeneration, java.util.List<ClosedSummary> sessions, ClosedCursor next) { }
    public record ClosedCursor(Instant beforeClosedAt, UUID beforeId) { }
    record Started(boolean created, Detail session) { }
    static Detail detail(WorkoutSession value,WorkoutSnapshotData.View snapshot) {
        return new Detail(value.userId, Long.toString(value.dataGeneration), value.id, value.status, Long.toString(value.version),
                value.startedAt, value.workoutDate, value.startedTimeZone, value.editorDeviceId, Long.toString(value.writerEpoch),
                Long.toString(value.localRevision), value.lastWorkoutSyncedAt, InitialPlan.read(value.initialPlan),value.lastSnapshotSavedAt,snapshot,
                value.endedAt,value.closedAt,value.completionAssessment==null?null:WorkoutSnapshotData.JSON.readTree(value.completionAssessment),value.deletedAt);
    }
}
