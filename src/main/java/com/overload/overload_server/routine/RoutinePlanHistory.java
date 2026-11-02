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
    RoutinePlanHistory(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void record(RoutineResponses.Detail plan, Instant now, boolean deleted) {
        jdbc.update("""
                INSERT INTO routine_plan_history(user_id,data_generation,routine_id,routine_version,recorded_at,deleted,plan)
                VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT (routine_id,routine_version) DO NOTHING
                """, plan.userId(), Long.parseLong(plan.dataGeneration()), plan.id(), Long.parseLong(plan.version()),
                java.sql.Timestamp.from(now), deleted, JSON.writeValueAsString(plan));
    }
    void removeFromNextSchedule(UUID owner,long generation,UUID routine,Instant now) {
        // Caller already holds the owner FOR UPDATE before taking routine locks.
        record State(long version,String zone,List<WeeklySchedule.Assignment> assignments) { }
        var rows=jdbc.query("SELECT version,time_zone,assignments::text FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? ORDER BY version DESC LIMIT 1",
                (r,i)->new State(r.getLong(1),r.getString(2),List.of(JSON.readValue(r.getString(3),WeeklySchedule.Assignment[].class))),owner,generation);
        if(rows.isEmpty())return;var latest=rows.getFirst();
        var assignments=latest.assignments().stream().filter(a->!a.routineId().equals(routine)).toList();
        if(assignments.size()==latest.assignments().size())return;
        long version=latest.version();if(version==Long.MAX_VALUE)throw new RoutineFailure(RoutineFailure.Reason.WRITE_CONFLICT);
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,recorded_at,effective_from,time_zone,assignments) VALUES (?,?,?,?,?,?,?::jsonb)",
                owner,generation,version+1,java.sql.Timestamp.from(now),WeeklyScheduleService.nextMonday(now,latest.zone()),latest.zone(),JSON.writeValueAsString(assignments));
    }
}
