package com.overload.overload_server.statistics;

import org.springframework.util.MultiValueMap;
import java.time.*;
import java.util.*;

record GrowthQuery(String period,String timeZone,String search,int offset) {
    static GrowthQuery parse(MultiValueMap<String,String> values,boolean searchAllowed,boolean pageAllowed) {
        var allowed=new HashSet<>(Set.of("period","timeZone"));if(searchAllowed)allowed.add("q");if(pageAllowed)allowed.add("offset");
        if(!allowed.containsAll(values.keySet())||values.values().stream().anyMatch(v->v.size()!=1))throw StatisticsQuery.invalid();
        String period=values.getFirst("period"),zone=values.getFirst("timeZone"),q=values.getFirst("q");
        if(!Set.of("1","3","6","all").contains(period==null?"":period)||zone==null||!ZoneId.getAvailableZoneIds().contains(zone))throw StatisticsQuery.invalid();
        q=q==null?"":q.strip();if(q.length()>100)throw StatisticsQuery.invalid();
        String raw=values.getFirst("offset");int offset=0;
        if(raw!=null){if(!raw.matches("0|[1-9][0-9]{0,9}"))throw StatisticsQuery.invalid();try{offset=Integer.parseInt(raw);if(offset>Integer.MAX_VALUE-101)throw StatisticsQuery.invalid();}catch(NumberFormatException failure){throw StatisticsQuery.invalid();}}
        return new GrowthQuery(period,zone,q,offset);
    }
    Range range(Instant now) {
        ZoneId zone=ZoneId.of(timeZone);LocalDate today=LocalDate.ofInstant(now,zone),from=period.equals("all")?null:today.minusMonths(Integer.parseInt(period));
        return new Range(from,today.plusDays(1),timeZone);
    }
    record Range(LocalDate fromDate,LocalDate toDateExclusive,String timeZone) {
        Object[] parameters(UUID owner,long generation) {
            ZoneId zone=ZoneId.of(timeZone);
            return new Object[]{owner,generation,fromDate==null?null:java.sql.Timestamp.from(fromDate.atStartOfDay(zone).toInstant()),java.sql.Timestamp.from(toDateExclusive.atStartOfDay(zone).toInstant()),fromDate,toDateExclusive};
        }
    }
    static UUID id(String raw){if(raw==null||!raw.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))throw StatisticsQuery.invalid();return UUID.fromString(raw);}
}
