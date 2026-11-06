package com.overload.overload_server.routine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;

/** Called only after the routine aggregate has flushed in the existing workout transaction. */
@Repository
public class RoutinePlanHistory {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final WorkoutCalendarRepository calendars;
    RoutinePlanHistory(JdbcTemplate jdbc,WorkoutCalendarRepository calendars) { this.jdbc = jdbc;this.calendars=calendars; }
    public void record(RoutineResponses.Detail plan, Instant now, boolean deleted) {
        var existing=jdbc.query("SELECT recorded_at,routine_version FROM routine_plan_history WHERE routine_id=? ORDER BY routine_version DESC LIMIT 1",(r,i)->Map.entry(r.getTimestamp(1).toInstant(),r.getLong(2)),plan.id());
        if(existing.isEmpty()||Long.parseLong(plan.version())>existing.getFirst().getValue())calendars.requireOpenTime(plan.userId(),Long.parseLong(plan.dataGeneration()),now);
        if(!existing.isEmpty()&&Long.parseLong(plan.version())>existing.getFirst().getValue()&&now.isBefore(existing.getFirst().getKey()))throw new RoutineFailure(RoutineFailure.Reason.WRITE_CONFLICT);
        jdbc.update("""
                INSERT INTO routine_plan_history(user_id,data_generation,routine_id,routine_version,recorded_at,deleted,plan)
                VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT (routine_id,routine_version) DO NOTHING
                """, plan.userId(), Long.parseLong(plan.dataGeneration()), plan.id(), Long.parseLong(plan.version()),
                java.sql.Timestamp.from(now), deleted, JSON.writeValueAsString(plan));
    }
    /** Caller already owns the user row; guard both explicit saves and deletion-driven changes. */
    void requireEditableSchedule(UUID owner,long generation) {
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workout_sessions WHERE user_id=? AND data_generation=? AND status='ACTIVE')",Boolean.class,owner,generation)))
            throw new RoutineFailure(RoutineFailure.Reason.ACTIVE_SESSION_EXISTS);
    }
    void removeFromSchedule(UUID owner,long generation,UUID routine,Instant now) {
        // Caller already holds the owner FOR UPDATE before taking routine locks.
        record State(long version,String zone,Instant at,List<WeeklySchedule.Assignment> assignments) { }
        var rows=jdbc.query("SELECT version,time_zone,recorded_at,assignments::text FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? ORDER BY version DESC LIMIT 1",
                (r,i)->new State(r.getLong(1),r.getString(2),r.getTimestamp(3).toInstant(),List.of(JSON.readValue(r.getString(4),WeeklySchedule.Assignment[].class))),owner,generation);
        if(rows.isEmpty())return;var latest=rows.getFirst();
        var assignments=latest.assignments().stream().filter(a->!a.routineId().equals(routine)).toList();
        if(assignments.size()==latest.assignments().size())return;
        requireEditableSchedule(owner,generation);
        calendars.requireOpenTime(owner,generation,now);
        if(now.isBefore(latest.at()))throw new RoutineFailure(RoutineFailure.Reason.WRITE_CONFLICT);
        long version=latest.version();if(version==Long.MAX_VALUE)throw new RoutineFailure(RoutineFailure.Reason.WRITE_CONFLICT);
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,recorded_at,effective_from,time_zone,assignments) VALUES (?,?,?,?,?,?,?::jsonb)",
                owner,generation,version+1,java.sql.Timestamp.from(now),now.atZone(java.time.ZoneId.of(latest.zone())).toLocalDate(),latest.zone(),JSON.writeValueAsString(assignments));
    }
}
