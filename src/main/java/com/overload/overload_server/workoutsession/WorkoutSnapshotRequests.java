package com.overload.overload_server.workoutsession;

import com.overload.overload_server.routine.RoutineRequests;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;
import static com.overload.overload_server.workoutsession.WorkoutSessionFailure.Reason.*;
final class WorkoutSnapshotRequests {
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    static Finish finish(String raw) {
        var body=parse(raw);
        fields(body,"dataGeneration","editorDeviceId","writerEpoch","localRevision","snapshot","endedAt");
        var state=new LinkedHashMap<String,JsonNode>();
        for (String field:List.of("dataGeneration","editorDeviceId","writerEpoch","localRevision","snapshot")) state.put(field,body.get(field));
        return new Finish(save(JSON.writeValueAsString(state)),time(body,"endedAt"));
    }
    static Save save(String raw) {
        var body = parse(raw);
        fields(body, "dataGeneration", "editorDeviceId", "writerEpoch", "localRevision", "snapshot");
        long revision = counter(body, "localRevision");
        if (revision==0) throw invalid();
        var s = body.get("snapshot");
        fields(s, "sessionExercises", "appliedPlan", "performedSets", "cardioResults", "deletedPerformedSetIds", "deletedCardioResultIds", "approvalRequests");
        var identities = new ArrayList<Exercise>();
        for (var p:array(s, "sessionExercises")) {
            fields(p, "id", "kind", "standardExerciseId");
            String kind = text(p, "kind");
            UUID definition = nullableUuid(p, "standardExerciseId");
            if (!Set.of("WEIGHT", "CARDIO").contains(kind)||(kind.equals("WEIGHT")!=(definition!=null))) throw invalid();
            identities.add(new Exercise(uuid(p, "id"), kind, definition));
        }
        var plan = s.get("appliedPlan");
        fields(plan, "schemaVersion", "weightExercises", "cardioActivities");
        if (count(plan, "schemaVersion")!=2) throw invalid();
        var weights = new ArrayList<WeightPlan>();
        var cardioPlans = new ArrayList<CardioPlan>();
        for (var p:array(plan, "weightExercises")) {
            fields(p, "sessionExerciseId", "setCount", "minReps", "maxReps", "manufacturerId", "loadType");
            int min = count(p, "minReps"), max = count(p, "maxReps");
            if (min>max) throw invalid();
            weights.add(new WeightPlan(uuid(p, "sessionExerciseId"), count(p, "setCount"), min, max, nullableUuid(p, "manufacturerId"), loadType(p)));
        }
        for (var p:array(plan, "cardioActivities")) {
            fields(p, "sessionActivityId", "plan");
            var rawPlan = p.get("plan");
            if (rawPlan==null||rawPlan.isNull()) throw invalid();
            cardioPlans.add(new CardioPlan(uuid(p, "sessionActivityId"), RoutineRequests.canonicalCardioPlan(rawPlan).orElseThrow(WorkoutSnapshotRequests::invalid)));
        }
        if (weights.isEmpty()&&cardioPlans.isEmpty()) throw invalid();
        var sets = new ArrayList<SetValue>();
        for (var p:array(s, "performedSets")) sets.add(set(p,false));
        var cardio = new ArrayList<Cardio>();
        for (var p:array(s, "cardioResults")) cardio.add(cardio(p));
        var approvals = new ArrayList<Approval>();
        for (var p:array(s, "approvalRequests")) approvals.add(approval(p));
        unique(identities.stream().map(Exercise::id).toList());
        unique(sets.stream().map(SetValue::id).toList());
        unique(cardio.stream().map(Cardio::id).toList());
        unique(approvals.stream().map(Approval::id).toList());
        unique(java.util.stream.Stream.concat(weights.stream().map(WeightPlan::sessionExerciseId), cardioPlans.stream().map(CardioPlan::sessionActivityId)).toList());
        // Identity and fact arrays are sets. Plan order and approval sequence retain domain meaning.
        identities.sort(Comparator.comparing(p -> p.id().toString()));
        sets.sort(Comparator.comparing(p -> p.id().toString()));
        cardio.sort(Comparator.comparing(p -> p.id().toString()));
        approvals.sort(Comparator.comparing((Approval p) -> p.sessionExerciseId().toString()).thenComparingLong(Approval::sequence));
        return new Save(counter(body, "dataGeneration"), uuid(body, "editorDeviceId"), counter(body, "writerEpoch"), revision,
            new State(List.copyOf(identities), new Plan(2, List.copyOf(weights), List.copyOf(cardioPlans)), List.copyOf(sets), List.copyOf(cardio), ids(s, "deletedPerformedSetIds"), ids(s, "deletedCardioResultIds"), List.copyOf(approvals)));
    }

