package com.overload.overload_server.routine;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import static com.overload.overload_server.routine.RoutineFailure.Reason.*;

@Service
@Transactional(transactionManager="workoutTransactionManager")
public class WeeklyScheduleService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final RoutineService routines;private final RoutinePlanHistory plans;private final Clock clock;
    WeeklyScheduleService(JdbcTemplate jdbc,RoutineService routines,RoutinePlanHistory plans,ObjectProvider<Clock> clocks) {
        this.jdbc=jdbc;this.routines=routines;this.plans=plans;this.clock=clocks.getIfAvailable(Clock::systemUTC);
    }
    @Transactional(transactionManager="workoutTransactionManager",readOnly=true,isolation=Isolation.REPEATABLE_READ)
    WeeklySchedule.View read(UUID owner,String zone) {long g=generation(owner,false);return view(owner,g,zone,versions(owner,g));}
    WeeklySchedule.View save(UUID owner,WeeklySchedule.Save request) {
        long generation=generation(owner,true);if(generation!=request.generation())throw new RoutineFailure(DATA_GENERATION_MISMATCH);
        var versions=versions(owner,generation);var latest=versions.isEmpty()?null:versions.getLast();
        long version=latest==null?0:Long.parseLong(latest.version());
        if(version!=request.expectedVersion())throw new RoutineFailure(SCHEDULE_VERSION_CONFLICT);
        if(latest!=null&&!latest.timeZone().equals(request.timeZone()))throw new RoutineFailure(SCHEDULE_TIME_ZONE_CONFLICT);
        var now=clock.instant();var effective=effectiveFrom(now,request.timeZone(),latest==null);
        if(latest!=null&&latest.assignments().equals(request.assignments()))return view(owner,generation,request.timeZone(),versions);
        if(version==Long.MAX_VALUE)throw new RoutineFailure(WRITE_CONFLICT);
        for(UUID id:request.assignments().stream().map(WeeklySchedule.Assignment::routineId).distinct().sorted().toList())
            plans.record(routines.detail(owner,id,false),now,false);
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,recorded_at,effective_from,time_zone,assignments) VALUES (?,?,?,?,?,?,?::jsonb)",
                owner,generation,version+1,java.sql.Timestamp.from(now),effective,request.timeZone(),JSON.writeValueAsString(request.assignments()));
        return view(owner,generation,request.timeZone(),versions(owner,generation));
    }
    private long generation(UUID owner,boolean lock) {
        var rows=jdbc.query("SELECT data_generation FROM users WHERE id=?"+(lock?" FOR UPDATE":""),(r,i)->r.getLong(1),owner);
        if(rows.isEmpty())throw new RoutineFailure(AUTHENTICATION_REQUIRED);return rows.getFirst();
    }
    private List<WeeklySchedule.Version> versions(UUID owner,long generation) {
        return jdbc.query("SELECT version,effective_from,time_zone,recorded_at,assignments::text FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? ORDER BY version DESC LIMIT 20",this::version,owner,generation).reversed();
    }
    @Transactional(transactionManager="workoutTransactionManager",readOnly=true,isolation=Isolation.REPEATABLE_READ)
    WeeklySchedule.Page history(UUID owner,long before) {
        long generation=generation(owner,false);
        var rows=jdbc.query("SELECT version,effective_from,time_zone,recorded_at,assignments::text FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? AND version<? ORDER BY version DESC LIMIT 21",this::version,owner,generation,before);
        var page=rows.stream().limit(20).toList();
        return new WeeklySchedule.Page(owner,Long.toString(generation),page,rows.size()>20?page.getLast().version():null);
    }
    private WeeklySchedule.Version version(java.sql.ResultSet r,int index)throws java.sql.SQLException {
        return new WeeklySchedule.Version(r.getString(1),r.getObject(2,LocalDate.class),r.getString(3),r.getTimestamp(4).toInstant(),List.of(JSON.readValue(r.getString(5),WeeklySchedule.Assignment[].class)));
    }
    private WeeklySchedule.View view(UUID owner,long generation,String requestedZone,List<WeeklySchedule.Version> versions) {
        var latest=versions.isEmpty()?null:versions.getLast();String zone=latest==null?requestedZone:latest.timeZone();
        LocalDate today=LocalDate.ofInstant(clock.instant(),ZoneId.of(zone));
        var active=jdbc.query("SELECT version,effective_from,time_zone,recorded_at,assignments::text FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? AND effective_from<=? ORDER BY effective_from DESC,version DESC LIMIT 1",this::version,owner,generation,today);
        WeeklySchedule.Version current=active.isEmpty()?null:active.getFirst(),pending=latest!=null&&latest.effectiveFrom().isAfter(today)?latest:null;
        return new WeeklySchedule.View(owner,Long.toString(generation),latest==null?"0":latest.version(),zone,effectiveFrom(clock.instant(),zone,latest==null),current,pending,versions);
    }
    static LocalDate effectiveFrom(Instant now,String zone,boolean firstRegistration){return firstRegistration?LocalDate.ofInstant(now,ZoneId.of(zone)):nextMonday(now,zone);}
    static LocalDate nextMonday(Instant now,String zone){return LocalDate.ofInstant(now,ZoneId.of(zone)).with(TemporalAdjusters.next(java.time.DayOfWeek.MONDAY));}
}
