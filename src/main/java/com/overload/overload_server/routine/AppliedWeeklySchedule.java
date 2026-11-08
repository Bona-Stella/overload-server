package com.overload.overload_server.routine;

import com.overload.overload_server.deload.DeloadAnalysisService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.overload.overload_server.routine.RoutineFailure.Reason.*;

@Repository
class AppliedWeeklySchedule {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final RoutineRepository routines;
    private final RoutinePlanHistory plans;
    private final WorkoutCalendarRepository calendars;
    private final DeloadAnalysisService analysis;

    AppliedWeeklySchedule(JdbcTemplate jdbc, RoutineRepository routines, RoutinePlanHistory plans,
                          WorkoutCalendarRepository calendars, DeloadAnalysisService analysis) {
        this.jdbc = jdbc;
        this.routines = routines;
        this.plans = plans;
        this.calendars = calendars;
        this.analysis = analysis;
    }

    Optional<WeeklySchedule.Version> current(WeeklyScheduleStore.Scope scope) {
        return jdbc.query("""
                SELECT s.version, s.effective_from, s.time_zone, s.recorded_at, s.assignments::text
                FROM workout_calendar_control c JOIN weekly_schedule_versions s
                  ON s.user_id=c.user_id AND s.data_generation=c.data_generation AND s.version>=c.baseline_version
                WHERE c.user_id=? AND c.data_generation=? ORDER BY s.version DESC LIMIT 1
                """, (row, index) -> new WeeklySchedule.Version(row.getString(1), row.getObject(2, LocalDate.class),
                    row.getString(3), row.getTimestamp(4).toInstant(),
                    List.of(JSON.readValue(row.getString(5), WeeklySchedule.Assignment[].class))),
                scope.owner(), scope.generation()).stream().findFirst();
    }

    String timeZone(WeeklyScheduleStore.Scope scope, Optional<WeeklySchedule.Version> current) {
        return current.map(WeeklySchedule.Version::timeZone)
                .orElseGet(() -> analysis.boundTimeZone(scope.owner(), scope.generation()));
    }

    String resolveTimeZone(WeeklyScheduleStore.Scope scope, Optional<WeeklySchedule.Version> current, String requested) {
        String bound = timeZone(scope, current);
        if (bound != null && requested != null && !bound.equals(requested)) throw new RoutineFailure(SCHEDULE_TIME_ZONE_CONFLICT);
        return bound == null ? requested : bound;
    }

    void applyPreset(WeeklyScheduleStore.Scope scope, WeeklyScheduleStore.State state, WeeklySchedule.Preset target,
                     Optional<WeeklySchedule.Version> current, String zone, Instant at) {
        if (current.isEmpty() || !current.get().assignments().equals(target.assignments())) {
            apply(scope, current, zone, target.assignments(), at);
        } else if (!target.id().equals(state.activePresetId())) {
            plans.requireEditableSchedule(scope.owner(), scope.generation());
        }
    }

    /** Caller holds the owner lock in the same transaction as preset and routine changes. */
    void apply(WeeklyScheduleStore.Scope scope, Optional<WeeklySchedule.Version> current, String zone,
               List<WeeklySchedule.Assignment> assignments, Instant now) {
        UUID owner = scope.owner();
        long generation = scope.generation();
        if (zone == null) throw new RoutineFailure(SCHEDULE_TIME_ZONE_REQUIRED);
        plans.requireEditableSchedule(owner, generation);
        calendars.requireOpenTime(owner, generation, now);
        if (current.isPresent() && now.isBefore(current.get().recordedAt())) throw new RoutineFailure(WRITE_CONFLICT);

        long previous = current.map(value -> Long.parseLong(value.version())).orElse(0L);
        if (previous == Long.MAX_VALUE) throw new RoutineFailure(WRITE_CONFLICT);

        analysis.bindForSchedule(owner, generation, zone);
        for (UUID routineId : assignments.stream().map(WeeklySchedule.Assignment::routineId).distinct().sorted().toList())
            plans.record(routines.schedulePlan(owner, generation, routineId), now, false);

        jdbc.update("""
                INSERT INTO weekly_schedule_versions(user_id,data_generation,version,recorded_at,effective_from,time_zone,assignments)
                VALUES (?,?,?,?,?,?,?::jsonb)
                """, owner, generation, previous + 1, Timestamp.from(now), now.atZone(ZoneId.of(zone)).toLocalDate(),
                zone, JSON.writeValueAsString(assignments));
        if (current.isEmpty()) calendars.register(owner, generation, zone, previous + 1, now);
    }
}
