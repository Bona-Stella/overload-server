package com.overload.overload_server.statistics;

import org.springframework.util.MultiValueMap;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.Set;

record StatisticsQuery(LocalDate fromDate, LocalDate toDateExclusive) {
    static StatisticsQuery parse(MultiValueMap<String, String> query) {
        if (!query.keySet().equals(Set.of("fromDate", "toDateExclusive"))
                || query.values().stream().anyMatch(v -> v.size() != 1)) throw invalid();
        var from = date(query.getFirst("fromDate"));
        var to = date(query.getFirst("toDateExclusive"));
        long days = ChronoUnit.DAYS.between(from, to);
        if (days < 1 || days > 366) throw invalid();
        return new StatisticsQuery(from, to);
    }
    private static LocalDate date(String value) {
        if (value == null || !value.matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw invalid();
        try { return LocalDate.parse(value); } catch (DateTimeException failure) { throw invalid(); }
    }
    static StatisticsFailure invalid() { return new StatisticsFailure(400, "INVALID_REQUEST"); }
}