    static SetValue set(JsonNode p,boolean recovery) {
        fields(p, "id", "sessionExerciseId", "ordinal", "weightKg", "reps", "completedAt", "weightContext", "manufacturerId", "loadType");
        var context = p.get("weightContext");
        fields(context, "kind", "requestId");
        String kind = text(context, "kind");
        UUID request = nullableUuid(context, "requestId");
        if (!(Set.of("INITIAL_APPROVED", "APPROVAL_REQUEST", "TODAY_ONLY").contains(kind)||recovery&&kind.equals("UNVERIFIED"))||(kind.equals("APPROVAL_REQUEST")!=(request!=null))) throw invalid();
        return new SetValue(uuid(p, "id"), uuid(p, "sessionExerciseId"), count(p, "ordinal"), decimal(p, "weightKg"), count(p, "reps"), time(p, "completedAt"), new Context(kind, request), nullableUuid(p, "manufacturerId"), loadType(p));
    }
    static Cardio cardio(JsonNode p) {
        fields(p, "id", "sessionActivityId", "status", "startedAt", "occurredAt", "result");
        String status = text(p, "status");
        Instant started = p.get("startedAt").isNull()?null:time(p, "startedAt");
        CardioValue result = null;
        var r = p.get("result");
        if (status.equals("COMPLETED")) {
            optionalFields(r,"measurement", "timerMs", "activeMs", "corrected", "distanceKm", "averageBpm");
            if (started==null) throw invalid();
            result=cardioNumbers(r,duration(r,"timerMs",0,9007199254740991L));
        }
        else if (!status.equals("SKIPPED")||!r.isNull()) throw invalid();
        return new Cardio(uuid(p, "id"), uuid(p, "sessionActivityId"), status, started, time(p, "occurredAt"), result);
    }
    static CardioValue cardioNumbers(JsonNode r,Long timer) {
        long active = duration(r, "activeMs", 1, 86400000);
        if (!r.get("corrected").isBoolean()) throw invalid();
        boolean corrected = r.get("corrected").asBoolean();
        if (timer!=null&&!corrected&&timer!=active) throw invalid();
        String distance = r.get("distanceKm").isNull()?null:decimal(r, "distanceKm");
        Integer bpm = r.get("averageBpm").isNull()?null:count(r, "averageBpm");
        if (bpm!=null&&(bpm<20||bpm>300)) throw invalid();
        CardioMeasurement measurement=null;
        if(r.has("measurement")&&!r.get("measurement").isNull()){
            var m=r.get("measurement");fields(m,"actualSpeedKmh","distanceSource");
            measurement=new CardioMeasurement(m.get("actualSpeedKmh").isNull()?null:decimal(m,"actualSpeedKmh"),m.get("distanceSource").isNull()?null:text(m,"distanceSource"));
        }
        return CardioMeasurements.normalize(new CardioValue(timer, active, corrected, distance, bpm,measurement));
    }

    static Resolve resolve(String raw) {
        var b = parse(raw);
        fields(b, "dataGeneration", "editorDeviceId", "writerEpoch", "expectedSessionVersion", "expectedLocalRevision", "sessionExerciseId", "pendingRequestIds", "decision", "occurredAt", "newApprovalRequest");
        UUID exercise = uuid(b, "sessionExerciseId");
        var ids = ids(b, "pendingRequestIds");
        if (ids.isEmpty()) throw invalid();
        String decision = text(b, "decision");
        Instant occurred = time(b, "occurredAt");
        return new Resolve(counter(b, "dataGeneration"), uuid(b, "editorDeviceId"), counter(b, "writerEpoch"), counter(b, "expectedSessionVersion"), counter(b, "expectedLocalRevision"), exercise, ids, decision, occurred, reapproval(b,exercise,occurred,decision));
    }
    static Approval reapproval(JsonNode b,UUID exercise,Instant occurred,String decision) {
        Approval next = null;
        var n = b.get("newApprovalRequest");
        if (decision.equals("REAPPROVE")) {
            fields(n, "id", "sequence", "expectedRoutineVersion", "previousApprovedWeightKg", "nextApprovedWeightKg");
            long seq = counter(n, "sequence");
            if (seq==0) throw invalid();
            next = new Approval(uuid(n, "id"), exercise, seq, null, counter(n, "expectedRoutineVersion"), decimal(n, "previousApprovedWeightKg"), decimal(n, "nextApprovedWeightKg"), occurred);
        }
        else if (!decision.equals("KEEP_CURRENT")||!n.isNull()) throw invalid();
        return next;
    }

