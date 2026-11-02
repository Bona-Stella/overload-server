package com.overload.overload_server.statistics;

import com.overload.overload_server.routine.RoutineResponses;
import java.time.LocalDate;
import java.util.*;

public record StatisticsOverview(StatisticsResponse statistics, String effectiveWorkoutDays, int unknownPlanDays,
                                 int unverifiableDays, List<PlannedDay> plannedDays) {
    public record PlannedDay(LocalDate date,String status,String scheduleVersion,RoutineResponses.Detail plan,
                             Integer plannedExercises,Integer completedExercises,String effectiveDay,UUID selectedSessionId) { }
}
