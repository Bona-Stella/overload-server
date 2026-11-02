package com.overload.overload_server.routine;

import java.time.*;
import java.util.*;

public final class WeeklySchedule {
    private WeeklySchedule() { }
    public record Assignment(int dayOfWeek, UUID routineId) { }
    public record Version(String version, LocalDate effectiveFrom, String timeZone, Instant recordedAt, List<Assignment> assignments) { }
    public record View(UUID userId, String dataGeneration, String version, String timeZone, LocalDate nextEffectiveFrom,
                       Version current, Version pending, List<Version> history) { }
    public record Page(UUID userId,String dataGeneration,List<Version> history,String nextBeforeVersion) { }
    record Save(long generation, long expectedVersion, String timeZone, List<Assignment> assignments) { }
}
