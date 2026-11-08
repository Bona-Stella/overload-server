package com.overload.overload_server.deload;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static com.overload.overload_server.deload.DeloadPlan.*;

final class DeloadPeriodRequests {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    record Preview(long generation,UUID presetId,Choice choice){}
    record Start(long generation,UUID requestId,String previewToken,UUID presetId,Choice choice){}
    record End(long generation, long expectedVersion, UUID requestId) {}
    static End end(String raw) {
        var body=parse(raw);
        fields(body,"dataGeneration","expectedVersion","requestId");
        return new End(counter(body.get("dataGeneration")),counter(body.get("expectedVersion")),id(text(body,"requestId")));
    }
    static DeloadTemporaryPlans.Edit edit(String raw,UUID plan) {
        var b=parse(raw);fields(b,"dataGeneration","expectedVersion","requestId","name","weightPlacements","cardioPlacements");
        var values=new LinkedHashMap<String,Object>();values.put("id",plan);for(String field:List.of("dataGeneration","name","weightPlacements","cardioPlacements"))values.put(field,b.get(field));
        return new DeloadTemporaryPlans.Edit(counter(b.get("dataGeneration")),counter(b.get("expectedVersion")),id(text(b,"requestId")),plan,com.overload.overload_server.routine.RoutineRequests.canonicalCreate(JSON.writeValueAsString(values)).orElseThrow(DeloadPeriodRequests::invalid));
    }
    static DeloadTemporaryPlans.Move move(String raw,UUID plan) {
        var b=parse(raw);fields(b,"dataGeneration","expectedVersion","requestId","date","swapPlanId");
        try{return new DeloadTemporaryPlans.Move(counter(b.get("dataGeneration")),counter(b.get("expectedVersion")),id(text(b,"requestId")),plan,LocalDate.parse(text(b,"date")),b.get("swapPlanId").isNull()?null:id(text(b,"swapPlanId")));}
        catch(DateTimeException bad){throw invalid();}
    }
    static DeloadTemporaryPlans.Remove remove(String raw,UUID plan) {var b=parse(raw);fields(b,"dataGeneration","expectedVersion","requestId");return new DeloadTemporaryPlans.Remove(counter(b.get("dataGeneration")),counter(b.get("expectedVersion")),id(text(b,"requestId")),plan);}
    static DeloadTemporaryPlans.Change change(String raw) {var b=parse(raw);fields(b,"dataGeneration","expectedVersion","requestId","method","split","dates");return new DeloadTemporaryPlans.Change(counter(b.get("dataGeneration")),counter(b.get("expectedVersion")),id(text(b,"requestId")),choice(b));}
    static UUID id(String text){try{var id=UUID.fromString(text);if(!id.toString().equalsIgnoreCase(text))throw invalid();return id;}catch(IllegalArgumentException bad){throw invalid();}}
    private static DeloadPeriodFailure invalid(){return DeloadPeriodFailure.invalid();}
    static Preview preview(String raw) {
        var b=parse(raw);
        UUID preset=selection(b,"dataGeneration","method","split","dates");
        return new Preview(counter(b.get("dataGeneration")),preset,choice(b));
    }
    static Start start(String raw) {
        var b=parse(raw);
        UUID preset=selection(b,"dataGeneration","requestId","previewToken","method","split","dates");
        String token=text(b,"previewToken");
        if(!token.matches("[0-9a-f]{64}"))throw invalid();
        return new Start(counter(b.get("dataGeneration")),id(text(b,"requestId")),token,preset,choice(b));
    }
    private static UUID selection(JsonNode body, String... required) {
        if (body == null || !body.has("presetId")) {
            fields(body, required);
            return null;
        }
        var names = Arrays.copyOf(required, required.length + 1);
        names[required.length] = "presetId";
        fields(body, names);
        return id(text(body, "presetId"));
    }
    private static Choice choice(JsonNode b) {
        try{var dates=new ArrayList<LocalDate>();var list=b.get("dates");if(!list.isArray()||list.size()>7)throw DeloadPeriodFailure.invalid();for(var d:list){if(!d.isString())throw DeloadPeriodFailure.invalid();var date=LocalDate.parse(d.asString());if(!date.toString().equals(d.asString()))throw DeloadPeriodFailure.invalid();dates.add(date);}return new Choice(Method.valueOf(text(b,"method")),Split.valueOf(text(b,"split")),List.copyOf(dates));}catch(IllegalArgumentException|DateTimeException bad){throw DeloadPeriodFailure.invalid();}
    }
    private static long counter(JsonNode n){if(n==null||!n.isString()||!n.asString().matches("0|[1-9][0-9]{0,18}"))throw DeloadPeriodFailure.invalid();try{return Long.parseLong(n.asString());}catch(NumberFormatException bad){throw DeloadPeriodFailure.invalid();}}
    private static String text(JsonNode b,String key){var n=b.get(key);if(n==null||!n.isString())throw DeloadPeriodFailure.invalid();return n.asString();}
    private static void fields(JsonNode b,String... keys){if(b==null||!b.isObject()||b.size()!=keys.length||Arrays.stream(keys).anyMatch(k->!b.has(k)))throw DeloadPeriodFailure.invalid();}
    private static JsonNode parse(String raw){try(var parser=JSON.createParser(raw)){var b=JSON.readTree(parser);if(parser.nextToken()!=null)throw DeloadPeriodFailure.invalid();return b;}catch(tools.jackson.core.JacksonException bad){throw DeloadPeriodFailure.invalid();}}
}
