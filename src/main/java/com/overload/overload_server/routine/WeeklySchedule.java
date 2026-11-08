package com.overload.overload_server.routine;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class WeeklySchedule {
    private WeeklySchedule() { }

    public record Assignment(int dayOfWeek, UUID routineId) { }
    public record Version(String version, LocalDate effectiveFrom, String timeZone, Instant recordedAt, List<Assignment> assignments) { }

    public record Preset(UUID id, int displayOrder, String presetVersion, boolean rotationIncluded, List<Assignment> assignments) { }
    public record View(String dataGeneration, String stateVersion, UUID activePresetId, boolean autoEnabled, String timeZone,
                       Instant nextRotationAt, String appliedScheduleVersion, Instant appliedAt, List<Preset> presets) { }

    record Configuration(long generation, long expectedStateVersion) { }
    record Edit(Configuration configuration, long expectedPresetVersion, String timeZone, List<Assignment> assignments) { }
    record Activate(Configuration configuration, UUID presetId, String timeZone) { }
    record RotationEntry(UUID id, boolean rotationIncluded) { }
    record Rotation(Configuration configuration, boolean autoEnabled, String timeZone, List<RotationEntry> presets) { }
    record Delete(Configuration configuration, long expectedPresetVersion) { }
}
