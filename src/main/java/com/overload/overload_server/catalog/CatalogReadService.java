package com.overload.overload_server.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Transactional(transactionManager = "workoutTransactionManager", readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class CatalogReadService {
    private final StandardExerciseRepository exercises;
    private final ExerciseAliasRepository aliases;
    private final ManufacturerRepository manufacturers;

    CatalogReadService(StandardExerciseRepository exercises, ExerciseAliasRepository aliases, ManufacturerRepository manufacturers) {
        this.exercises = exercises; this.aliases = aliases; this.manufacturers = manufacturers;
    }

    public CatalogResponses.SearchData searchData() {
        var active = exercises.findAllByActiveTrueOrderByIdAsc();
        var byExercise = new HashMap<UUID, List<String>>();
        var muscles=new HashMap<UUID,List<CatalogResponses.MuscleItem>>();
        for(var row:exercises.muscleRows())muscles.computeIfAbsent(row.getExerciseId(),ignored->new ArrayList<>()).add(new CatalogResponses.MuscleItem(row.getMuscleId(),row.getGroupCode(),row.getMuscleName(),row.getRole()));
        for (var alias : aliases.findAllForActiveExercises()) {
            byExercise.computeIfAbsent(alias.getStandardExerciseId(), ignored -> new ArrayList<>()).add(alias.getAlias());
        }
        return new CatalogResponses.SearchData(1, active.stream().map(exercise -> new CatalogResponses.Exercise(
                exercise.getId(), exercise.getName(), List.copyOf(byExercise.getOrDefault(exercise.getId(), List.of())),List.copyOf(muscles.getOrDefault(exercise.getId(),List.of())))).toList());
    }

    public CatalogResponses.Manufacturers manufacturers() {
        return new CatalogResponses.Manufacturers(manufacturers.findAllByActiveTrueOrderByIdAsc().stream()
                .map(value -> new CatalogResponses.ManufacturerItem(value.getId(), value.getName())).toList());
    }
}
