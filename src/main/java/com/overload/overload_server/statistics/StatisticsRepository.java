package com.overload.overload_server.statistics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import com.overload.overload_server.workoutsession.InitialPlan;
import com.overload.overload_server.workoutsession.WorkoutSnapshotData;
import java.util.*;
import java.time.*;
import com.overload.overload_server.completion.InitialPlanCompletion.CardioEvidence;
import static com.overload.overload_server.statistics.StatisticsCalculator.*;

/** Fixed-count reads in the same workout transaction, with owner/generation in every fact query. */
@Repository
public class StatisticsRepository {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OWNER_DATES = " ws.user_id=? AND ws.data_generation=? AND ws.workout_date>=? AND ws.workout_date<? AND ws.deleted_at IS NULL ";
    private static final String SCOPE = OWNER_DATES+" AND ws.status IN ('FINISHED','AUTO_CLOSED') ";
    private final JdbcTemplate jdbc;
    StatisticsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    long generation(UUID owner) {
        var rows = jdbc.query("SELECT data_generation FROM users WHERE id=?", (r, i) -> r.getLong(1), owner);
        if (rows.isEmpty()) throw new StatisticsFailure(401, "AUTHENTICATION_REQUIRED");
        return rows.getFirst();
    }
    Input read(UUID owner, long generation, StatisticsQuery period) {return read(owner,generation,period,false);}
    Input readGeneral(UUID owner,long generation,StatisticsQuery period){return read(owner,generation,period,true);}
    private Input read(UUID owner,long generation,StatisticsQuery period,boolean general){
        String scope=SCOPE+(general?" AND ws.deload_period_id IS NULL ":"");
        Object[] args = {owner, generation, period.fromDate(), period.toDateExclusive()};
        var sessions = jdbc.query("SELECT ws.id,ws.workout_date,ws.initial_plan_snapshot::text,ws.applied_plan_snapshot::text,ws.deload_period_id IS NULL FROM workout_sessions ws WHERE " + scope + " ORDER BY ws.started_at DESC,ws.id", (r, i) ->
                new Session(r.getObject(1, UUID.class), r.getObject(2, LocalDate.class), JSON.readValue(r.getString(3), InitialPlan.class), JSON.readValue(r.getString(4), WorkoutSnapshotData.Plan.class),r.getBoolean(5)), args);
        var sets = sets(scope,args);
        var cardio = cardio(scope,args);
        var mappings = jdbc.query("SELECT em.standard_exercise_id,m.id,m.name,m.group_code,em.role FROM exercise_muscle em JOIN muscle m ON m.id=em.muscle_id WHERE m.is_active ORDER BY em.standard_exercise_id,m.id",
                (r, i) -> new Mapping(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getString(3), r.getString(4), r.getString(5)));
        var recent = jdbc.query("""
                SELECT id,initial_plan_snapshot->>'routineName',workout_date,started_at,status FROM workout_sessions
                WHERE user_id=? AND data_generation=? AND status IN ('FINISHED','AUTO_CLOSED') AND deleted_at IS NULL
                ORDER BY started_at DESC,id LIMIT 3
                """, (r, i) -> new StatisticsResponse.Recent(r.getObject(1, UUID.class), r.getString(2), r.getObject(3, LocalDate.class), r.getTimestamp(4).toInstant(), r.getString(5)), owner, generation);
        var categories = new HashMap<UUID, String>();
        if (!cardio.isEmpty()) jdbc.query("SELECT id, category FROM cardio_activity_catalog",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> categories.put(row.getObject(1, UUID.class), row.getString(2)));
        return new Input(sessions, sets, cardio, mappings, recent, categories);
    }
    private List<SetFact> sets(String scope,Object[] args) {
        return jdbc.query("""
                SELECT ps.id,ps.session_id,ps.session_exercise_id,se.standard_exercise_id,coalesce(se.name_at_addition,e.name),ps.weight,ps.reps,ps.load_type,(ps.bodyweight_load->>'additionalWeightKg')::numeric
                FROM workout_sessions ws JOIN performed_sets ps ON ps.session_id=ws.id
                JOIN session_exercises se ON se.id=ps.session_exercise_id AND se.session_id=ws.id AND se.user_id=ws.user_id AND se.data_generation=ws.data_generation
                JOIN standard_exercise e ON e.id=se.standard_exercise_id
                WHERE ps.deleted_at IS NULL AND ps.kind='WEIGHT' AND
                """ + scope + " ORDER BY ps.id", (r, i) -> new SetFact(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getObject(3, UUID.class), r.getObject(4, UUID.class), r.getString(5), r.getBigDecimal(6), r.getInt(7), r.getString(8),r.getBigDecimal(9)), args);
    }
    private List<CardioFact> cardio(String scope,Object[] args) {
        return jdbc.query("SELECT c.session_id,c.session_activity_id,c.active_ms,c.distance,(c.measurement->>'actualSpeedKmh')::numeric,c.measurement->>'distanceSource' FROM workout_sessions ws JOIN cardio_results c ON c.session_id=ws.id WHERE c.deleted_at IS NULL AND c.status='COMPLETED' AND " + scope + " ORDER BY c.id",
                (r, i) -> new CardioFact(r.getObject(1, UUID.class), r.getObject(2, UUID.class), r.getLong(3), r.getBigDecimal(4),r.getBigDecimal(5),r.getString(6)), args);
    }
    /** Raw immutable plan JSON is decoded by the completion consumer so damage can be reported as pending. */
    public record CompletionSession(UUID id,LocalDate date,Instant startedAt,String status,
            long version,long localRevision,String snapshotHash,String initialJson,Map<UUID,Integer> sets,
            Map<UUID,CardioEvidence> cardio) { }
    /** Same fact queries as general statistics, without muscle/recent/schedule reads or a routine restriction. */
    public List<CompletionSession> completionFacts(UUID owner,long generation,LocalDate from,LocalDate to,
            Instant onFrom,Instant onUntil) {
        String scope=OWNER_DATES+" AND ws.status IN ('ACTIVE','FINISHED','AUTO_CLOSED') AND ws.deload_period_id IS NULL AND ws.started_at>=?";
        var params=new ArrayList<Object>(List.of(owner,generation,from,to,java.sql.Timestamp.from(onFrom)));
        if(onUntil!=null){scope+=" AND ws.started_at<?";params.add(java.sql.Timestamp.from(onUntil));}
        var args=params.toArray();
        var counts=new HashMap<UUID,Map<UUID,Integer>>();
        sets(scope,args).forEach(s->counts.computeIfAbsent(s.sessionId(),ignored->new HashMap<>()).merge(s.exerciseId(),1,Integer::sum));
        var activities=new HashMap<UUID,Map<UUID,CardioEvidence>>();
        cardio(scope,args).forEach(c->activities.computeIfAbsent(c.sessionId(),ignored->new HashMap<>()).put(c.activityId(),
                new CardioEvidence(c.activeMs(),c.distance(),c.actualSpeedKmh())));
        return jdbc.query("SELECT ws.id,ws.workout_date,ws.started_at,ws.status,ws.version,ws.local_revision,ws.last_snapshot_hash,ws.initial_plan_snapshot::text FROM workout_sessions ws WHERE "+scope+" ORDER BY ws.workout_date,ws.id",(r,i)-> {
            UUID id=r.getObject(1,UUID.class);
            return new CompletionSession(id,r.getObject(2,LocalDate.class),r.getTimestamp(3).toInstant(),r.getString(4),r.getLong(5),r.getLong(6),r.getString(7),r.getString(8),
                    Map.copyOf(counts.getOrDefault(id,Map.of())),Map.copyOf(activities.getOrDefault(id,Map.of())));
        },args);
    }
}
