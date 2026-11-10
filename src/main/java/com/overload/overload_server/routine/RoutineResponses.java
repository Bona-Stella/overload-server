package com.overload.overload_server.routine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RoutineResponses {
	private RoutineResponses() { }
	public record Summary(UUID id, String name, String version, String tag, Instant createdAt) { public Summary(UUID id,String name,String version){this(id,name,version,null,null);} }
	public record Listing(UUID userId, String dataGeneration, List<Summary> routines) { }
	public record Weight(UUID id, UUID standardExerciseId, String exerciseName, UUID manufacturerId, String loadType, int setCount, int minReps,
			int maxReps, String initialWeightKg, String approvedWeightKg, Instant deletedAt) { }
	public record Intensity(String mode, String value) { }
	public record Plan(String kind, String name, int targetSeconds, String targetDistanceKm, Intensity intensity,
			String inclinePercent, String resistance, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID activityId,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) UUID standardExerciseId) {
        public Plan(String k,String n,int t,String d,Intensity i,String incline,String resistance){this(k,n,t,d,i,incline,resistance,null,null);}
        public Plan(String k,String n,int t,String d,Intensity i,String incline,String resistance,UUID a){this(k,n,t,d,i,incline,resistance,a,null);}
        public boolean sameCardioIdentity(Plan previous) {
            return java.util.Objects.equals(standardExerciseId, previous.standardExerciseId)
                    && java.util.Objects.equals(activityId, previous.activityId)
                    && (activityId == null || (kind.equals(previous.kind) && name.equals(previous.name)));
        }
    }
	public record Cardio(UUID id, Plan plan, Instant deletedAt) { }
	public record Detail(UUID userId, String dataGeneration, UUID id, String version, String name,
			List<Weight> weightPlacements, List<Cardio> cardioPlacements, String tag, Instant createdAt) { public Detail(UUID userId,String generation,UUID id,String version,String name,List<Weight> weights,List<Cardio> cardio){this(userId,generation,id,version,name,weights,cardio,null,null);} }
	static String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
	static Cardio cardio(RoutineCardioPlacement value) {
		return new Cardio(value.id, new Plan(value.kind, value.name, value.targetSeconds, decimal(value.targetDistance),
				value.intensityMode == null ? null : new Intensity(value.intensityMode, decimal(value.intensityValue)),
				decimal(value.incline), decimal(value.resistance),value.activityId,value.standardExerciseId), value.deletedAt);
	}
}