    static Approval approval(JsonNode p) {
        fields(p, "id", "sessionExerciseId", "sequence", "previousRequestId", "expectedRoutineVersion", "previousApprovedWeightKg", "nextApprovedWeightKg", "occurredAt");
        long seq = counter(p, "sequence");
        if (seq==0) throw invalid();
        return new Approval(uuid(p, "id"), uuid(p, "sessionExerciseId"), seq, nullableUuid(p, "previousRequestId"), counter(p, "expectedRoutineVersion"), decimal(p, "previousApprovedWeightKg"), decimal(p, "nextApprovedWeightKg"), time(p, "occurredAt"));
    }

    static JsonNode parse(String raw) {
        try(var p = JSON.createParser(raw)) {
            JsonNode b = JSON.readTree(p);
            if (p.nextToken()!=null)throw invalid();
            return b;
        }
        catch (tools.jackson.core.JacksonException e) {
            throw invalid();
        }
    }

    static void fields(JsonNode p, String... names) {
        if (p==null||!p.isObject()||p.size()!=names.length)throw invalid();
        var allowed = Set.of(names);
        for (String n:p.propertyNames())if (!allowed.contains(n))throw invalid();
    }
    static void optionalFields(JsonNode p,String optional,String... required){
        var fields=new ArrayList<>(Arrays.asList(required));if(p!=null&&p.has(optional))fields.add(optional);fields(p,fields.toArray(String[]::new));
    }

    static Iterable<JsonNode> array(JsonNode p, String n) {
        var a = p.get(n);
        if (a==null||!a.isArray())throw invalid();
        return a;
    }

    static String text(JsonNode p, String n) {
        var v = p.get(n);
        if (v==null||!v.isString())throw invalid();
        return v.asString();
    }

    static UUID uuid(JsonNode p, String n) {
        return WorkoutSessionRequests.uuid(text(p, n));
    }

    static String loadType(JsonNode p) {
        var value = p.get("loadType");
        if (value == null) throw invalid();
        if (value.isNull()) return null;
        String code = text(p, "loadType");
        if (!Set.of("PLATE", "STACK", "DUMBBELL", "BODYWEIGHT").contains(code)) throw invalid();
        return code;
    }

    static UUID nullableUuid(JsonNode p, String n) {
        var v = p.get(n);
        if (v==null)throw invalid();
        return v.isNull()?null:uuid(p, n);
    }

    static long counter(JsonNode p, String n) {
        String s = text(p, n);
        if (!s.matches("0|[1-9][0-9]{0,18}"))throw invalid();
        try {
            return Long.parseLong(s);
        }
        catch (NumberFormatException e) {
            throw invalid();
        }
    }

    static int count(JsonNode p, String n) {
        var v = p.get(n);
        if (v==null||!v.isIntegralNumber()||!v.canConvertToInt()||v.intValue()<1)throw invalid();
        return v.intValue();
    }

    static long duration(JsonNode p, String n, long min, long max) {
        var v = p.get(n);
        if (v==null||!v.isIntegralNumber()||!v.canConvertToLong()||v.longValue()<min||v.longValue()>max)throw invalid();
        return v.longValue();
    }

    static String decimal(JsonNode p, String n) {
        String s = text(p, n);
        if (!s.matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{0,5}[1-9])?"))throw invalid();
        return s;
    }

    static Instant time(JsonNode p, String n) {
        String s = text(p, n);
        if (!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?Z")||s.startsWith("0000"))throw invalid();
        try {
            return OffsetDateTime.parse(s).toInstant();
        }
        catch (DateTimeException e) {
            throw invalid();
        }
    }

    static List<UUID> ids(JsonNode p, String n) {
        var r = new ArrayList<UUID>();
        for (var v:array(p, n)) {
            if (!v.isString())throw invalid();
            r.add(WorkoutSessionRequests.uuid(v.asString()));
        }
        unique(r);
        r.sort(Comparator.comparing(UUID::toString));
        return List.copyOf(r);
    }

    static void unique(List<UUID> ids) {
        if (new HashSet<>(ids).size()!=ids.size())throw invalid();
    }

    static WorkoutSessionFailure invalid() {
        return new WorkoutSessionFailure(INVALID_REQUEST);
    }
}
