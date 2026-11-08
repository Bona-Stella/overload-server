package com.overload.overload_server.deload;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeloadPeriodLifecycleTests {
    @ParameterizedTest
    @CsvSource({
        "2026-03-04, 2026-03-14T04:00:00Z",
        "2026-10-28, 2026-11-07T05:00:00Z"
    })
    void tenDatesUseFixedLocalMidnightAcrossDaylightSaving(String firstDate,String deadline) {
        var zone=ZoneId.of("America/New_York");
        var start=LocalDate.parse(firstDate);
        var startedAt=start.atTime(18,30).atZone(zone).toInstant();
        var period=new DeloadPeriodRepository.Lifecycle(UUID.randomUUID(),0,"ACTIVE",startedAt,zone,
            start,start.plusDays(9),null,null,startedAt);
        assertThat(period.deadline()).isEqualTo(Instant.parse(deadline));
        assertThat(period.deadline().minusNanos(1000).atZone(zone).toLocalDate()).isEqualTo(period.maximumEndDate());
    }
}
