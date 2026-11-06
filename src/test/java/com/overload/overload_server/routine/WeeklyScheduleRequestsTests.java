package com.overload.overload_server.routine;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class WeeklyScheduleRequestsTests {
    @Test void strictFieldsCountersDaysAndTimeZonesAreRequired() {
        String valid="{\"contractVersion\":\"immediate-v1\",\"dataGeneration\":\"0\",\"expectedVersion\":\"0\",\"timeZone\":\"Asia/Seoul\",\"assignments\":[]}";
        assertThat(WeeklyScheduleRequests.parse(valid).assignments()).isEmpty();
        for(String raw:new String[]{valid.replace("\"0\"","0"),valid.replace("Asia/Seoul","bogus"),valid.replace("\"assignments\":[]","\"assignments\":[],\"userId\":\"fake\""),valid+"{}",
                valid.replace("[]","[{\"dayOfWeek\":1,\"routineId\":\"11111111-1111-4111-8111-111111111111\"},{\"dayOfWeek\":1,\"routineId\":\"11111111-1111-4111-8111-111111111111\"}]"),
                valid.replace("[]","[{\"dayOfWeek\":0,\"routineId\":\"11111111-1111-4111-8111-111111111111\"}]")})
            assertThatThrownBy(()->WeeklyScheduleRequests.parse(raw)).isInstanceOf(RoutineFailure.class);
    }
    @Test void oldReservationWritesFailBeforeAnyServiceWrite() {
        assertThatThrownBy(()->WeeklyScheduleRequests.parse("{\"dataGeneration\":\"0\",\"expectedVersion\":\"0\",\"timeZone\":\"UTC\",\"assignments\":[]}"))
            .isInstanceOfSatisfying(RoutineFailure.class,f->assertThat(f.reason).isEqualTo(RoutineFailure.Reason.SCHEDULE_CONTRACT_MISMATCH));
    }
}
