package com.overload.overload_server.routine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class WeeklyScheduleStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    record Scope(UUID owner, long generation) { }
    record State(UUID activePresetId, boolean autoEnabled, long version, Instant nextRotationAt) { }

    WeeklyScheduleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<State> state(Scope scope) {
        return jdbc.query("""
                SELECT active_preset_id, auto_enabled, state_version, next_rotation_at
                FROM weekly_schedule_state WHERE user_id=? AND data_generation=?
                """, (row, index) -> {
                    Timestamp next = row.getTimestamp("next_rotation_at");
                    return new State(row.getObject("active_preset_id", UUID.class), row.getBoolean("auto_enabled"),
                            row.getLong("state_version"), next == null ? null : next.toInstant());
                }, scope.owner(), scope.generation()).stream().findFirst();
    }

    List<WeeklySchedule.Preset> presets(Scope scope) {
        return jdbc.query("""
                SELECT id, display_order, preset_version, rotation_included, assignments::text
                FROM weekly_schedule_presets WHERE user_id=? AND data_generation=? ORDER BY display_order
                """, (row, index) -> new WeeklySchedule.Preset(row.getObject(1, UUID.class), row.getInt(2),
                    row.getString(3), row.getBoolean(4), List.of(JSON.readValue(row.getString(5), WeeklySchedule.Assignment[].class))),
                scope.owner(), scope.generation());
    }

    void create(Scope scope, int order) {
        jdbc.update("""
                INSERT INTO weekly_schedule_presets(user_id, data_generation, id, display_order) VALUES (?,?,?,?)
                """, scope.owner(), scope.generation(), UUID.randomUUID(), order);
    }

    void edit(Scope scope, UUID id, List<WeeklySchedule.Assignment> assignments) {
        jdbc.update("""
                UPDATE weekly_schedule_presets SET assignments=?::jsonb, preset_version=preset_version+1
                WHERE user_id=? AND data_generation=? AND id=?
                """, JSON.writeValueAsString(assignments), scope.owner(), scope.generation(), id);
    }

    void changeState(Scope scope, UUID activePresetId) {
        jdbc.update("""
                UPDATE weekly_schedule_state SET active_preset_id=?, state_version=state_version+1
                WHERE user_id=? AND data_generation=?
                """, activePresetId, scope.owner(), scope.generation());
    }

    void delete(Scope scope, WeeklySchedule.Preset preset) {
        jdbc.update("DELETE FROM weekly_schedule_presets WHERE user_id=? AND data_generation=? AND id=?",
                scope.owner(), scope.generation(), preset.id());
        // V30's DEFERRABLE INITIALLY IMMEDIATE unique constraint checks the final positions for this statement.
        jdbc.update("""
                UPDATE weekly_schedule_presets SET display_order=display_order-1
                WHERE user_id=? AND data_generation=? AND display_order>?
                """, scope.owner(), scope.generation(), preset.displayOrder());
    }
}
