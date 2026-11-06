package com.overload.overload_server.statistics;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class DailyTrainingReviewTests {
    @Test void midnightDoesNotReplaceTheFourAmPublicationBoundary(){
        for(String now:new String[]{"2026-11-02T15:00:00Z","2026-11-02T18:30:00Z","2026-11-02T18:59:59.999999Z"})
            assertThat(DailyTrainingReviewService.dueDate(Instant.parse(now),"Asia/Seoul")).isEqualTo(LocalDate.of(2026,11,1));
        assertThat(DailyTrainingReviewService.dueDate(Instant.parse("2026-11-02T19:00:00Z"),"Asia/Seoul")).isEqualTo(LocalDate.of(2026,11,2));
    }
    @Test void storedTimeZoneDeterminesTheBoundaryAcrossDaylightSaving(){
        assertThat(DailyTrainingReviewService.dueDate(Instant.parse("2026-11-01T09:59:59Z"),"America/Chicago")).isEqualTo(LocalDate.of(2026,10,30));
        assertThat(DailyTrainingReviewService.dueDate(Instant.parse("2026-11-01T10:00:00Z"),"America/Chicago")).isEqualTo(LocalDate.of(2026,10,31));
    }
}
