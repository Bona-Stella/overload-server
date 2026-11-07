package com.overload.overload_server.deload;

import com.overload.overload_server.routine.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.overload.overload_server.deload.DeloadPlan.*;

@Repository
class DeloadPeriodRepository {
    static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final RoutineService routines;private final RoutineRepository routineData;private final DeloadAnalysisRepository analysis;
    DeloadPeriodRepository(JdbcTemplate jdbc,RoutineService routines,RoutineRepository routineData,DeloadAnalysisRepository analysis){this.jdbc=jdbc;this.routines=routines;this.routineData=routineData;this.analysis=analysis;}
    record Eligibility(boolean eligible,String timeZone,LocalDate today,boolean recentActual,boolean afterPreviousCompletion,boolean todayClear,boolean activeSessionClear,List<String> reasons,String revision){}
    record Stored(UUID id,UUID userId,String dataGeneration,String version,String status,Instant startedAt,LocalDate startDate,String timeZone,LocalDate recommendedEndDate,LocalDate maximumEndDate,Method method,Split split,String sourceHash,Source source,List<SavedPlan> plans){}
    record SavedPlan(UUID id,int position,Plan plan){}
    record Request(String hash,Stored period){}
    long generation(UUID owner,boolean lock){return analysis.generation(owner,lock);}
    String zone(UUID owner,long generation){return analysis.current(owner,generation).timeZone();}
    Eligibility eligibility(UUID owner,long generation,Instant now) {
        String zone=zone(owner,generation);if(zone==null)return new Eligibility(false,null,null,false,false,false,false,List.of("AWAITING_TIME_ZONE"),"UNAVAILABLE");
        var today=now.atZone(ZoneId.of(zone)).toLocalDate();
        record Completion(UUID id,Instant at,LocalDate date,long version){}
        var completed=jdbc.query("SELECT id,closed_at,closed_date,version FROM deload_periods WHERE user_id=? AND data_generation=? AND status='COMPLETED' ORDER BY closed_at DESC,id DESC LIMIT 1",(r,i)->new Completion(r.getObject(1,UUID.class),r.getTimestamp(2).toInstant(),r.getObject(3,LocalDate.class),r.getLong(4)),owner,generation).stream().findFirst().orElse(null);
        var actual=jdbc.query("""
            SELECT ws.workout_date,ws.id,ws.version,(SELECT max(p.completed_at) FROM performed_sets p WHERE p.session_id=ws.id AND p.kind='WEIGHT' AND p.deleted_at IS NULL) weight_at,
              (SELECT max(c.occurred_at) FROM cardio_results c WHERE c.session_id=ws.id AND c.deleted_at IS NULL AND c.status='COMPLETED' AND c.active_ms>0) cardio_at
            FROM workout_sessions ws WHERE ws.user_id=? AND ws.data_generation=? AND ws.deleted_at IS NULL AND ws.deload_period_id IS NULL
              AND ws.status IN('ACTIVE','FINISHED','AUTO_CLOSED') AND ws.workout_date>=? AND ws.workout_date<=?
              AND (EXISTS(SELECT 1 FROM performed_sets p WHERE p.session_id=ws.id AND p.kind='WEIGHT' AND p.deleted_at IS NULL)
                OR EXISTS(SELECT 1 FROM cardio_results c WHERE c.session_id=ws.id AND c.deleted_at IS NULL AND c.status='COMPLETED' AND c.active_ms>0)) ORDER BY ws.id
            """,(r,i)->Arrays.asList(r.getObject("workout_date",LocalDate.class),r.getObject("id",UUID.class),r.getLong("version"),instant(r,"weight_at"),instant(r,"cardio_at")),owner,generation,today.minusDays(7),today);
        boolean recent=actual.stream().anyMatch(r->((LocalDate)r.getFirst()).isBefore(today));
        boolean todayClear=actual.stream().noneMatch(r->r.getFirst().equals(today));
        boolean after=completed==null||actual.stream().anyMatch(r->(r.get(3)!=null&&((Instant)r.get(3)).isAfter(completed.at())||r.get(4)!=null&&((Instant)r.get(4)).isAfter(completed.at())));
        var active=jdbc.queryForList("SELECT id FROM workout_sessions WHERE user_id=? AND data_generation=? AND status='ACTIVE' AND deload_period_id IS NULL ORDER BY id",UUID.class,owner,generation);
        var revisions=jdbc.queryForList("SELECT workout_date,revision FROM daily_training_assessments WHERE user_id=? AND data_generation=? AND workout_date>=? AND workout_date<=? ORDER BY workout_date",owner,generation,today.minusDays(7),today);
        var reasons=new ArrayList<String>();if(!recent)reasons.add("RECENT_GENERAL_ACTIVITY_REQUIRED");if(!after)reasons.add("POST_COMPLETION_ACTIVITY_REQUIRED");if(!todayClear)reasons.add("TODAY_GENERAL_ACTIVITY");if(!active.isEmpty())reasons.add("ACTIVE_GENERAL_SESSION");
        String revision=DeloadPeriodService.hash(Arrays.asList(today,actual,active,revisions,completed));
        return new Eligibility(reasons.isEmpty(),zone,today,recent,after,todayClear,active.isEmpty(),List.copyOf(reasons),revision);
    }
    private static Instant instant(ResultSet row,String column)throws SQLException {var at=row.getTimestamp(column);return at==null?null:at.toInstant();}
    Source source(UUID owner,long generation,Eligibility eligibility,boolean lock) {
        String zone=eligibility.timeZone();if(zone==null)throw DeloadPeriodFailure.conflict("AWAITING_TIME_ZONE");
        record Schedule(String version,List<WeeklySchedule.Assignment> assignments){}
        var schedule=jdbc.query("SELECT v.version,v.assignments::text,c.time_zone FROM weekly_schedule_versions v JOIN workout_calendar_control c ON c.user_id=v.user_id AND c.data_generation=v.data_generation WHERE v.user_id=? AND v.data_generation=? AND v.version>=c.baseline_version ORDER BY v.version DESC LIMIT 1",(r,i)->{
            if(!zone.equals(r.getString(3)))throw DeloadPeriodFailure.conflict("DELOAD_TIME_ZONE_CONFLICT");return new Schedule(r.getString(1),List.of(JSON.readValue(r.getString(2),WeeklySchedule.Assignment[].class)));
        },owner,generation).stream().findFirst().orElse(new Schedule("0",List.of()));
        var plans=new HashMap<UUID,RoutineResponses.Detail>();
        for(var id:schedule.assignments().stream().map(WeeklySchedule.Assignment::routineId).distinct().sorted().toList()) {
            if(lock)routineData.planForWorkoutStart(owner,generation,id).orElseThrow(()->DeloadPeriodFailure.conflict("DELOAD_SOURCE_CHANGED"));
            plans.put(id,routines.detail(owner,id,false));
        }
        var days=schedule.assignments().stream().sorted(Comparator.comparingInt(WeeklySchedule.Assignment::dayOfWeek)).map(a->new Day(a.dayOfWeek(),plans.get(a.routineId()))).toList();
        var ids=new TreeSet<UUID>();days.forEach(d->d.routine().weightPlacements().forEach(w->ids.add(w.standardExerciseId())));var classes=new ArrayList<Classification>();
        if(!ids.isEmpty()) {
            String marks=String.join(",",Collections.nCopies(ids.size(),"?"));Object[] parameters=ids.toArray();
            var rows=jdbc.query("SELECT id,name,joint_type FROM standard_exercise WHERE id IN("+marks+") ORDER BY id"+(lock?" FOR SHARE":""),(r,i)->new Classification(r.getObject(1,UUID.class),r.getString(2),r.getString(3),List.of()),parameters);
            if(rows.size()!=ids.size())throw DeloadPeriodFailure.conflict("DELOAD_SOURCE_CHANGED");
            var muscles=new HashMap<UUID,List<Muscle>>();
            jdbc.query("SELECT em.standard_exercise_id,m.id,m.group_code,m.name FROM exercise_muscle em JOIN muscle m ON m.id=em.muscle_id WHERE em.standard_exercise_id IN("+marks+") AND em.role='PRIMARY' ORDER BY em.standard_exercise_id,m.id"+(lock?" FOR SHARE OF em,m":""),(org.springframework.jdbc.core.RowCallbackHandler)r->muscles.computeIfAbsent(r.getObject(1,UUID.class),key->new ArrayList<>()).add(new Muscle(r.getObject(2,UUID.class),r.getString(3),r.getString(4))),parameters);
            rows.forEach(c->classes.add(new Classification(c.id(),c.name(),c.jointType(),List.copyOf(muscles.getOrDefault(c.id(),List.of())))));
        }
        return new Source(owner,Long.toString(generation),zone,eligibility.today(),schedule.version(),days,List.copyOf(classes));
    }
    Optional<Stored> active(UUID owner,long generation){return jdbc.query("SELECT * FROM deload_periods WHERE user_id=? AND data_generation=? AND status='ACTIVE'",(r,i)->stored(r),owner,generation).stream().findFirst();}
    Optional<Request> request(UUID owner,long generation,UUID request){return jdbc.query("SELECT * FROM deload_periods WHERE user_id=? AND data_generation=? AND request_id=?",(r,i)->new Request(r.getString("request_hash"),stored(r)),owner,generation,request).stream().findFirst();}
    private Stored stored(ResultSet r)throws SQLException {
        UUID id=r.getObject("id",UUID.class);var plans=jdbc.query("SELECT id,position,initial_plan::text FROM deload_temporary_plans WHERE period_id=? ORDER BY position",(p,i)->new SavedPlan(p.getObject(1,UUID.class),p.getInt(2),JSON.readValue(p.getString(3),Plan.class)),id);
        return new Stored(id,r.getObject("user_id",UUID.class),r.getString("data_generation"),r.getString("version"),r.getString("status"),r.getTimestamp("started_at").toInstant(),r.getObject("start_date",LocalDate.class),r.getString("time_zone"),r.getObject("recommended_end_date",LocalDate.class),r.getObject("maximum_end_date",LocalDate.class),Method.valueOf(r.getString("method")),Split.valueOf(r.getString("split")),r.getString("source_hash"),JSON.readValue(r.getString("source_snapshot"),Source.class),List.copyOf(plans));
    }
    Stored insert(UUID owner,long generation,UUID request,String requestHash,String sourceHash,Source source,Choice choice,Generated generated,Instant at) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO deload_periods(id,user_id,data_generation,request_id,request_hash,source_hash,started_at,start_date,time_zone,status,version,method,split,source_snapshot,updated_at) VALUES (?,?,?,?,?,?,?,?,?,'ACTIVE',0,?,?,?::jsonb,?)",id,owner,generation,request,requestHash,sourceHash,Timestamp.from(at),source.today(),source.timeZone(),choice.method().name(),choice.split().name(),JSON.writeValueAsString(source),Timestamp.from(at));
        for(int i=0;i<generated.plans().size();i++)jdbc.update("INSERT INTO deload_temporary_plans(id,user_id,data_generation,period_id,position,planned_date,initial_plan) VALUES (?,?,?,?,?,?,?::jsonb)",UUID.randomUUID(),owner,generation,id,i,generated.plans().get(i).date(),JSON.writeValueAsString(generated.plans().get(i)));
        return active(owner,generation).orElseThrow();
    }
}
