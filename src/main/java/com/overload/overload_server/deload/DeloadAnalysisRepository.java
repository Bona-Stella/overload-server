package com.overload.overload_server.deload;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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
            SELECT * FROM deload_analysis_contexts WHERE user_id=? AND data_generation=? ORDER BY version DESC LIMIT 1
            """,(r,i)->state(r),owner,generation).stream().findFirst();
    }
    DeloadAnalysisState current(UUID owner,long generation) {
        return find(owner,generation).orElseThrow(()->new DeloadAnalysisFailure(DELOAD_SETTINGS_MISSING));
    }
    List<DeloadAnalysisState> epochHistory(UUID owner,long generation,long epoch) {
        return jdbc.query("SELECT * FROM deload_analysis_contexts WHERE user_id=? AND data_generation=? AND epoch=? ORDER BY version",
            (r,i)->state(r),owner,generation,epoch);
    }
    static DeloadAnalysisState state(ResultSet row) throws SQLException {
        return new DeloadAnalysisState(row.getLong("version"),row.getBoolean("enabled"),row.getLong("epoch"),
            row.getTimestamp("recorded_at").toInstant(),row.getTimestamp("epoch_started_at").toInstant(),
            row.getString("time_zone"),row.getString("reason"),row.getObject("resume_date",LocalDate.class));
    }
    Instant nextCompletion(UUID owner,long generation,long epoch) {
        var cutoff=jdbc.queryForObject("""
            SELECT min(epoch_started_at) FROM deload_analysis_versions
            WHERE user_id=? AND data_generation=? AND version>? AND reason='DELOAD_COMPLETED'
            """,Timestamp.class,owner,generation,epoch);
        return cutoff==null?null:cutoff.toInstant();
    }
    String scheduleZone(UUID owner,long generation) {
        return jdbc.query("SELECT time_zone FROM workout_calendar_control WHERE user_id=? AND data_generation=?",(r,i)->r.getString(1),owner,generation).stream().findFirst().orElse(null);
    }
    Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(); }
    Instant createdAt(UUID owner) { return jdbc.queryForObject("SELECT created_at FROM users WHERE id=?",Timestamp.class,owner).toInstant(); }
    void resetAfterCompletion(UUID owner, long generation, UUID period, Instant closedAt, Instant processedAt) {
        var state=current(owner,generation);
        if(!state.enabled())return;
        if(state.version()==Long.MAX_VALUE)throw new DeloadAnalysisFailure(WRITE_CONFLICT);
        long version=state.version()+1;
        var recordedAt=processedAt.isBefore(state.recordedAt())?state.recordedAt():processedAt;
        jdbc.update("""
            INSERT INTO deload_analysis_versions(user_id,data_generation,version,enabled,epoch,recorded_at,epoch_started_at,
                time_zone,reason,completed_period_id)
            VALUES(?,?,?,true,?,?,?,?,'DELOAD_COMPLETED',?)
            """,owner,generation,version,version,Timestamp.from(recordedAt),Timestamp.from(closedAt),state.timeZone(),period);
    }
    void append(UUID owner,long generation,DeloadAnalysisState s) {
        jdbc.update("""
            INSERT INTO deload_analysis_versions(user_id,data_generation,version,enabled,epoch,recorded_at,epoch_started_at,time_zone,reason)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,owner,generation,s.version(),s.enabled(),s.epoch(),Timestamp.from(s.recordedAt()),Timestamp.from(s.epochStartedAt()),s.timeZone(),s.reason());
    }
}
