package com.overload.overload_server.statistics;

import com.overload.overload_server.routine.*;
import com.overload.overload_server.workoutsession.InitialPlan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

@Repository
class PlanningRepository {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    PlanningRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    PlanningCalculator.Evidence read(UUID owner,long generation,StatisticsQuery query) {
        var schedules=jdbc.query("""
                WITH relevant AS (SELECT DISTINCT ON (effective_from) version,effective_from,time_zone,recorded_at,assignments
                FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? AND effective_from<?
                AND (effective_from>=? OR effective_from=(SELECT max(effective_from) FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? AND effective_from<?))
                ORDER BY effective_from,version DESC)
                SELECT version,effective_from,time_zone,recorded_at,assignments::text FROM relevant
                UNION
                SELECT version,effective_from,time_zone,recorded_at,assignments::text FROM weekly_schedule_versions
                WHERE user_id=? AND data_generation=? AND version=1
                ORDER BY effective_from,version
                """,(r,i)->new WeeklySchedule.Version(r.getString(1),r.getObject(2,LocalDate.class),r.getString(3),r.getTimestamp(4).toInstant(),List.of(JSON.readValue(r.getString(5),WeeklySchedule.Assignment[].class))),owner,generation,query.toDateExclusive(),query.fromDate(),owner,generation,query.fromDate(),owner,generation);
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
        var anchors=jdbc.query("SELECT id,workout_date,started_at,initial_plan_snapshot::text FROM workout_sessions WHERE user_id=? AND data_generation=? AND workout_date>=? AND workout_date<? ORDER BY started_at,id",
                (r,i)->new PlanningCalculator.Anchor(r.getObject(1,UUID.class),r.getObject(2,LocalDate.class),r.getTimestamp(3).toInstant(),JSON.readValue(r.getString(4),InitialPlan.class)),owner,generation,query.fromDate(),query.toDateExclusive());
        return new PlanningCalculator.Evidence(schedules,plans,anchors);
    }
}
