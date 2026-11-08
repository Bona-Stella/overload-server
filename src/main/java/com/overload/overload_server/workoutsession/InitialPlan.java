package com.overload.overload_server.workoutsession;

import com.overload.overload_server.routine.RoutineResponses;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

/** Plan values live only in the immutable JSONB, not in a second relational plan model. */
public record InitialPlan(int schemaVersion, UUID routineId, String routineName, String routineVersion,
                          List<Weight> weightExercises, List<Cardio> cardioActivities,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) tools.jackson.databind.JsonNode deloadOrigin) {
    public InitialPlan(int schemaVersion,UUID routineId,String routineName,String routineVersion,List<Weight> weights,List<Cardio> cardio){this(schemaVersion,routineId,routineName,routineVersion,weights,cardio,null);}
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
    static InitialPlan deload(com.overload.overload_server.deload.DeloadTemporaryPlans.Executable plan) {
        var c=plan.content();
        return new InitialPlan(2,null,c.label(),plan.version(),c.weights().stream().map(w->new Weight(UUID.randomUUID(),w.id(),w.standardExerciseId(),w.exerciseName(),w.manufacturerId(),w.loadType(),w.setCount(),w.minReps(),w.maxReps(),w.initialWeightKg(),null)).toList(),c.cardio().stream().map(v->new Cardio(UUID.randomUUID(),v.id(),v.plan())).toList(),JSON.valueToTree(plan));
    }
    String json() { return JSON.writeValueAsString(this); }
    static InitialPlan read(String json) { return JSON.readValue(json, InitialPlan.class); }
}
