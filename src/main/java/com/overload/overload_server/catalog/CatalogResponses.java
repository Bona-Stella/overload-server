package com.overload.overload_server.catalog;

import java.util.*;

public final class CatalogResponses {
    private CatalogResponses() { }
    public record SearchData(int schemaVersion, List<Exercise> exercises) { }
    public record MuscleItem(UUID id,String groupCode,String name,String role) { }
    public record Exercise(UUID id, String name, List<String> aliases,List<MuscleItem> muscles) { public Exercise(UUID id,String name,List<String> aliases){this(id,name,aliases,List.of());} }
    public record Catalog(List<CatalogItem> exercises) { }
    public record CatalogItem(UUID id, String name, List<String> aliases, List<MuscleItem> muscles,
                              StandardExercise.ExerciseType exerciseType, StandardExercise.RecordMethod recordMethod,
                              String instructionsKo, String equipmentDescription) { }
    public record Detail(UUID id, String name, List<String> aliases, StandardExercise.ExerciseType exerciseType,
                         StandardExercise.RecordMethod recordMethod, String equipmentDescription,
                         StandardExercise.JointType jointType, List<PrimaryMuscle> primaryMuscles,
                         List<SecondaryMuscle> secondaryMuscles, StandardExercise.PplSplit pplSplit,
                         StandardExercise.FrontBackSplit frontBackSplit, List<String> instructionSteps,
                         boolean selectableForRoutine) { }
    public record PrimaryMuscle(UUID id, String groupCode, String name, int primaryOrder) { }
    public record SecondaryMuscle(UUID id, String groupCode, String name) { }
    public record CardioChoices(List<StandardCardioCatalog.Choice> activities) { }
    public record Manufacturers(List<ManufacturerItem> manufacturers) { }
    public record ManufacturerItem(UUID id, String name) { }
}
