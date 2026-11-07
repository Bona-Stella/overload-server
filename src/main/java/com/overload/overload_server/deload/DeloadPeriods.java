package com.overload.overload_server.deload;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

/** Period boundaries only. G5-3 owns completion/cancellation and any final resumption semantics. */
@Repository
public class DeloadPeriods {
    private final JdbcTemplate jdbc;
    public DeloadPeriods(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public boolean active(UUID owner,long generation) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deload_periods WHERE user_id=? AND data_generation=? AND status='ACTIVE')",Boolean.class,owner,generation));
    }
    public Optional<LocalDate> firstOverlap(UUID owner,long generation,LocalDate from,LocalDate end) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT min(greatest(start_date,?::date)) FROM deload_periods WHERE user_id=? AND data_generation=? AND start_date<? AND (closed_date IS NULL OR closed_date>=?)",LocalDate.class,from,owner,generation,end,from));
    }
    public Set<LocalDate> stoppedDates(UUID owner,long generation,LocalDate from,LocalDate end) {
        var days=new HashSet<LocalDate>();
        jdbc.query("SELECT start_date,closed_date FROM deload_periods WHERE user_id=? AND data_generation=? AND start_date<? AND (closed_date IS NULL OR closed_date>=?)",r->{
            var first=r.getObject(1,LocalDate.class);var last=r.getObject(2,LocalDate.class);if(first.isBefore(from))first=from;if(last==null||!last.isBefore(end))last=end.minusDays(1);first.datesUntil(last.plusDays(1)).forEach(days::add);
        },owner,generation,end,from);return days;
    }
}
