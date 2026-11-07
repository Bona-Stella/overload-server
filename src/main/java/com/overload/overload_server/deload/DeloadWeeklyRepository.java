package com.overload.overload_server.deload;

import com.overload.overload_server.completion.ExactFraction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** V25 is the sole progress/result store. Every write joins the existing owner-locked workout transaction. */
@Repository
public class DeloadWeeklyRepository {
    private final JdbcTemplate jdbc;
    public DeloadWeeklyRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    record Owner(UUID id,long generation) { }
    record Row(LocalDate week,String status,boolean dirty,long revision,int rule,ExactFraction e,Integer n,
            String operation,ExactFraction contribution,String input) { }
    private static final String ACTIVITY="""
        (EXISTS(SELECT 1 FROM performed_sets p WHERE p.session_id=ws.id AND p.kind='WEIGHT' AND p.deleted_at IS NULL)
         OR EXISTS(SELECT 1 FROM cardio_results a WHERE a.session_id=ws.id AND a.status='COMPLETED' AND a.deleted_at IS NULL AND a.active_ms>0))
        """;
    private static final String CHANGED="""
        (w.rule_version<>1 OR CASE WHEN w.input_revision IS JSON OBJECT THEN
          coalesce(w.input_revision::jsonb->>'hash','') !~ '^[0-9a-f]{64}$'
          OR coalesce(jsonb_typeof(w.input_revision::jsonb->'revisions'),'')<>'object'
          OR EXISTS(SELECT 1 FROM daily_training_assessments d WHERE d.user_id=w.user_id AND d.data_generation=w.data_generation
            AND d.workout_date>=w.week_start AND d.workout_date<w.week_start+7
            AND d.revision::text IS DISTINCT FROM coalesce((w.input_revision::jsonb->'revisions')->>d.workout_date::text,'0'))
        ELSE true END)
        """;
    List<Owner> targets(Instant now) {
        String sql="""
            WITH latest AS (SELECT DISTINCT ON(v.user_id,v.data_generation) v.* FROM deload_analysis_versions v
              JOIN users u ON u.id=v.user_id AND u.data_generation=v.data_generation ORDER BY v.user_id,v.data_generation,v.version DESC),
            eligible AS (SELECT l.*,
              least(date_trunc('week',l.epoch_started_at AT TIME ZONE l.time_zone)::date,
                (SELECT min(date_trunc('week',ws.workout_date)::date) FROM workout_sessions ws
                  WHERE ws.user_id=l.user_id AND ws.data_generation=l.data_generation AND ws.started_at>=l.epoch_started_at
                  AND ws.workout_date<date_trunc('week',l.epoch_started_at AT TIME ZONE l.time_zone)::date
                  AND ws.deleted_at IS NULL AND ws.status IN('FINISHED','AUTO_CLOSED') AND
            """+ACTIVITY+"""
                ),(SELECT min(w.week_start) FROM deload_weekly_assessments w WHERE w.user_id=l.user_id
                    AND w.data_generation=l.data_generation AND w.epoch=l.epoch)) first_week,
              (date_trunc('week',(?::timestamptz AT TIME ZONE l.time_zone)-interval '4 hours')-interval '7 days')::date last_week
              FROM latest l WHERE l.enabled AND l.time_zone IS NOT NULL)
            SELECT e.user_id,e.data_generation FROM eligible e
            WHERE e.first_week<=e.last_week
              AND NOT EXISTS(SELECT 1 FROM deload_weekly_assessments w WHERE w.user_id=e.user_id AND w.data_generation=e.data_generation
                AND w.epoch=e.epoch AND w.status='CONFIRMED' AND w.needs_recheck)
              AND (EXISTS(SELECT 1 FROM (
                  SELECT e.first_week AS day UNION ALL SELECT w.week_start+7 FROM deload_weekly_assessments w
                    WHERE w.user_id=e.user_id AND w.data_generation=e.data_generation AND w.epoch=e.epoch AND w.status='CONFIRMED'
                      AND w.week_start>=e.first_week AND w.week_start<e.last_week) c
                  WHERE c.day<=e.last_week AND NOT EXISTS(SELECT 1 FROM deload_weekly_assessments w
                    WHERE w.user_id=e.user_id AND w.data_generation=e.data_generation AND w.epoch=e.epoch AND w.week_start=c.day AND w.status='CONFIRMED'))
                OR EXISTS(SELECT 1 FROM deload_weekly_assessments w WHERE w.user_id=e.user_id AND w.data_generation=e.data_generation
                  AND w.epoch=e.epoch AND w.status='CONFIRMED' AND
            """+CHANGED+"""
                ))
            ORDER BY coalesce((SELECT max(w.updated_at) FROM deload_weekly_assessments w WHERE w.user_id=e.user_id
              AND w.data_generation=e.data_generation AND w.epoch=e.epoch),e.epoch_started_at),e.user_id LIMIT 20
            """;
        return jdbc.query(sql,(r,i)->new Owner(r.getObject(1,UUID.class),r.getLong(2)),Timestamp.from(now));
    }
    boolean lock(UUID owner,long generation,boolean skipBusy) {
        return !jdbc.query("SELECT id FROM users WHERE id=? AND data_generation=? FOR UPDATE"+(skipBusy?" SKIP LOCKED":""),(r,i)->r.getObject(1,UUID.class),owner,generation).isEmpty();
    }
    LocalDate firstWeek(UUID owner,long generation,DeloadAnalysisState state) {
        var first=DeloadWeeklyPolicy.monday(state.epochStartedAt().atZone(ZoneId.of(state.timeZone())).toLocalDate());
        var earlier=jdbc.queryForObject("SELECT min(ws.workout_date) FROM workout_sessions ws WHERE ws.user_id=? AND ws.data_generation=? AND ws.workout_date<? AND ws.started_at>=? AND ws.deleted_at IS NULL AND ws.status IN('FINISHED','AUTO_CLOSED') AND "+ACTIVITY,
            LocalDate.class,owner,generation,first,Timestamp.from(state.epochStartedAt()));
        if(earlier!=null)first=DeloadWeeklyPolicy.monday(earlier);
        var stored=jdbc.queryForObject("SELECT min(week_start) FROM deload_weekly_assessments WHERE user_id=? AND data_generation=? AND epoch=?",LocalDate.class,owner,generation,state.epoch());
        return stored!=null&&stored.isBefore(first)?stored:first;
    }
    List<Row> rows(UUID owner,long generation,long epoch) {
        return jdbc.query("SELECT * FROM deload_weekly_assessments WHERE user_id=? AND data_generation=? AND epoch=? ORDER BY week_start",(r,i)->new Row(r.getObject("week_start",LocalDate.class),r.getString("status"),r.getBoolean("needs_recheck"),r.getLong("revision"),r.getInt("rule_version"),fraction(r,"e"),r.getObject("n",Integer.class),r.getString("operation"),fraction(r,"contribution"),r.getString("input_revision")),owner,generation,epoch);
    }
    private static ExactFraction fraction(ResultSet r,String prefix)throws SQLException {
        var n=r.getBigDecimal(prefix+"_numerator");var d=r.getBigDecimal(prefix+"_denominator");
        return n==null?null:new ExactFraction(n.toBigIntegerExact(),d.toBigIntegerExact());
    }
    void reserve(UUID owner,long generation,long epoch,LocalDate week,Instant now) {
        jdbc.update("""
            INSERT INTO deload_weekly_assessments(user_id,data_generation,epoch,week_start,status,rule_version,revision,input_revision,updated_at)
            VALUES(?,?,?,?,'PENDING',1,0,'{"hash":"PENDING","revisions":{}}',?) ON CONFLICT DO NOTHING
            """,owner,generation,epoch,week,Timestamp.from(now));
    }
    void pending(UUID owner,long generation,long epoch,Row row,String input,boolean dirty,Instant now) {
        changed(jdbc.update("""
            UPDATE deload_weekly_assessments SET input_revision=?,needs_recheck=?,revision=revision+1,updated_at=?
            WHERE user_id=? AND data_generation=? AND epoch=? AND week_start=? AND status='PENDING' AND revision=?
            """,input,dirty,Timestamp.from(now),owner,generation,epoch,row.week(),row.revision()));
    }
    void confirm(UUID owner,long generation,long epoch,Row row,DeloadCandidateCalculator.Week result,String input,DeloadWeeklyPolicy.Decision decision,Instant now) {
        changed(jdbc.update("""
            UPDATE deload_weekly_assessments SET status='CONFIRMED',e_numerator=?,e_denominator=?,n=?,operation=?,
              contribution_numerator=?,contribution_denominator=?,rule_version=1,input_revision=?,needs_recheck=false,
              confirmed_at=?,updated_at=?,revision=revision+1
            WHERE user_id=? AND data_generation=? AND epoch=? AND week_start=? AND status='PENDING' AND revision=?
            """,result.e().numerator(),result.e().denominator(),result.n(),decision.operation(),decision.contribution().numerator(),decision.contribution().denominator(),input,Timestamp.from(now),Timestamp.from(now),owner,generation,epoch,row.week(),row.revision()));
    }
    private void changed(int count) {if(count!=1)throw new DeloadAnalysisFailure(DeloadAnalysisFailure.Reason.WRITE_CONFLICT);}
    void invalidateSources(UUID owner,long generation,long epoch,Instant now) {
        jdbc.update("UPDATE deload_weekly_assessments w SET needs_recheck=true,revision=revision+1,updated_at=greatest(updated_at,?) WHERE user_id=? AND data_generation=? AND epoch=? AND status='CONFIRMED' AND NOT needs_recheck AND "+CHANGED,Timestamp.from(now),owner,generation,epoch);
    }
    /** Must run before G3 clears source dirty in the SAME owner-locked transaction. No derived score is recalculated. */
    @Transactional(transactionManager="workoutTransactionManager",propagation=Propagation.MANDATORY)
    public void preserveDirtyWeeks(UUID owner,long generation,LocalDate from,LocalDate to,Instant now) {
        jdbc.update("""
            UPDATE deload_weekly_assessments w SET needs_recheck=true,revision=revision+1,updated_at=greatest(updated_at,?)
            WHERE w.user_id=? AND w.data_generation=? AND NOT w.needs_recheck AND EXISTS(
              SELECT 1 FROM daily_training_assessments d WHERE d.user_id=w.user_id AND d.data_generation=w.data_generation
              AND d.workout_date>=? AND d.workout_date<? AND d.workout_date>=w.week_start AND d.workout_date<w.week_start+7 AND d.needs_recheck)
            """,Timestamp.from(now),owner,generation,from,to);
    }
}
