package com.overload.overload_server.routine;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class WeeklyScheduleRequestsTests {
    @Test void strictFieldsCountersDaysAndTimeZonesAreRequired() {
        String valid="{\"dataGeneration\":\"0\",\"expectedVersion\":\"0\",\"timeZone\":\"Asia/Seoul\",\"assignments\":[]}";
        assertThat(WeeklyScheduleRequests.parse(valid).assignments()).isEmpty();
        for(String raw:new String[]{valid.replace("\"0\"","0"),valid.replace("Asia/Seoul","bogus"),valid.replace("\"assignments\":[]","\"assignments\":[],\"userId\":\"fake\""),valid+"{}",
                valid.replace("[]","[{\"dayOfWeek\":1,\"routineId\":\"11111111-1111-4111-8111-111111111111\"},{\"dayOfWeek\":1,\"routineId\":\"11111111-1111-4111-8111-111111111111\"}]"),
                valid.replace("[]","[{\"dayOfWeek\":0,\"routineId\":\"11111111-1111-4111-8111-111111111111\"}]")})
            assertThatThrownBy(()->WeeklyScheduleRequests.parse(raw)).isInstanceOf(RoutineFailure.class);
    }
    @Test void nextMondayUsesRegisteredLocalZoneAndMondayEditsApplyFollowingWeek() {
        assertThat(WeeklyScheduleService.nextMonday(Instant.parse("2026-11-08T14:59:59Z"),"Asia/Seoul")).isEqualTo(LocalDate.of(2026,11,9));
        assertThat(WeeklyScheduleService.nextMonday(Instant.parse("2026-11-08T15:00:00Z"),"Asia/Seoul")).isEqualTo(LocalDate.of(2026,11,16));
    }
    @Test void firstRegistrationUsesTodayWhileEverySubsequentChangeWaitsUntilMonday() {
        Instant boundary=Instant.parse("2026-11-11T15:00:00Z");
        assertThat(WeeklyScheduleService.effectiveFrom(boundary,"Asia/Seoul",true)).isEqualTo(LocalDate.of(2026,11,12));
        assertThat(WeeklyScheduleService.effectiveFrom(boundary.minusNanos(1),"Asia/Seoul",true)).isEqualTo(LocalDate.of(2026,11,11));
        assertThat(WeeklyScheduleService.effectiveFrom(boundary,"America/Los_Angeles",true)).isEqualTo(LocalDate.of(2026,11,11));
        assertThat(WeeklyScheduleService.effectiveFrom(boundary,"Asia/Seoul",false)).isEqualTo(LocalDate.of(2026,11,16));
        assertThat(WeeklyScheduleService.effectiveFrom(Instant.parse("2026-11-15T15:00:00Z"),"Asia/Seoul",false)).isEqualTo(LocalDate.of(2026,11,23));
    }
}
