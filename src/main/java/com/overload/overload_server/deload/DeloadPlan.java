package com.overload.overload_server.deload;

import com.overload.overload_server.routine.*;
import java.time.*;
import java.util.*;

final class DeloadPlan {
    private DeloadPlan(){}
    enum Method { KEEP, THREE, TWO, REST }
    enum Split { ORIGINAL, PPL, FRONT_BACK, EQUAL, REST }
    record Muscle(UUID id,String group,String name){}
    record Classification(UUID id,String name,String jointType,List<Muscle> primary){}
    record Day(int weekday,RoutineResponses.Detail routine){}
    record Source(UUID userId,String dataGeneration,String timeZone,LocalDate today,
                  UUID presetId,String presetVersion,List<Day> days,List<Classification> classifications,
                  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                  String scheduleVersion){}
    record Choice(Method method,Split split,List<LocalDate> dates){}
    record Weight(int sourceWeekday,UUID sourceRoutineId,String sourceVersion,RoutineResponses.Weight original,String weightKg,int setCount,int minReps,int maxReps){}
    record Cardio(int sourceWeekday,UUID sourceRoutineId,String sourceVersion,RoutineResponses.Cardio original,RoutineResponses.Plan target){}
    record Plan(String label,LocalDate date,List<Weight> weights,List<Cardio> cardio){}
    record Issue(String code,UUID sourceId){}
    record Generated(List<Plan> plans,List<Issue> issues){boolean ready(){return issues.isEmpty();}}
    record Option(Method method,Split split,List<LocalDate> suggestedDates,boolean available,List<Issue> issues){}
}
