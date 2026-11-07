package com.overload.overload_server.deload;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.ZoneId;
import static com.overload.overload_server.deload.DeloadAnalysisFailure.Reason.INVALID_REQUEST;

final class DeloadAnalysisRequests {
    private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    record Save(long generation,long expectedVersion,boolean enabled,String timeZone) { }
    static Save parse(String raw) {
        try(var parser=JSON.createParser(raw)) {
            var b=JSON.readTree(parser);
            if(parser.nextToken()!=null||b==null||!b.isObject()||b.size()!=4
                ||!b.has("dataGeneration")||!b.has("expectedVersion")||!b.has("enabled")||!b.has("timeZone")
                ||!b.get("enabled").isBoolean()||!b.get("timeZone").isString())throw invalid();
            return new Save(counter(b.get("dataGeneration")),counter(b.get("expectedVersion")),b.get("enabled").asBoolean(),zone(b.get("timeZone").asString()));
        } catch(tools.jackson.core.JacksonException failure) { throw invalid(); }
    }
    static String zone(String zone) {
        if(zone==null||!ZoneId.getAvailableZoneIds().contains(zone))throw invalid();
        return zone;
    }
    private static long counter(JsonNode value) {
        if(!value.isString()||!value.asString().matches("0|[1-9][0-9]{0,18}"))throw invalid();
        try{return Long.parseLong(value.asString());}catch(NumberFormatException failure){throw invalid();}
    }
    static DeloadAnalysisFailure invalid() { return new DeloadAnalysisFailure(INVALID_REQUEST); }
}
