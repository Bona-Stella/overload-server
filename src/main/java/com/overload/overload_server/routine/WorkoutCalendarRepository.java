package com.overload.overload_server.routine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Calendar evidence only; all writes join the caller's workout transaction and owner lock. */
@Repository
public class WorkoutCalendarRepository {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    public WorkoutCalendarRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Control(String timeZone,long baselineVersion,Instant boundary,LocalDate reviewFrom,LocalDate reviewedThrough) { }
    public Optional<Control> control(UUID owner,long generation) {
        return jdbc.query("""
            SELECT c.time_zone,c.baseline_version,s.recorded_at,c.review_from_date,c.reviewed_through
            FROM workout_calendar_control c JOIN weekly_schedule_versions s
            ON s.user_id=c.user_id AND s.data_generation=c.data_generation AND s.version=c.baseline_version
            WHERE c.user_id=? AND c.data_generation=?
            """,(r,i)->new Control(r.getString(1),r.getLong(2),r.getTimestamp(3).toInstant(),r.getObject(4,LocalDate.class),r.getObject(5,LocalDate.class)),owner,generation).stream().findFirst();
    }
    void register(UUID owner,long generation,String zone,long version,Instant at) {
        var day=at.atZone(ZoneId.of(zone)).toLocalDate();
        jdbc.update("INSERT INTO workout_calendar_control(user_id,data_generation,time_zone,baseline_version,review_from_date,reviewed_through) VALUES (?,?,?,?,?,?)",
                owner,generation,zone,version,day,day.minusDays(1));
    }
    void requireOpenTime(UUID owner,long generation,Instant at){
        control(owner,generation).ifPresent(c->{if(!at.atZone(ZoneId.of(c.timeZone())).toLocalDate().isAfter(c.reviewedThrough()))throw new RoutineFailure(RoutineFailure.Reason.WRITE_CONFLICT);});
    }
    public Long origin(UUID owner,long generation,Instant at,LocalDate day) {
        var calendar=control(owner,generation);if(calendar.isEmpty())return null;
        var c=calendar.get();
        var rows=at.isBefore(c.boundary())
            ?jdbc.query("SELECT version FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? AND version<? AND effective_from<=? ORDER BY effective_from DESC,version DESC LIMIT 1",(r,i)->r.getLong(1),owner,generation,c.baselineVersion(),day)
            :jdbc.query("SELECT version FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? AND version>=? AND recorded_at<=? ORDER BY recorded_at DESC,version DESC LIMIT 1",(r,i)->r.getLong(1),owner,generation,c.baselineVersion(),java.sql.Timestamp.from(at));
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void recordStart(UUID owner,long generation,UUID session,Long version,LocalDate day,UUID routine) {
        // A new START wakes a previously reviewed date, but is never counted as performance.
        // Duplicate START acknowledgements return before this method in the session service.
        jdbc.update("""
            INSERT INTO daily_training_assessments(user_id,data_generation,workout_date,revision) VALUES (?,?,?,1)
            ON CONFLICT(user_id,data_generation,workout_date) DO UPDATE SET needs_recheck=true,next_check_at=NULL,revision=daily_training_assessments.revision+1
            """,owner,generation,day);
        if(version==null)return;
        var rows=jdbc.query("SELECT assignments::text FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? AND version=?",(r,i)->r.getString(1),owner,generation,version);
        if(rows.isEmpty()||Arrays.stream(JSON.readValue(rows.getFirst(),WeeklySchedule.Assignment[].class)).noneMatch(a->a.dayOfWeek()==day.getDayOfWeek().getValue()&&a.routineId().equals(routine)))return;
        jdbc.update("""
            INSERT INTO daily_training_assessments(user_id,data_generation,workout_date,first_session_id) VALUES (?,?,?,?)
            ON CONFLICT(user_id,data_generation,workout_date) DO UPDATE SET first_session_id=EXCLUDED.first_session_id,needs_recheck=true,next_check_at=NULL,revision=daily_training_assessments.revision+1
            WHERE daily_training_assessments.first_session_id IS NULL AND daily_training_assessments.first_missed_at IS NULL
            """,owner,generation,day,session);
    }
}
