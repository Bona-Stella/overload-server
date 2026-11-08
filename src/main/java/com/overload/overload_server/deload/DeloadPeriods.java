package com.overload.overload_server.deload;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Authorization boundaries and historical stopped dates for the ordinary training pipeline. */
@Repository
public class DeloadPeriods {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public DeloadPeriods(JdbcTemplate jdbc,ObjectProvider<Clock> clocks){this.jdbc=jdbc;clock=clocks.getIfAvailable(Clock::systemUTC);}
    public boolean generalWriteBlocked(UUID owner,long generation) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM deload_periods WHERE user_id=? AND data_generation=?
              AND (status='ACTIVE' OR status='COMPLETED' AND closed_date=(?::timestamptz AT TIME ZONE time_zone)::date))
            """,Boolean.class,owner,generation,Timestamp.from(clock.instant())));
    }
    public boolean generalStartBlocked(UUID owner,long generation,Instant startedAt) {
        if(generalWriteBlocked(owner,generation))return true;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM deload_periods p CROSS JOIN (SELECT ?::timestamptz AS started) s
              WHERE p.user_id=? AND p.data_generation=? AND (
                p.status='COMPLETED' AND (
                  (s.started AT TIME ZONE p.time_zone)::date BETWEEN p.start_date AND p.closed_date
                  OR p.end_reason='AUTOMATIC_COMPLETION' AND s.started>=p.started_at AND s.started<p.updated_at)
                OR p.status='CANCELLED' AND (
                  s.started<p.closed_at AND (s.started AT TIME ZONE p.time_zone)::date>=p.start_date)))
            """,Boolean.class,Timestamp.from(startedAt),owner,generation));
    }
    public Optional<LocalDate> firstActiveOverlap(UUID owner,long generation,LocalDate from,LocalDate end) {
        if(!from.isBefore(end))return Optional.empty();
        return Optional.ofNullable(jdbc.queryForObject("""
            SELECT min(greatest(start_date,?::date)) FROM deload_periods
            WHERE user_id=? AND data_generation=? AND status='ACTIVE' AND start_date<?
            """,LocalDate.class,from,owner,generation,end));
    }
    public Set<LocalDate> stoppedDates(UUID owner,long generation,LocalDate from,LocalDate end) {
        var days=new HashSet<LocalDate>();
        jdbc.query("SELECT start_date,deload_stopped_through(p) FROM deload_periods p WHERE user_id=? AND data_generation=? AND start_date<? AND (closed_date IS NULL OR deload_stopped_through(p)>=?)",r->{
            var first=r.getObject(1,LocalDate.class);var last=r.getObject(2,LocalDate.class);if(first.isBefore(from))first=from;if(last==null||!last.isBefore(end))last=end.minusDays(1);first.datesUntil(last.plusDays(1)).forEach(days::add);
        },owner,generation,end,from);return days;
    }
    record Protection(Instant normalStartAt) {
        boolean allows(Instant startedAt) {
            return normalStartAt!=null&&!startedAt.isBefore(normalStartAt);
        }
        private Protection merge(Protection other) {
            if(normalStartAt==null||other.normalStartAt==null)return new Protection(null);
            return normalStartAt.isAfter(other.normalStartAt)?this:other;
        }
    }
    Map<LocalDate,Protection> protection(UUID owner,long generation,LocalDate from,LocalDate end) {
        var dates=new HashMap<LocalDate,Protection>();
        jdbc.query("""
            SELECT start_date,deload_stopped_through(p) AS stopped_through,closed_at,closed_date,status,time_zone,end_reason,updated_at
            FROM deload_periods p
            WHERE user_id=? AND data_generation=? AND status<>'ACTIVE' AND start_date<? AND deload_stopped_through(p)>=?
            """,r->{
                var first=r.getObject("start_date",LocalDate.class);
                var last=r.getObject("stopped_through",LocalDate.class);
                var reopened=switch(r.getString("status")) {
                    case "CANCELLED" -> r.getTimestamp("closed_at").toInstant();
                    case "COMPLETED" -> "AUTOMATIC_COMPLETION".equals(r.getString("end_reason"))
                        ?r.getTimestamp("updated_at").toInstant()
                        :r.getObject("closed_date",LocalDate.class).plusDays(1).atStartOfDay(ZoneId.of(r.getString("time_zone"))).toInstant();
                    default -> throw new IllegalStateException("Expected a terminal period");
                };
                if(first.isBefore(from))first=from;
                for(var date=first;!date.isAfter(last)&&date.isBefore(end);date=date.plusDays(1)) {
                    dates.merge(date,new Protection(reopened),Protection::merge);
                }
            },owner,generation,end,from);
        return Map.copyOf(dates);
    }
}
