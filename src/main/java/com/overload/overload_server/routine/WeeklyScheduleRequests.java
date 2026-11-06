package com.overload.overload_server.routine;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.ZoneId;
import java.util.*;

final class WeeklyScheduleRequests {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    static String zone(String value) {
        if(value==null || !ZoneId.getAvailableZoneIds().contains(value))throw RoutineRequests.invalid();return value;
    }
    static WeeklySchedule.Save parse(String raw) {
        try(var parser=JSON.createParser(raw)) {
            var body=JSON.readTree(parser);if(parser.nextToken()!=null)throw RoutineRequests.invalid();
            if(body==null||!body.isObject())throw RoutineRequests.invalid();
            if(!body.has("contractVersion")||!body.get("contractVersion").isString()||!body.get("contractVersion").asString().equals("immediate-v1"))
                throw new RoutineFailure(RoutineFailure.Reason.SCHEDULE_CONTRACT_MISMATCH);
            fields(body,"contractVersion","dataGeneration","expectedVersion","timeZone","assignments");
            long generation=counter(body.get("dataGeneration")),version=counter(body.get("expectedVersion"));
            String zone=zone(text(body.get("timeZone")));
            var values=body.get("assignments");if(!values.isArray()||values.size()>7)throw RoutineRequests.invalid();
            var result=new ArrayList<WeeklySchedule.Assignment>();var days=new HashSet<Integer>();
            for(var value:values) {
                fields(value,"dayOfWeek","routineId");var day=value.get("dayOfWeek");
                if(!day.isInt()||day.intValue()<1||day.intValue()>7||!days.add(day.intValue()))throw RoutineRequests.invalid();
                result.add(new WeeklySchedule.Assignment(day.intValue(),RoutineRequests.uuid(text(value.get("routineId")))));
            }
            result.sort(Comparator.comparingInt(WeeklySchedule.Assignment::dayOfWeek));
            return new WeeklySchedule.Save(generation,version,zone,List.copyOf(result));
        } catch(tools.jackson.core.JacksonException failure){throw RoutineRequests.invalid();}
    }
    private static void fields(JsonNode node,String... names) {
        if(node==null||!node.isObject()||node.size()!=names.length)throw RoutineRequests.invalid();
        for(String name:names)if(!node.has(name))throw RoutineRequests.invalid();
    }
    private static String text(JsonNode value){if(value==null||!value.isString())throw RoutineRequests.invalid();return value.asString();}
    private static long counter(JsonNode value){String s=text(value);if(!s.matches("0|[1-9][0-9]{0,18}"))throw RoutineRequests.invalid();try{return Long.parseLong(s);}catch(NumberFormatException failure){throw RoutineRequests.invalid();}}
}
