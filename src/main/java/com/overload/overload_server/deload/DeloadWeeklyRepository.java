package com.overload.overload_server.deload;

import com.overload.overload_server.completion.ExactFraction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** V25/V26 weekly rows are the sole progress/result store. Every write joins the existing owner-locked workout transaction. */
@Repository
public class DeloadWeeklyRepository {
    private final JdbcTemplate jdbc;
    public DeloadWeeklyRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    record Owner(UUID id,long generation) { }
    record Row(LocalDate week,String status,boolean dirty,long revision,int rule,ExactFraction e,Integer n,
            String operation,ExactFraction contribution,String input) {
        boolean resolved() {return status.equals("CONFIRMED")||status.equals("EXCLUDED");}
    }
    record EpochState(DeloadAnalysisState settings,Instant attempted) { }
    List<EpochState> epochs(UUID owner,long generation) {
        return jdbc.query("""
            WITH c AS (SELECT DISTINCT ON(epoch) * FROM deload_analysis_versions WHERE user_id=? AND data_generation=? ORDER BY epoch,version DESC),
            attempts AS (SELECT epoch,max(updated_at) attempted FROM deload_weekly_assessments WHERE user_id=? AND data_generation=? GROUP BY epoch)
            SELECT c.*,coalesce(a.attempted,c.epoch_started_at) attempted FROM c LEFT JOIN attempts a ON a.epoch=c.epoch
            """,(r,i)->new EpochState(new DeloadAnalysisState(r.getLong("version"),r.getBoolean("enabled"),r.getLong("epoch"),r.getTimestamp("recorded_at").toInstant(),r.getTimestamp("epoch_started_at").toInstant(),r.getString("time_zone"),r.getString("reason")),r.getTimestamp("attempted").toInstant()),owner,generation,owner,generation);
    }
    private static final String ACTIVITY="""
        (EXISTS(SELECT 1 FROM performed_sets p WHERE p.session_id=ws.id AND p.kind='WEIGHT' AND p.deleted_at IS NULL)
         OR EXISTS(SELECT 1 FROM cardio_results a WHERE a.session_id=ws.id AND a.status='COMPLETED' AND a.deleted_at IS NULL AND a.active_ms>0))
        """;
    // An earlier travel-date ACTIVE can still change the first published week. It blocks; it never counts as performance.
    private static final String EARLY_CANDIDATE="(ws.status='ACTIVE' OR ws.status IN('FINISHED','AUTO_CLOSED') AND "+ACTIVITY+")";
    private static final String CHANGED="""
        (w.rule_version<>1 OR w.status<>'EXCLUDED' AND EXISTS(SELECT 1 FROM deload_periods p JOIN deload_analysis_versions v ON v.user_id=w.user_id AND v.data_generation=w.data_generation AND v.version=w.epoch
          WHERE p.user_id=w.user_id AND p.data_generation=w.data_generation AND p.start_date<w.week_start+7
            AND (p.closed_date IS NULL OR p.closed_date>=greatest(w.week_start,(v.epoch_started_at AT TIME ZONE v.time_zone)::date))) OR CASE WHEN w.input_revision IS JSON OBJECT THEN
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
            contexts AS (SELECT DISTINCT ON(v.user_id,v.data_generation,v.epoch) v.* FROM deload_analysis_versions v
              JOIN latest l ON l.user_id=v.user_id AND l.data_generation=v.data_generation
              WHERE l.enabled AND l.time_zone IS NOT NULL ORDER BY v.user_id,v.data_generation,v.epoch,v.version DESC),
            eligible AS (SELECT c.*,
              least(date_trunc('week',c.epoch_started_at AT TIME ZONE c.time_zone)::date,
                (SELECT min(date_trunc('week',ws.workout_date)::date) FROM workout_sessions ws
                  WHERE ws.user_id=c.user_id AND ws.data_generation=c.data_generation AND ws.started_at>=c.epoch_started_at
                  AND(c.enabled OR ws.started_at<c.recorded_at)
                  AND ws.workout_date<date_trunc('week',c.epoch_started_at AT TIME ZONE c.time_zone)::date
                  AND ws.deleted_at IS NULL AND ws.deload_period_id IS NULL AND
            """+EARLY_CANDIDATE+"""
                ),summary.first_week) first_week,
              CASE WHEN c.epoch=l.epoch THEN
                (date_trunc('week',(?::timestamptz AT TIME ZONE c.time_zone)-interval '4 hours')-interval '7 days')::date
                WHEN summary.published IS NOT NULL THEN least(summary.published,
                  (date_trunc('week',(?::timestamptz AT TIME ZONE c.time_zone)-interval '4 hours')-interval '7 days')::date) END last_week,
              coalesce(summary.attempt,c.epoch_started_at) attempt
              FROM contexts c JOIN latest l ON l.user_id=c.user_id AND l.data_generation=c.data_generation
              LEFT JOIN LATERAL (SELECT min(w.week_start) first_week,
                max(w.week_start) FILTER(WHERE w.status IN('CONFIRMED','EXCLUDED')) published,max(w.updated_at) attempt
                FROM deload_weekly_assessments w WHERE w.user_id=c.user_id AND w.data_generation=c.data_generation AND w.epoch=c.epoch) summary ON true
              WHERE c.time_zone IS NOT NULL)
            SELECT e.user_id,e.data_generation FROM eligible e WHERE e.first_week<=e.last_week
              AND (EXISTS(SELECT 1 FROM (
                  SELECT e.first_week AS candidate_date UNION ALL SELECT w.week_start+7 FROM deload_weekly_assessments w
                    WHERE w.user_id=e.user_id AND w.data_generation=e.data_generation AND w.epoch=e.epoch
                      AND w.status IN('CONFIRMED','EXCLUDED') AND w.week_start>=e.first_week AND w.week_start<e.last_week) c
                  WHERE c.candidate_date<=e.last_week AND NOT EXISTS(SELECT 1 FROM deload_weekly_assessments w
                    WHERE w.user_id=e.user_id AND w.data_generation=e.data_generation AND w.epoch=e.epoch AND w.week_start=c.candidate_date
                      AND w.status IN('CONFIRMED','EXCLUDED') AND NOT w.needs_recheck))
                OR EXISTS(SELECT 1 FROM deload_weekly_assessments w WHERE w.user_id=e.user_id AND w.data_generation=e.data_generation
                  AND w.epoch=e.epoch AND w.week_start<=e.last_week AND w.status IN('CONFIRMED','EXCLUDED') AND
            """+CHANGED+"""
                ))
            GROUP BY e.user_id,e.data_generation ORDER BY min(e.attempt),e.user_id LIMIT 20
            """;
        return jdbc.query(sql,(r,i)->new Owner(r.getObject(1,UUID.class),r.getLong(2)),Timestamp.from(now),Timestamp.from(now));
    }
    boolean lock(UUID owner,long generation,boolean skipBusy) {
        return !jdbc.query("SELECT id FROM users WHERE id=? AND data_generation=? FOR UPDATE"+(skipBusy?" SKIP LOCKED":""),(r,i)->r.getObject(1,UUID.class),owner,generation).isEmpty();
    }
    List<Row> rows(UUID owner,long generation,long epoch) {
        return jdbc.query("SELECT * FROM deload_weekly_assessments WHERE user_id=? AND data_generation=? AND epoch=? ORDER BY week_start",(r,i)->row(r),owner,generation,epoch);
    }
    /** Snapshot-local invalidation only. GET never persists dirty markers or recalculates workout completion. */
    List<Row> auditedRows(UUID owner,long generation,long epoch) {
        return jdbc.query("SELECT w.*,(w.needs_recheck OR (w.status IN('CONFIRMED','EXCLUDED') AND "+CHANGED+")) effective_dirty FROM deload_weekly_assessments w WHERE w.user_id=? AND w.data_generation=? AND w.epoch=? ORDER BY w.week_start",(r,i)->{
            var row=row(r);return new Row(row.week(),row.status(),r.getBoolean("effective_dirty"),row.revision(),row.rule(),row.e(),row.n(),row.operation(),row.contribution(),row.input());
        },owner,generation,epoch);
    }
    Map<Long,TreeMap<LocalDate,Row>> allRows(UUID owner,long generation) {
        var result=new HashMap<Long,TreeMap<LocalDate,Row>>();
        jdbc.query("SELECT * FROM deload_weekly_assessments WHERE user_id=? AND data_generation=? ORDER BY epoch,week_start",r->{result.computeIfAbsent(r.getLong("epoch"),ignored->new TreeMap<>()).put(r.getObject("week_start",LocalDate.class),row(r));},owner,generation);
        return result;
    }
    Map<Long,LocalDate> earlierWeeks(UUID owner,long generation) {
        return earlierWeeks(owner,generation,null);
    }
    Map<Long,LocalDate> earlierWeeks(UUID owner,long generation,Long epoch) {
        var result=new HashMap<Long,LocalDate>();
        jdbc.query("""
            WITH c AS (SELECT DISTINCT ON(epoch) * FROM deload_analysis_versions WHERE user_id=? AND data_generation=? ORDER BY epoch,version DESC)
            SELECT c.epoch,min(ws.workout_date) AS observed_date FROM c JOIN workout_sessions ws ON ws.user_id=c.user_id AND ws.data_generation=c.data_generation
              AND ws.started_at>=c.epoch_started_at AND(c.enabled OR ws.started_at<c.recorded_at)
              AND ws.workout_date<date_trunc('week',c.epoch_started_at AT TIME ZONE c.time_zone)::date
            WHERE c.time_zone IS NOT NULL AND (?::bigint IS NULL OR c.epoch=?) AND ws.deleted_at IS NULL AND ws.deload_period_id IS NULL AND
            """+EARLY_CANDIDATE+" GROUP BY c.epoch",r->{result.put(r.getLong("epoch"),DeloadWeeklyPolicy.monday(r.getObject("observed_date",LocalDate.class)));},owner,generation,epoch,epoch);
        return result;
    }
    private static Row row(ResultSet r)throws SQLException {
        return new Row(r.getObject("week_start",LocalDate.class),r.getString("status"),r.getBoolean("needs_recheck"),r.getLong("revision"),r.getInt("rule_version"),fraction(r,"e"),r.getObject("n",Integer.class),r.getString("operation"),fraction(r,"contribution"),r.getString("input_revision"));
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
        if(row.resolved()) {
            changed(jdbc.update("UPDATE deload_weekly_assessments SET needs_recheck=true,updated_at=greatest(updated_at,?) WHERE user_id=? AND data_generation=? AND epoch=? AND week_start=? AND revision=?",Timestamp.from(now),owner,generation,epoch,row.week(),row.revision()));
            return;
        }
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
            WHERE user_id=? AND data_generation=? AND epoch=? AND week_start=? AND (status='PENDING' OR status IN('CONFIRMED','EXCLUDED') AND needs_recheck) AND revision=?
            """,result.e().numerator(),result.e().denominator(),result.n(),decision.operation(),decision.contribution().numerator(),decision.contribution().denominator(),input,Timestamp.from(now),Timestamp.from(now),owner,generation,epoch,row.week(),row.revision()));
    }
    private void changed(int count) {if(count!=1)throw new DeloadAnalysisFailure(DeloadAnalysisFailure.Reason.WRITE_CONFLICT);}
    void exclude(UUID owner,long generation,long epoch,Row row,String input,Instant now) {
        changed(jdbc.update("""
            UPDATE deload_weekly_assessments SET status='EXCLUDED',e_numerator=NULL,e_denominator=NULL,n=NULL,operation=NULL,
              contribution_numerator=NULL,contribution_denominator=NULL,rule_version=1,input_revision=?,needs_recheck=false,
              confirmed_at=?,updated_at=?,revision=revision+1
            WHERE user_id=? AND data_generation=? AND epoch=? AND week_start=?
              AND (status='PENDING' OR status IN('CONFIRMED','EXCLUDED') AND needs_recheck) AND revision=?
            """,input,Timestamp.from(now),Timestamp.from(now),owner,generation,epoch,row.week(),row.revision()));
    }
    void markSuffix(UUID owner,long generation,long epoch,LocalDate from,LocalDate through,Instant now) {
        jdbc.update("UPDATE deload_weekly_assessments SET needs_recheck=true,revision=revision+1,updated_at=greatest(updated_at,?) WHERE user_id=? AND data_generation=? AND epoch=? AND week_start>=? AND week_start<=? AND NOT needs_recheck",Timestamp.from(now),owner,generation,epoch,from,through);
    }
    void clearUnchanged(UUID owner,long generation,long epoch,Row row,Instant now) {
        changed(jdbc.update("UPDATE deload_weekly_assessments SET needs_recheck=false,revision=revision+1,updated_at=greatest(updated_at,?) WHERE user_id=? AND data_generation=? AND epoch=? AND week_start=? AND revision=? AND needs_recheck AND status IN('CONFIRMED','EXCLUDED')",Timestamp.from(now),owner,generation,epoch,row.week(),row.revision()));
    }
    void invalidateSources(UUID owner,long generation,Long epoch,Instant now) {
        jdbc.update("UPDATE deload_weekly_assessments w SET needs_recheck=true,revision=revision+1,updated_at=greatest(updated_at,?) WHERE user_id=? AND data_generation=? AND (?::bigint IS NULL OR epoch=?) AND status IN('CONFIRMED','EXCLUDED') AND NOT needs_recheck AND "+CHANGED,Timestamp.from(now),owner,generation,epoch,epoch);
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
