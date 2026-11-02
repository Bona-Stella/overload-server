package com.overload.overload_server.statistics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
class GrowthRepository {
    private final JdbcTemplate jdbc;
    GrowthRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    // Existing approval facts are ordinary routine approvals. Deload must never emit these facts.
    private static final String EVIDENCE="""
        WITH bounds AS (SELECT ?::uuid owner,?::bigint generation,?::timestamptz since,?::timestamptz until,?::date from_day,?::date to_day),
        approvals AS (
          SELECT e.*,p.standard_exercise_id FROM progress_change_events e
          JOIN routine_weight_placements p ON (p.user_id,p.data_generation,p.routine_id,p.id)=(e.user_id,e.data_generation,e.routine_id,e.placement_id)
          CROSS JOIN bounds b WHERE e.user_id=b.owner AND e.data_generation=b.generation
          AND e.source IN ('ROUTINE_EDITOR','WORKOUT') AND e.occurred_at<b.until AND (b.since IS NULL OR e.occurred_at>=b.since)
        ), performed AS (
          SELECT se.standard_exercise_id,coalesce(se.name_at_addition,
            (SELECT item->>'exerciseName' FROM jsonb_array_elements(ws.initial_plan_snapshot->'weightExercises') item
             WHERE item->>'sessionExerciseId'=se.id::text LIMIT 1),s.name) name,ws.started_at FROM session_exercises se
          JOIN workout_sessions ws ON (ws.id,ws.user_id,ws.data_generation)=(se.session_id,se.user_id,se.data_generation)
          JOIN standard_exercise s ON s.id=se.standard_exercise_id CROSS JOIN bounds b
          WHERE ws.user_id=b.owner AND ws.data_generation=b.generation AND ws.status IN ('FINISHED','AUTO_CLOSED') AND ws.deleted_at IS NULL
          AND ws.workout_date<b.to_day AND (b.from_day IS NULL OR ws.workout_date>=b.from_day)
          AND EXISTS(SELECT 1 FROM performed_sets ps WHERE ps.session_id=ws.id AND ps.session_exercise_id=se.id AND ps.deleted_at IS NULL AND ps.kind='WEIGHT')
        )
        """;
    List<GrowthResponse.Exercise> search(UUID owner,long generation,GrowthQuery query,GrowthQuery.Range range) {
        var args=args(owner,generation,range);args.add(query.search());args.add(query.offset());
        return jdbc.query(EVIDENCE+"""
            , facts AS (
              SELECT standard_exercise_id,name,started_at performed_at,NULL::timestamptz approval_at FROM performed
              UNION ALL SELECT standard_exercise_id,exercise_name,NULL,occurred_at FROM approvals
            ), grouped AS (
              SELECT standard_exercise_id,string_agg(name,' ') aliases,max(performed_at) last_performed_at,max(approval_at) last_approval_at
              FROM facts GROUP BY standard_exercise_id
            ) SELECT s.id,s.name,g.last_performed_at,g.last_approval_at FROM grouped g JOIN standard_exercise s ON s.id=g.standard_exercise_id
            WHERE strpos(lower(s.name||' '||g.aliases),lower(?))>0
            ORDER BY g.last_performed_at DESC NULLS LAST,g.last_approval_at DESC NULLS LAST,s.id LIMIT 51 OFFSET ?
            """,(r,i)->new GrowthResponse.Exercise(r.getObject(1,UUID.class),r.getString(2),instant(r,3),instant(r,4)),args.toArray());
    }
    String knownExercise(UUID owner,long generation,UUID standard) {
        // Ownership evidence may be outside the selected range; an empty range is still a valid empty chart.
        var all=new GrowthQuery.Range(null,LocalDate.of(9999,12,31),"UTC");var args=args(owner,generation,all);args.add(standard);
        var rows=jdbc.query(EVIDENCE+"SELECT name FROM standard_exercise s WHERE s.id=? AND (EXISTS(SELECT 1 FROM performed WHERE standard_exercise_id=s.id) OR EXISTS(SELECT 1 FROM approvals WHERE standard_exercise_id=s.id))",(r,i)->r.getString(1),args.toArray());
        if(rows.isEmpty())throw new StatisticsFailure(404,"GROWTH_EXERCISE_NOT_FOUND");return rows.getFirst();
    }
    List<GrowthResponse.Placement> placements(UUID owner,long generation,UUID standard,GrowthQuery.Range range) {
        var args=args(owner,generation,range);args.add(standard);
        return jdbc.query(EVIDENCE+"""
            SELECT a.routine_id,a.placement_id,r.name,(r.deleted_at IS NOT NULL OR p.deleted_at IS NOT NULL),max(a.occurred_at)
            FROM approvals a JOIN routines r ON (r.user_id,r.data_generation,r.id)=(a.user_id,a.data_generation,a.routine_id)
            JOIN routine_weight_placements p ON p.id=a.placement_id WHERE a.standard_exercise_id=?
            GROUP BY a.routine_id,a.placement_id,r.name,r.deleted_at,p.deleted_at ORDER BY max(a.occurred_at) DESC,a.placement_id
            """,(r,i)->new GrowthResponse.Placement(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getBoolean(4),instant(r,5)),args.toArray());
    }
    List<GrowthResponse.Point> points(UUID owner,long generation,UUID standard,UUID placement,GrowthQuery query,GrowthQuery.Range range) {
        var args=args(owner,generation,range);args.add(standard);args.add(placement);args.add(query.offset());
        return jdbc.query(EVIDENCE+"""
            , chain AS (
              SELECT a.*,lag(id) OVER w prior_id,lag(next_weight) OVER w prior_weight,lag(occurred_at) OVER w prior_occurred,lag(recorded_at) OVER w prior_recorded
              FROM approvals a WHERE a.standard_exercise_id=? AND a.placement_id=? WINDOW w AS (ORDER BY occurred_at,id)
            ) SELECT id,previous_weight,next_weight,occurred_at,recorded_at,source,session_id,
              coalesce(previous_weight=prior_weight AND occurred_at>prior_occurred AND recorded_at>prior_recorded,false),prior_id
            FROM chain ORDER BY occurred_at DESC,id DESC LIMIT 101 OFFSET ?
            """,(r,i)->new GrowthResponse.Point(r.getObject(1,UUID.class),decimal(r,2),decimal(r,3),instant(r,4),instant(r,5),r.getString(6),r.getObject(7,UUID.class),r.getBoolean(8),r.getObject(9,UUID.class)),args.toArray());
    }
    private static ArrayList<Object> args(UUID owner,long generation,GrowthQuery.Range range){return new ArrayList<>(Arrays.asList(range.parameters(owner,generation)));}
    private static Instant instant(ResultSet r,int column)throws SQLException{var value=r.getTimestamp(column);return value==null?null:value.toInstant();}
    private static String decimal(ResultSet r,int column)throws SQLException{return r.getBigDecimal(column).stripTrailingZeros().toPlainString();}
}
