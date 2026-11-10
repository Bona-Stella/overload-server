package com.overload.overload_server.statistics;

import java.time.*;
import java.util.*;

public record StatisticsResponse(UUID userId, String dataGeneration, LocalDate fromDate, LocalDate toDateExclusive,
        String planCoverage, Integer plannedWorkoutDays, String routineAchievementPercent,
        Weight summary, String cardioActiveMs, String unmappedCompletedSets, List<Day> days,
        List<Exercise> exercises, List<Muscle> muscles, List<Group> groups, List<Cardio> cardio,
        List<Recent> recentSessions, List<GroupActual> groupActuals) {
    StatisticsResponse withPlanning(String coverage,Integer plannedDays,String rate) {
        return new StatisticsResponse(userId,dataGeneration,fromDate,toDateExclusive,coverage,plannedDays,rate,summary,cardioActiveMs,
                unmappedCompletedSets,days,exercises,muscles,groups,cardio,recentSessions,groupActuals);
    }
    public record Weight(String plannedSets, String completedSets, String reps, String volumeLoadKg, String setsWithoutVolume) { }
    public record Day(LocalDate date, Weight weights, String cardioActiveMs) { }
    public record Exercise(UUID standardExerciseId, String name, Weight weights) { }
    public record Muscle(UUID id, String name, String groupCode, String role, Weight weights, String contributionSets) { }
    public record GroupActual(String groupCode, String completedSets) { }
    public record Group(String groupCode, String role, Weight weights) { }
    public record Cardio(String kind, String name, String activeMs, String distanceKm, String recordsWithoutDistance) { }
    public record Recent(UUID id, String routineName, LocalDate workoutDate, Instant startedAt, String status) { }
}
