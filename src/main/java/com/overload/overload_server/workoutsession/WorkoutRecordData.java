package com.overload.overload_server.workoutsession;

import java.time.Instant;
import java.util.*;
import tools.jackson.databind.JsonNode;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;

public final class WorkoutRecordData {
    private WorkoutRecordData() { }
    public record Scope(long dataGeneration,long expectedVersion) { }
    public record SetCorrection(UUID id,String weightKg,int reps) { }
    public record CardioCorrection(UUID id,long activeMs,boolean corrected,String distanceKm,Integer averageBpm,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) CardioMeasurement measurement) {
        public CardioCorrection(UUID id,long activeMs,boolean corrected,String distanceKm,Integer averageBpm){this(id,activeMs,corrected,distanceKm,averageBpm,null);}
    }
    public record Edit(Scope scope,List<SetCorrection> setCorrections,List<CardioCorrection> cardioCorrections,
            List<UUID> deletedPerformedSetIds,List<UUID> deletedCardioResultIds) { }
    public record Origin(UUID editorDeviceId,String writerEpoch,String localRevision) { }
    public record Recovery(Scope scope,Origin origin,List<SetValue> performedSets,List<Cardio> cardioResults) { }
    public record ClosedResolve(Scope scope,UUID sessionExerciseId,List<UUID> pendingRequestIds,String decision,
            Instant occurredAt,Approval newApprovalRequest) { }
    public record Ack(UUID userId,String dataGeneration,UUID sessionId,String status,String version,boolean changed,
            List<UUID> changeEventIds,List<UUID> recoveredIds,List<UUID> alreadyPresentIds,Instant deletedAt) { }
    public record ResolutionAck(UUID userId,String dataGeneration,UUID sessionId,String status,String version,
            boolean changed,List<ApprovalResult> approvalResults) { }
    public record CurrentAssessment(String state,List<String> reasonCodes,String advice,WorkoutAssessment.Result evaluation) { }
    public record Assessment(UUID userId,String dataGeneration,UUID sessionId,String status,String version,
            JsonNode completionAssessment,CurrentAssessment currentAssessment) { }
    public record Change(UUID id,String recordKind,UUID recordId,String changeKind,JsonNode before,JsonNode after,
            JsonNode changeContext,Instant recordedAt) { }
    public record Changes(UUID userId,String dataGeneration,UUID sessionId,String version,List<Change> events) { }
}
