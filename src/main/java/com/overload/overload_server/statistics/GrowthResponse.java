package com.overload.overload_server.statistics;

import java.time.*;
import java.util.*;

public final class GrowthResponse {
    public record Exercise(UUID standardExerciseId,String name,Instant lastPerformedAt,Instant lastApprovalAt) { }
    public record Placement(UUID routineId,UUID placementId,String routineName,boolean deleted,Instant lastApprovalAt) { }
    public record Point(UUID id,String previousWeightKg,String approvedWeightKg,Instant occurredAt,Instant recordedAt,String source,UUID sessionId,boolean connectFromPrevious,UUID previousPointId) { }
    public record Search(UUID userId,String dataGeneration,String period,GrowthQuery.Range range,String query,int offset,List<Exercise> exercises,Integer nextOffset) { }
    public record Options(UUID userId,String dataGeneration,String period,GrowthQuery.Range range,UUID standardExerciseId,String name,List<Placement> placements) { }
    public record History(UUID userId,String dataGeneration,String period,GrowthQuery.Range range,UUID standardExerciseId,Placement placement,int offset,List<Point> points,Integer nextOffset) { }
}
