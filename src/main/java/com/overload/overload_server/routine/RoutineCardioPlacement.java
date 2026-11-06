package com.overload.overload_server.routine;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "routine_cardio_placements")
public class RoutineCardioPlacement {
	@Id UUID id;
	@Column(name = "user_id", nullable = false, updatable = false) UUID userId;
	@Column(name = "data_generation", nullable = false, updatable = false) long dataGeneration;
	@Column(name = "routine_id", nullable = false, updatable = false) UUID routineId;
	@Column(nullable = false) int position;
	@Column(name="activity_id") UUID activityId;
	@Column(nullable = false, columnDefinition = "text") String kind;
	@Column(nullable = false, columnDefinition = "text") String name;
	@Column(name = "target_seconds", nullable = false) int targetSeconds;
	@Column(name = "target_distance", precision = 20, scale = 6) BigDecimal targetDistance;
	@Column(name = "intensity_mode", columnDefinition = "text") String intensityMode;
	@Column(name = "intensity_value", precision = 20, scale = 6) BigDecimal intensityValue;
	@Column(precision = 20, scale = 6) BigDecimal incline;
	@Column(precision = 20, scale = 6) BigDecimal resistance;
	@Column(name = "created_at", nullable = false, updatable = false) Instant createdAt;
	@Column(name = "updated_at", nullable = false) Instant updatedAt;
	@Column(name = "deleted_at") Instant deletedAt;
	protected RoutineCardioPlacement() { }

	static RoutineCardioPlacement create(Routine routine, RoutineRequests.Cardio input, int order, Instant now) {
		var value = new RoutineCardioPlacement();
		value.id = input.id(); value.userId = routine.userId; value.dataGeneration = routine.dataGeneration;
		value.routineId = routine.id; value.createdAt = now; value.apply(input.plan(), order, now);
		return value;
	}
	RoutineRequests.Plan plan() {
		return new RoutineRequests.Plan(kind, name, targetSeconds, normalize(targetDistance), intensityMode,
				normalize(intensityValue), normalize(incline), normalize(resistance),activityId);
	}
	private static BigDecimal normalize(BigDecimal value) { return value == null ? null : value.stripTrailingZeros(); }
	boolean differs(RoutineRequests.Cardio input, int order) {
		return deletedAt != null || position != order || !plan().equals(input.plan());
	}
	void apply(RoutineRequests.Plan plan, int order, Instant now) {
		activityId=plan.activityId(); position = order; kind = plan.kind(); name = plan.name(); targetSeconds = plan.targetSeconds();
		targetDistance = plan.targetDistanceKm(); intensityMode = plan.intensityMode(); intensityValue = plan.intensityValue();
		incline = plan.inclinePercent(); resistance = plan.resistance(); deletedAt = null; updatedAt = now;
	}
}
