package com.overload.overload_server.deload;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

/** G3 evidence only: no derived daily store, schedule inference, cursor advancement or dirty clearing. */
@Repository
class DeloadCandidateRepository {
    private final JdbcTemplate jdbc;
    DeloadCandidateRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    Map<LocalDate,DeloadCandidateCalculator.DailyEvidence> daily(UUID owner,long generation,LocalDate from,LocalDate to) {
        var result=new HashMap<LocalDate,DeloadCandidateCalculator.DailyEvidence>();
        jdbc.query("SELECT * FROM daily_training_assessments WHERE user_id=? AND data_generation=? AND workout_date>=? AND workout_date<?",r->{
            var at=r.getTimestamp("reviewed_at");var missed=r.getTimestamp("first_missed_at");
            result.put(r.getObject("workout_date",LocalDate.class),new DeloadCandidateCalculator.DailyEvidence(r.getString("state"),r.getLong("revision"),r.getBoolean("needs_recheck"),
                at==null?null:at.toInstant(),missed==null?null:missed.toInstant(),r.getObject("missed_schedule_version",Long.class),r.getObject("missed_routine_id",UUID.class),r.getString("missed_plan_version")));
        },owner,generation,from,to);
        return Map.copyOf(result);
    }
    LocalDate firstScheduleDate(UUID owner,long generation) {
        return jdbc.queryForObject("SELECT min(effective_from) FROM weekly_schedule_versions WHERE user_id=? AND data_generation=?",LocalDate.class,owner,generation);
    }
}
