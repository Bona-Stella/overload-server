package com.overload.overload_server.deload;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static com.overload.overload_server.deload.DeloadAnalysisFailure.Reason.*;

/** Analysis rows join the caller's transaction and owner lock; reads never initialize them. */
@Repository
public class DeloadAnalysisRepository {
    private final JdbcTemplate jdbc;
    public DeloadAnalysisRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void initializeNewAccount(UUID owner,long generation) {
        jdbc.update("""
            INSERT INTO deload_analysis_versions(user_id,data_generation,version,enabled,epoch,recorded_at,epoch_started_at,reason)
            SELECT id,data_generation,0,true,0,created_at,created_at,'INITIAL' FROM users WHERE id=? AND data_generation=?
            """,owner,generation);
    }
    long generation(UUID owner,boolean lock) {
        var values=jdbc.query("SELECT data_generation FROM users WHERE id=?"+(lock?" FOR UPDATE":""),(r,i)->r.getLong(1),owner);
        if(values.isEmpty())throw new DeloadAnalysisFailure(AUTHENTICATION_REQUIRED);
        return values.getFirst();
    }
    Optional<DeloadAnalysisState> find(UUID owner,long generation) {
        return jdbc.query("""
            SELECT * FROM deload_analysis_versions WHERE user_id=? AND data_generation=? ORDER BY version DESC LIMIT 1
            """,(r,i)->new DeloadAnalysisState(r.getLong("version"),r.getBoolean("enabled"),r.getLong("epoch"),
                r.getTimestamp("recorded_at").toInstant(),r.getTimestamp("epoch_started_at").toInstant(),r.getString("time_zone"),r.getString("reason")),owner,generation).stream().findFirst();
    }
    DeloadAnalysisState current(UUID owner,long generation) {
        return find(owner,generation).orElseThrow(()->new DeloadAnalysisFailure(DELOAD_SETTINGS_MISSING));
    }
    List<DeloadAnalysisState> epochHistory(UUID owner,long generation,long epoch) {
        return jdbc.query("SELECT * FROM deload_analysis_versions WHERE user_id=? AND data_generation=? AND epoch=? ORDER BY version",
            (r,i)->new DeloadAnalysisState(r.getLong("version"),r.getBoolean("enabled"),r.getLong("epoch"),r.getTimestamp("recorded_at").toInstant(),
                r.getTimestamp("epoch_started_at").toInstant(),r.getString("time_zone"),r.getString("reason")),owner,generation,epoch);
    }
    String scheduleZone(UUID owner,long generation) {
        return jdbc.query("SELECT time_zone FROM workout_calendar_control WHERE user_id=? AND data_generation=?",(r,i)->r.getString(1),owner,generation).stream().findFirst().orElse(null);
    }
    Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(); }
    Instant createdAt(UUID owner) { return jdbc.queryForObject("SELECT created_at FROM users WHERE id=?",Timestamp.class,owner).toInstant(); }
    void append(UUID owner,long generation,DeloadAnalysisState s) {
        jdbc.update("""
            INSERT INTO deload_analysis_versions(user_id,data_generation,version,enabled,epoch,recorded_at,epoch_started_at,time_zone,reason)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,owner,generation,s.version(),s.enabled(),s.epoch(),Timestamp.from(s.recordedAt()),Timestamp.from(s.epochStartedAt()),s.timeZone(),s.reason());
    }
}
