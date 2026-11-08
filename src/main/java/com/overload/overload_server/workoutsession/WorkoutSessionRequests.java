package com.overload.overload_server.workoutsession;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static com.overload.overload_server.workoutsession.WorkoutSessionFailure.Reason.*;

/** Strict boundary local to session APIs; does not alter existing JSON/authentication configuration. */
final class WorkoutSessionRequests {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    record Start(UUID id, long dataGeneration, UUID routineId, UUID editorDeviceId,
                 Instant startedAt, LocalDate workoutDate, String startedTimeZone) { }
    record DeloadStart(Start facts,UUID planId,long planVersion) { }
    record Abandon(long generation,long version,UUID device,long epoch,long revision) { }
    record Takeover(long dataGeneration, UUID editorDeviceId, long expectedWriterEpoch) { }
    record ClosedQuery(int limit, Instant beforeClosedAt, UUID beforeId) { }
    static ClosedQuery closed(org.springframework.util.MultiValueMap<String,String> query) {
        if (query.entrySet().stream().anyMatch(e -> !Set.of("limit","beforeClosedAt","beforeId").contains(e.getKey()) || e.getValue().size()!=1)) throw invalid();
        String limit=query.getFirst("limit"), at=query.getFirst("beforeClosedAt"), id=query.getFirst("beforeId");
        if (limit!=null&&!limit.matches("[1-9]|[1-4][0-9]|50") || (at==null)!=(id==null)) throw invalid();
        Instant before=null;
        if (at!=null) {
            if (!at.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,6})?Z") || at.startsWith("0000")) throw invalid();
            try { before=Instant.parse(at); } catch (DateTimeException failure) { throw invalid(); }
        }
        return new ClosedQuery(limit==null?20:Integer.parseInt(limit),before,id==null?null:uuid(id));
    }

    static Start start(String raw) {
        var body = parse(raw);
        fields(body, "id", "dataGeneration", "routineId", "editorDeviceId", "startedAt", "workoutDate", "startedTimeZone");
        return context(body,uuid(text(body,"routineId")));
    }
    static DeloadStart deloadStart(String raw) {
        var b=parse(raw);fields(b,"id","dataGeneration","planId","expectedPlanVersion","editorDeviceId","startedAt","workoutDate","startedTimeZone");
        return new DeloadStart(context(b,null),uuid(text(b,"planId")),counter(b,"expectedPlanVersion"));
    }
    static Abandon abandon(String raw) {
        var b=parse(raw);fields(b,"dataGeneration","expectedVersion","editorDeviceId","writerEpoch","localRevision","confirm");
        if(!b.get("confirm").isBoolean()||!b.get("confirm").asBoolean())throw invalid();
        return new Abandon(counter(b,"dataGeneration"),counter(b,"expectedVersion"),uuid(text(b,"editorDeviceId")),counter(b,"writerEpoch"),counter(b,"localRevision"));
    }
    private static Start context(JsonNode body,UUID routine) {
        String time = text(body, "startedAt"), date = text(body, "workoutDate"), zone = text(body, "startedTimeZone");
        if (!time.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?Z")
                || time.startsWith("0000") || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || date.startsWith("0000")) throw invalid();
        Instant started; LocalDate local;
        try { started = OffsetDateTime.parse(time).toInstant(); local = LocalDate.parse(date); }
        catch (DateTimeException failure) { throw invalid(); }
        if (!ZoneId.getAvailableZoneIds().contains(zone) || !LocalDate.ofInstant(started, ZoneId.of(zone)).equals(local)) {
            throw new WorkoutSessionFailure(CLOCK_CONTEXT_INVALID);
        }
        return new Start(uuid(text(body, "id")), counter(body, "dataGeneration"), routine,
                uuid(text(body, "editorDeviceId")), started, local, zone);
    }

    static Takeover takeover(String raw) {
        var body = parse(raw); fields(body, "dataGeneration", "editorDeviceId", "expectedWriterEpoch");
        return new Takeover(counter(body, "dataGeneration"), uuid(text(body, "editorDeviceId")), counter(body, "expectedWriterEpoch"));
    }

    static UUID uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw invalid();
            return id;
        } catch (IllegalArgumentException failure) { throw invalid(); }
    }
    private static JsonNode parse(String raw) {
        try (var parser = JSON.createParser(raw)) {
            JsonNode result = JSON.readTree(parser);
            if (parser.nextToken() != null) throw invalid();
            return result;
        } catch (tools.jackson.core.JacksonException failure) { throw invalid(); }
    }
    private static void fields(JsonNode value, String... fields) {
        if (value == null || !value.isObject() || value.size() != fields.length) throw invalid();
        var names = Set.of(fields);
        for (String name : value.propertyNames()) if (!names.contains(name)) throw invalid();
    }
    private static String text(JsonNode value, String name) {
        var item = value.get(name); if (item == null || !item.isString()) throw invalid(); return item.asString();
    }
    private static long counter(JsonNode value, String name) {
        String text = text(value, name);
        if (!text.matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(text); } catch (NumberFormatException failure) { throw invalid(); }
    }
    static WorkoutSessionFailure invalid() { return new WorkoutSessionFailure(INVALID_REQUEST); }
}
