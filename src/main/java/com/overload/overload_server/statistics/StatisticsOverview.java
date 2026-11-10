package com.overload.overload_server.statistics;

import com.overload.overload_server.routine.RoutineResponses;
import java.time.LocalDate;
import java.util.*;

public record StatisticsOverview(StatisticsResponse statistics, String effectiveWorkoutDays, int unknownPlanDays,
                                 int unverifiableDays, List<PlannedDay> plannedDays,List<CardioTotal> cardioByKind,
                                 List<CardioCategoryTotal> cardioByCategory) {
    public record CardioCategoryTotal(String category, String activeMs, String distanceKm, String recordsWithoutDistance) { }
    public record CardioTotal(String kind,String activeMs,String distanceKm,String manualDistanceKm,String calculatedDistanceKm,String unknownSourceDistanceKm,
            String manualRecords,String calculatedRecords,String unknownSourceRecords,String recordsWithoutDistance) { }
    public record PlannedDay(LocalDate date,String status,String scheduleVersion,RoutineResponses.Detail plan,
                             Integer plannedExercises,Integer completedExercises,String effectiveDay,UUID selectedSessionId,String reviewStatus,String reviewRevision) {
        public PlannedDay(LocalDate date,String status,String version,RoutineResponses.Detail plan,Integer planned,Integer done,String effective,UUID session){this(date,status,version,plan,planned,done,effective,session,null,null);}
    }
}
