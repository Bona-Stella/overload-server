package com.overload.overload_server.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ExerciseMuscleService {
    private final StandardExerciseRepository exercises;
    private final ExerciseMuscleRepository links;

    ExerciseMuscleService(StandardExerciseRepository exercises, ExerciseMuscleRepository links) {
        this.exercises = exercises;
        this.links = links;
    }

    @Transactional("workoutTransactionManager")
    public void replace(UUID exerciseId, List<UUID> primary, List<UUID> secondary) {
        var replacement = new ArrayList<ExerciseMuscle>();
        var seen = new HashSet<UUID>();
        for (int index = 0; index < primary.size(); index++) {
            var muscleId = primary.get(index);
            requireUniqueMuscle(seen, muscleId);
            replacement.add(ExerciseMuscle.create(exerciseId, muscleId, ExerciseMuscle.Role.PRIMARY, index + 1));
        }
        for (var muscleId : secondary) {
            requireUniqueMuscle(seen, muscleId);
            replacement.add(ExerciseMuscle.create(exerciseId, muscleId, ExerciseMuscle.Role.SECONDARY, null));
        }

        exercises.findForUpdate(exerciseId).orElseThrow(() -> new IllegalArgumentException("Unknown standard exercise"));
        links.deleteAll(links.findAllByStandardExerciseId(exerciseId));
        links.flush();
        links.saveAllAndFlush(replacement);
    }

    private static void requireUniqueMuscle(Set<UUID> seen, UUID muscleId) {
        if (!seen.add(Objects.requireNonNull(muscleId))) {
            throw new IllegalArgumentException("A muscle can appear only once per exercise");
        }
    }
}
