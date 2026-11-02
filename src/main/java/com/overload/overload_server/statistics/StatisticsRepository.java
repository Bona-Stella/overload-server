package com.overload.overload_server.statistics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import com.overload.overload_server.workoutsession.InitialPlan;
import com.overload.overload_server.workoutsession.WorkoutSnapshotData;
import java.util.*;
import static com.overload.overload_server.statistics.StatisticsCalculator.*;

/** Fixed-count reads in the same workout transaction, with owner/generation in every fact query. */
@Repository
class StatisticsRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCOPE = " ws.user_id=? AND ws.data_generation=? AND ws.workout_date>=? AND ws.workout_date<?"
            + " AND ws.status IN ('FINISHED','AUTO_CLOSED') AND ws.deleted_at IS NULL ";
    private final JdbcTemplate jdbc;
    StatisticsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    long generation(UUID owner) {
        var rows = jdbc.query("SELECT data_generation FROM users WHERE id=?", (r, i) -> r.getLong(1), owner);
        if (rows.isEmpty()) throw new StatisticsFailure(401, "AUTHENTICATION_REQUIRED");
        return rows.getFirst();
    }
    Input read(UUID owner, long generation, StatisticsQuery period) {
        Object[] args = {owner, generation, period.fromDate(), period.toDateExclusive()};
        var sessions = jdbc.query("SELECT ws.id,ws.workout_date,ws.initial_plan_snapshot::text,ws.applied_plan_snapshot::text FROM workout_sessions ws WHERE " + SCOPE + " ORDER BY ws.started_at DESC,ws.id", (r, i) ->
                new Session(r.getObject(1, UUID.class), r.getObject(2, java.time.LocalDate.class), JSON.readValue(r.getString(3), InitialPlan.class), JSON.readValue(r.getString(4), WorkoutSnapshotData.Plan.class)), args);
        var sets = jdbc.query("""
                SELECT ps.id,ps.session_id,ps.session_exercise_id,se.standard_exercise_id,coalesce(se.name_at_addition,e.name),ps.weight,ps.reps,ps.load_type,(ps.bodyweight_load->>'additionalWeightKg')::numeric
                FROM workout_sessions ws JOIN performed_sets ps ON ps.session_id=ws.id
                JOIN session_exercises se ON se.id=ps.session_exercise_id AND se.session_id=ws.id AND se.user_id=ws.user_id AND se.data_generation=ws.data_generation
                JOIN standard_exercise e ON e.id=se.standard_exercise_id
                WHERE ps.deleted_at IS NULL AND ps.kind='WEIGHT' AND
                """ + SCOPE + " ORDER BY ps.id", (r, i) -> new SetFact(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class), r.getObject(4, UUID.class), r.getString(5), r.getBigDecimal(6), r.getInt(7), r.getString(8),r.getBigDecimal(9)), args);
        var cardio = jdbc.query("SELECT c.session_id,c.session_activity_id,c.active_ms,c.distance,(c.measurement->>'actualSpeedKmh')::numeric,c.measurement->>'distanceSource' FROM workout_sessions ws JOIN cardio_results c ON c.session_id=ws.id WHERE c.deleted_at IS NULL AND c.status='COMPLETED' AND " + SCOPE + " ORDER BY c.id",
                (r, i) -> new CardioFact(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3), r.getBigDecimal(4),r.getBigDecimal(5),r.getString(6)), args);
        var mappings = jdbc.query("SELECT em.standard_exercise_id,m.id,m.name,m.group_code,em.role FROM exercise_muscle em JOIN muscle m ON m.id=em.muscle_id WHERE m.is_active ORDER BY em.standard_exercise_id,m.id",
                (r, i) -> new Mapping(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4), r.getString(5)));
        var recent = jdbc.query("""
                SELECT id,initial_plan_snapshot->>'routineName',workout_date,started_at,status FROM workout_sessions
                WHERE user_id=? AND data_generation=? AND status IN ('FINISHED','AUTO_CLOSED') AND deleted_at IS NULL
                ORDER BY started_at DESC,id LIMIT 3
                """, (r, i) -> new StatisticsResponse.Recent(r.getObject(1, UUID.class), r.getString(2), r.getObject(3, java.time.LocalDate.class), r.getTimestamp(4).toInstant(), r.getString(5)), owner, generation);
        return new Input(sessions, sets, cardio, mappings, recent);
    }
}
