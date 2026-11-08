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
}
