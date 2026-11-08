package com.overload.overload_server.routine;

import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import static java.time.DayOfWeek.MONDAY;
import static java.time.temporal.TemporalAdjusters.next;
import static java.time.temporal.TemporalAdjusters.previousOrSame;

final class WeeklyRotationWeeks {
    private WeeklyRotationWeeks() { }

    static Instant nextMonday(LocalDate date, ZoneId zone) {
        return date.with(next(MONDAY)).atStartOfDay(zone).toInstant();
    }

    static int targetIndex(int activeIndex, int size, Instant due, Instant now, ZoneId zone) {
        LocalDate first = due.atZone(zone).toLocalDate().with(previousOrSame(MONDAY));
        LocalDate current = now.atZone(zone).toLocalDate().with(previousOrSame(MONDAY));
        long elapsed = ChronoUnit.WEEKS.between(first, current) + 1;
        return Math.floorMod(activeIndex + elapsed, size);
    }
}
