package com.overload.overload_server.statistics;

import com.overload.overload_server.routine.*;
import com.overload.overload_server.workoutsession.InitialPlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

@Repository
class PlanningRepository {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final WorkoutCalendarRepository calendars;
    PlanningRepository(JdbcTemplate jdbc,WorkoutCalendarRepository calendars){this.jdbc=jdbc;this.calendars=calendars;}
    PlanningCalculator.Evidence read(UUID owner,long generation,StatisticsQuery query) {
        var calendar=calendars.control(owner,generation);long boundary=calendar.map(WorkoutCalendarRepository.Control::baselineVersion).orElse(Long.MAX_VALUE);
        String calendarZone=calendar.map(WorkoutCalendarRepository.Control::timeZone).orElse("UTC");
        var named=new NamedParameterJdbcTemplate(jdbc);var params=new HashMap<String,Object>();
        params.put("owner",owner);params.put("generation",generation);params.put("boundary",boundary);params.put("zone",calendarZone);
        params.put("from",query.fromDate());params.put("to",query.toDateExclusive());
        params.put("fromInstant",java.sql.Timestamp.from(query.fromDate().atStartOfDay(ZoneId.of(calendarZone)).toInstant()));
        params.put("toInstant",java.sql.Timestamp.from(query.toDateExclusive().atStartOfDay(ZoneId.of(calendarZone)).toInstant()));
        var schedules=named.query("""
            WITH scoped AS NOT MATERIALIZED (SELECT * FROM weekly_schedule_versions WHERE user_id=:owner AND data_generation=:generation),
            legacy AS (SELECT DISTINCT ON(effective_from) * FROM scoped WHERE version<:boundary AND effective_from<:to
                AND(effective_from>=:from OR effective_from=(SELECT max(effective_from) FROM scoped WHERE version<:boundary AND effective_from<:from)) ORDER BY effective_from,version DESC),
            modern AS (SELECT DISTINCT ON((recorded_at AT TIME ZONE time_zone)::date) * FROM scoped WHERE version>=:boundary AND recorded_at<:toInstant
                AND(recorded_at>=:fromInstant OR recorded_at=(SELECT max(recorded_at) FROM scoped WHERE version>=:boundary AND recorded_at<:fromInstant))
                ORDER BY (recorded_at AT TIME ZONE time_zone)::date,recorded_at DESC,version DESC),
            origins AS (SELECT schedule_version_at_start AS version FROM workout_sessions WHERE deload_period_id IS NULL AND user_id=:owner AND data_generation=:generation AND workout_date>=:from AND workout_date<:to
                UNION SELECT missed_schedule_version FROM daily_training_assessments WHERE user_id=:owner AND data_generation=:generation AND workout_date>=:from AND workout_date<:to),
            relevant AS (SELECT * FROM legacy UNION SELECT * FROM modern UNION SELECT * FROM scoped WHERE version=1 OR version=:boundary OR version IN(SELECT version FROM origins))
            SELECT version,effective_from,time_zone,recorded_at,assignments::text FROM relevant ORDER BY recorded_at,version
            """,params,(r,i)->new WeeklySchedule.Version(r.getString(1),r.getObject(2,LocalDate.class),r.getString(3),r.getTimestamp(4).toInstant(),List.of(JSON.readValue(r.getString(5),WeeklySchedule.Assignment[].class))));
        var ids=schedules.stream().flatMap(s->s.assignments().stream()).map(WeeklySchedule.Assignment::routineId).distinct().toList();
        List<PlanningCalculator.Revision> plans=List.of();
        if(!ids.isEmpty()) {
            String zone=schedules.getFirst().timeZone();var args=new ArrayList<Object>();args.add(zone);args.add(owner);args.add(generation);args.addAll(ids);args.add(query.toDateExclusive());args.add(query.fromDate());args.add(query.fromDate());
            String sql="""
                    WITH source AS (
                      SELECT
                        routine_id,routine_version,recorded_at,deleted,plan,(recorded_at AT TIME ZONE ?)::date AS local_day
                      FROM routine_plan_history WHERE user_id=? AND data_generation=? AND routine_id IN (
                    """+String.join(",",Collections.nCopies(ids.size(),"?"))+"""
                      )
                    ), daily AS (
                      SELECT DISTINCT ON (routine_id,local_day) * FROM source WHERE local_day<?
                      ORDER BY routine_id,local_day,recorded_at DESC,routine_version DESC
                    ) SELECT recorded_at,deleted,plan::text FROM daily d WHERE local_day>=?
                      OR local_day=(SELECT max(local_day) FROM daily p WHERE p.routine_id=d.routine_id AND p.local_day<?)
                    ORDER BY recorded_at,routine_version
                    """;
            plans=jdbc.query(sql,(r,i)->new PlanningCalculator.Revision(r.getTimestamp(1).toInstant(),r.getBoolean(2),JSON.readValue(r.getString(3),RoutineResponses.Detail.class)),args.toArray());
        }
        // Deleted/ACTIVE sessions still preserve immutable evidence of the plan at first start.
        var activeDates=new HashSet<LocalDate>();
        var anchors=jdbc.query("SELECT id,workout_date,started_at,initial_plan_snapshot::text,schedule_version_at_start,status FROM workout_sessions WHERE deload_period_id IS NULL AND user_id=? AND data_generation=? AND workout_date>=? AND workout_date<? ORDER BY started_at,id",
                (r,i)->{var day=r.getObject(2,LocalDate.class);if(r.getString(6).equals("ACTIVE"))activeDates.add(day);return new PlanningCalculator.Anchor(r.getObject(1,UUID.class),day,r.getTimestamp(3).toInstant(),JSON.readValue(r.getString(4),InitialPlan.class),r.getObject(5,Long.class));},owner,generation,query.fromDate(),query.toDateExclusive());
        var daily=new HashMap<LocalDate,PlanningCalculator.Daily>();
        jdbc.query("SELECT * FROM daily_training_assessments WHERE user_id=? AND data_generation=? AND workout_date>=? AND workout_date<?",r->{
            var missed=r.getTimestamp("first_missed_at");daily.put(r.getObject("workout_date",LocalDate.class),new PlanningCalculator.Daily(r.getObject("first_session_id",UUID.class),missed==null?null:missed.toInstant(),r.getString("missed_schedule_version"),r.getObject("missed_routine_id",UUID.class),r.getString("missed_plan_version"),r.getString("state"),r.getLong("revision"),r.getBoolean("needs_recheck")));
        },owner,generation,query.fromDate(),query.toDateExclusive());
        var frozen=named.query("""
            SELECT DISTINCT p.recorded_at,p.deleted,p.plan::text FROM routine_plan_history p JOIN daily_training_assessments d
            ON p.user_id=d.user_id AND p.data_generation=d.data_generation AND p.routine_id=d.missed_routine_id AND p.routine_version=d.missed_plan_version
            WHERE d.user_id=:owner AND d.data_generation=:generation AND d.workout_date>=:from AND d.workout_date<:to
            """,params,(r,i)->new PlanningCalculator.Revision(r.getTimestamp(1).toInstant(),r.getBoolean(2),JSON.readValue(r.getString(3),RoutineResponses.Detail.class)));
        if(!frozen.isEmpty()){plans=new ArrayList<>(plans);plans.addAll(frozen);}
        return new PlanningCalculator.Evidence(schedules,plans,anchors,calendar.isEmpty()?null:boundary,calendar.map(WorkoutCalendarRepository.Control::boundary).orElse(null),daily,activeDates);
    }
}
