package com.overload.overload_server.catalog;

import com.overload.overload_server.routine.RoutineRequests;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class StandardCardioCatalog {
    private static final String SELECTABLE = """
            FROM standard_exercise e JOIN cardio_activity_catalog a ON a.id = e.cardio_activity_id
            WHERE e.is_active AND e.muscle_targets_reviewed AND e.splits_reviewed
              AND e.record_method = 'CARDIO' AND a.active AND char_length(e.name) <= 100
            """;

    private final JdbcTemplate jdbc;

    StandardCardioCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Choice(UUID id, UUID activityId, String category, String name, String kind, List<String> aliases) { }

    List<Choice> choices() {
        return jdbc.query("SELECT e.id, a.id, a.category, e.name, a.kind " + SELECTABLE + " ORDER BY e.id",
                (row, index) -> new Choice(row.getObject(1, UUID.class), row.getObject(2, UUID.class),
                        row.getString(3), row.getString(4), row.getString(5), List.of()));
    }

    boolean selectable(UUID exerciseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 " + SELECTABLE + " AND e.id=?)", Boolean.class, exerciseId));
    }

    public boolean selectable(RoutineRequests.Plan plan) {
        return plan.standardExerciseId() != null && plan.activityId() != null
                && !jdbc.queryForList("SELECT e.id " + SELECTABLE
                        + " AND e.id=? AND a.id=? AND a.kind=? AND e.name=? FOR SHARE OF e, a",
                        UUID.class, plan.standardExerciseId(), plan.activityId(), plan.kind(), plan.name()).isEmpty();
    }
}
