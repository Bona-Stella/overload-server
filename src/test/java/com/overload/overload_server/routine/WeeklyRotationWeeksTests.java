package com.overload.overload_server.routine;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyRotationWeeksTests {
    @Test void countsLocalMondayBoundariesAcrossSpringAndFallDst() {
        var zone = ZoneId.of("America/New_York");
        var spring = WeeklyRotationWeeks.nextMonday(LocalDate.parse("2026-03-01"), zone);
        var following = WeeklyRotationWeeks.nextMonday(LocalDate.parse("2026-03-02"), zone);
        assertThat(spring).isEqualTo(Instant.parse("2026-03-02T05:00:00Z"));
        assertThat(following).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"));
        assertThat(Duration.between(spring, following).toHours()).isEqualTo(167);
        assertThat(WeeklyRotationWeeks.targetIndex(0, 3, spring, following.minusNanos(1), zone)).isEqualTo(1);
        assertThat(WeeklyRotationWeeks.targetIndex(0, 3, spring, following, zone)).isEqualTo(2);

        var fall = WeeklyRotationWeeks.nextMonday(LocalDate.parse("2026-10-25"), zone);
        var nextFall = WeeklyRotationWeeks.nextMonday(LocalDate.parse("2026-10-26"), zone);
        assertThat(Duration.between(fall, nextFall).toHours()).isEqualTo(169);
        assertThat(WeeklyRotationWeeks.targetIndex(2, 3, fall, nextFall, zone)).isEqualTo(1);
    }

    @Test void threeMissedWeeksReturnToTheSamePresetAndNextMondayIsStrictlyLater() {
        var zone = ZoneId.of("Asia/Seoul");
        var due = Instant.parse("2026-11-08T15:00:00Z");
        var now = Instant.parse("2026-11-22T15:00:00Z");
        assertThat(WeeklyRotationWeeks.targetIndex(1, 3, due, now, zone)).isEqualTo(1);
        assertThat(WeeklyRotationWeeks.nextMonday(LocalDate.parse("2026-11-23"), zone))
                .isEqualTo(Instant.parse("2026-11-29T15:00:00Z"));
    }
}
