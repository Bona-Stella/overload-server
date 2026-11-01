package com.overload.overload_server.routine;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "routine_weight_placements")
public class RoutineWeightPlacement {
	@Id UUID id;
	@Column(name = "user_id", nullable = false, updatable = false) UUID userId;
	@Column(name = "data_generation", nullable = false, updatable = false) long dataGeneration;
	@Column(name = "routine_id", nullable = false, updatable = false) UUID routineId;
	@Column(name = "standard_exercise_id", nullable = false, updatable = false) UUID standardExerciseId;
	@Column(name = "manufacturer_id") UUID manufacturerId;
	@Column(name = "load_type", columnDefinition = "text") String loadType;
	@Column(nullable = false) int position;
	@Column(name = "set_count", nullable = false) int setCount;
	@Column(name = "min_reps", nullable = false) int minReps;
	@Column(name = "max_reps", nullable = false) int maxReps;
	@Column(name = "initial_weight", nullable = false, updatable = false, precision = 20, scale = 6) BigDecimal initialWeight;
	@Column(name = "approved_weight", nullable = false, precision = 20, scale = 6) BigDecimal approvedWeight;
	@Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
	@Column(name = "updated_at", nullable = false) Instant updatedAt;
	@Column(name = "deleted_at") Instant deletedAt;
	protected RoutineWeightPlacement() { }

	static RoutineWeightPlacement create(Routine routine, RoutineRequests.Weight input, int position, Instant now) {
		var value = new RoutineWeightPlacement();
		value.id = input.id(); value.userId = routine.userId; value.dataGeneration = routine.dataGeneration;
		value.routineId = routine.id; value.standardExerciseId = input.standardExerciseId();
		value.initialWeight = input.initialWeightKg(); value.approvedWeight = input.initialWeightKg();
		value.createdAt = now; value.apply(input, position, now);
		return value;
	}
	boolean differs(RoutineRequests.Weight input, int order) {
		return deletedAt != null || position != order || setCount != input.setCount()
				|| minReps != input.minReps() || maxReps != input.maxReps()
				|| !java.util.Objects.equals(manufacturerId, input.manufacturerId()) || !java.util.Objects.equals(loadType, input.loadType());
	}
	void apply(RoutineRequests.Weight input, int order, Instant now) {
		position = order; setCount = input.setCount(); minReps = input.minReps(); maxReps = input.maxReps();
		manufacturerId = input.manufacturerId(); loadType = input.loadType();
		deletedAt = null; updatedAt = now;
	}
}
