package com.overload.overload_server.workoutsession;

import com.overload.overload_server.routine.RoutineResponses;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Plan values live only in the immutable JSONB, not in a second relational plan model. */
public record InitialPlan(int schemaVersion, UUID routineId, String routineName, String routineVersion,
                          List<Weight> weightExercises, List<Cardio> cardioActivities) {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Weight(UUID sessionExerciseId, UUID sourcePlacementId, UUID standardExerciseId, String exerciseName, UUID manufacturerId, String loadType,
                         int setCount, int minReps, int maxReps, String initialWeightKg, String approvedWeightKg) { }
    public record Cardio(UUID sessionActivityId, UUID sourcePlacementId, RoutineResponses.Plan plan) { }

    static InitialPlan copy(RoutineResponses.Detail routine) {
        return new InitialPlan(2, routine.id(), routine.name(), routine.version(),
                routine.weightPlacements().stream().map(p -> new Weight(UUID.randomUUID(), p.id(), p.standardExerciseId(),
                        p.exerciseName(), p.manufacturerId(), p.loadType(), p.setCount(), p.minReps(), p.maxReps(), p.initialWeightKg(), p.approvedWeightKg())).toList(),
                routine.cardioPlacements().stream().map(p -> new Cardio(UUID.randomUUID(), p.id(), p.plan())).toList());
    }
    String json() { return JSON.writeValueAsString(this); }
    static InitialPlan read(String json) { return JSON.readValue(json, InitialPlan.class); }
}
