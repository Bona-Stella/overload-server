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
    private final StandardCardioCatalog cardio;

    CatalogReadService(StandardExerciseRepository exercises, ExerciseAliasRepository aliases, ManufacturerRepository manufacturers,
                       StandardCardioCatalog cardio) {
        this.exercises = exercises; this.aliases = aliases; this.manufacturers = manufacturers; this.cardio = cardio;
    }

    public CatalogResponses.SearchData searchData() {
        var selectable = exercises.findAllByActiveTrueOrderByIdAsc().stream()
                .filter(StandardExercise::isSelectableForSetsReps).toList();
        var byExercise = aliasesByExercise();
        var muscles = musclesByExercise();
        return new CatalogResponses.SearchData(1, selectable.stream().map(exercise -> new CatalogResponses.Exercise(
                exercise.getId(), exercise.getName(), byExercise.getOrDefault(exercise.getId(), List.of()),
                muscles.getOrDefault(exercise.getId(), List.of()))).toList());
    }

    public CatalogResponses.Catalog catalog() {
        var byExercise = aliasesByExercise();
        var muscles = musclesByExercise();
        return new CatalogResponses.Catalog(exercises.findAllByActiveTrueOrderByIdAsc().stream()
                .filter(StandardExercise::isPublished)
                .map(e -> new CatalogResponses.CatalogItem(e.getId(), e.getName(), byExercise.getOrDefault(e.getId(), List.of()),
                        muscles.getOrDefault(e.getId(), List.of()), e.getExerciseType(), e.getRecordMethod(),
                        e.getInstructionsKo(), e.getEquipmentDescription()))
                .toList());
    }

    public CatalogResponses.CardioChoices cardioChoices() {
        var byExercise = aliasesByExercise();
        return new CatalogResponses.CardioChoices(cardio.choices().stream().map(c -> new StandardCardioCatalog.Choice(
                c.id(), c.activityId(), c.category(), c.name(), c.kind(), byExercise.getOrDefault(c.id(), List.of()))).toList());
    }

    public Optional<CatalogResponses.Detail> detail(UUID exerciseId) {
        return exercises.findById(exerciseId).filter(StandardExercise::isPublished).map(exercise -> {
            var exerciseAliases = aliases.findAllByStandardExerciseIdOrderByNormalizedAlias(exerciseId)
                    .stream().map(ExerciseAlias::getAlias).toList();
            var primary = new ArrayList<CatalogResponses.PrimaryMuscle>();
            var secondary = new ArrayList<CatalogResponses.SecondaryMuscle>();
            for (var row : exercises.muscleRows(exerciseId)) {
                if ("PRIMARY".equals(row.getRole())) {
                    primary.add(new CatalogResponses.PrimaryMuscle(row.getMuscleId(), row.getGroupCode(),
                            row.getMuscleName(), row.getPrimaryOrder()));
                } else {
                    secondary.add(new CatalogResponses.SecondaryMuscle(row.getMuscleId(), row.getGroupCode(), row.getMuscleName()));
                }
            }
            String instructions = exercise.getInstructionsKo();
            List<String> steps = instructions == null ? List.of()
                    : instructions.lines().map(String::strip).filter(step -> !step.isEmpty()).toList();
            boolean selectable = exercise.isSelectableForSetsReps()
                    || exercise.getRecordMethod() == StandardExercise.RecordMethod.CARDIO && cardio.selectable(exerciseId);
            return new CatalogResponses.Detail(exerciseId, exercise.getName(), exerciseAliases, exercise.getExerciseType(),
                    exercise.getRecordMethod(), exercise.getEquipmentDescription(), exercise.getJointType(),
                    List.copyOf(primary), List.copyOf(secondary), exercise.getPplSplit(), exercise.getFrontBackSplit(), steps, selectable);
        });
    }

    private Map<UUID, List<CatalogResponses.MuscleItem>> musclesByExercise() {
        var muscles=new HashMap<UUID,List<CatalogResponses.MuscleItem>>();
        for(var row:exercises.muscleRows())muscles.computeIfAbsent(row.getExerciseId(),ignored->new ArrayList<>()).add(new CatalogResponses.MuscleItem(row.getMuscleId(),row.getGroupCode(),row.getMuscleName(),row.getRole()));
        return muscles;
    }

    private Map<UUID, List<String>> aliasesByExercise() {
        var byExercise = new HashMap<UUID, List<String>>();
        for (var alias : aliases.findAllForActiveExercises()) {
            byExercise.computeIfAbsent(alias.getStandardExerciseId(), ignored -> new ArrayList<>()).add(alias.getAlias());
        }
        return byExercise;
    }

    public CatalogResponses.Manufacturers manufacturers() {
        return new CatalogResponses.Manufacturers(manufacturers.findAllByActiveTrueOrderByIdAsc().stream()
                .map(value -> new CatalogResponses.ManufacturerItem(value.getId(), value.getName())).toList());
    }
}
