package com.overload.overload_server.routine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RoutineResponses {
	private RoutineResponses() { }
	public record Summary(UUID id, String name, String version) { }
	public record Listing(UUID userId, String dataGeneration, List<Summary> routines) { }
	public record Weight(UUID id, UUID standardExerciseId, String exerciseName, UUID manufacturerId, String loadType, int setCount, int minReps,
			int maxReps, String initialWeightKg, String approvedWeightKg, Instant deletedAt) { }
	public record Intensity(String mode, String value) { }
	public record Plan(String kind, String name, int targetSeconds, String targetDistanceKm, Intensity intensity,
			String inclinePercent, String resistance) { }
	public record Cardio(UUID id, Plan plan, Instant deletedAt) { }
	public record Detail(UUID userId, String dataGeneration, UUID id, String version, String name,
			List<Weight> weightPlacements, List<Cardio> cardioPlacements) { }
	static String decimal(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
	static Cardio cardio(RoutineCardioPlacement value) {
		return new Cardio(value.id, new Plan(value.kind, value.name, value.targetSeconds, decimal(value.targetDistance),
				value.intensityMode == null ? null : new Intensity(value.intensityMode, decimal(value.intensityValue)),
				decimal(value.incline), decimal(value.resistance)), value.deletedAt);
	}
}
