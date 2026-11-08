package com.overload.overload_server.routine;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

final class WeeklyScheduleRequests {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    static WeeklySchedule.Configuration create(String raw) {
        return configuration(body(raw, false, "dataGeneration", "expectedStateVersion"));
    }

    static WeeklySchedule.Edit edit(String raw) {
        var body = body(raw, true, "dataGeneration", "expectedStateVersion", "expectedPresetVersion", "assignments");
        var values = body.get("assignments");
        if (!values.isArray() || values.size() > 7) throw RoutineRequests.invalid();
        var assignments = new ArrayList<WeeklySchedule.Assignment>();
        var days = new HashSet<Integer>();
        for (var value : values) {
            fields(value, false, "dayOfWeek", "routineId");
            var day = value.get("dayOfWeek");
            if (!day.isInt() || day.intValue() < 1 || day.intValue() > 7 || !days.add(day.intValue()))
                throw RoutineRequests.invalid();
            assignments.add(new WeeklySchedule.Assignment(day.intValue(), RoutineRequests.uuid(text(value.get("routineId")))));
        }
        assignments.sort(Comparator.comparingInt(WeeklySchedule.Assignment::dayOfWeek));
        return new WeeklySchedule.Edit(configuration(body), counter(body.get("expectedPresetVersion")), zone(body), List.copyOf(assignments));
    }

    static WeeklySchedule.Activate activate(String raw) {
        var body = body(raw, true, "dataGeneration", "expectedStateVersion", "presetId");
        return new WeeklySchedule.Activate(configuration(body), RoutineRequests.uuid(text(body.get("presetId"))), zone(body));
    }

    static WeeklySchedule.Delete delete(String raw) {
        var body = body(raw, false, "dataGeneration", "expectedStateVersion", "expectedPresetVersion");
        return new WeeklySchedule.Delete(configuration(body), counter(body.get("expectedPresetVersion")));
    }

    private static WeeklySchedule.Configuration configuration(JsonNode body) {
        return new WeeklySchedule.Configuration(counter(body.get("dataGeneration")), counter(body.get("expectedStateVersion")));
    }

    private static String zone(JsonNode body) {
        if (!body.has("timeZone")) return null;
        String zone = text(body.get("timeZone"));
        if (!ZoneId.getAvailableZoneIds().contains(zone)) throw RoutineRequests.invalid();
        return zone;
    }

    private static JsonNode body(String raw, boolean optionalZone, String... names) {
        try (var parser = JSON.createParser(raw)) {
            var body = JSON.readTree(parser);
            fields(body, optionalZone, names);
            if (parser.nextToken() != null) throw RoutineRequests.invalid();
            return body;
        } catch (tools.jackson.core.JacksonException failure) {
            throw RoutineRequests.invalid();
        }
    }

    private static void fields(JsonNode node, boolean optionalZone, String... names) {
        if (node == null || !node.isObject() || node.size() != names.length + (optionalZone && node.has("timeZone") ? 1 : 0))
            throw RoutineRequests.invalid();
        for (String name : names) if (!node.has(name)) throw RoutineRequests.invalid();
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isString()) throw RoutineRequests.invalid();
        return value.asString();
    }

    private static long counter(JsonNode value) {
        String counter = text(value);
        if (!counter.matches("0|[1-9][0-9]{0,18}")) throw RoutineRequests.invalid();
        try {
            return Long.parseLong(counter);
        } catch (NumberFormatException failure) {
            throw RoutineRequests.invalid();
        }
    }
}
