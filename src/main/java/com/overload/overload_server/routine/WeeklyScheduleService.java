package com.overload.overload_server.routine;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.overload.overload_server.deload.DeloadAnalysisService;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.overload.overload_server.routine.RoutineFailure.Reason.*;

@Service
@Transactional(transactionManager="workoutTransactionManager")
public class WeeklyScheduleService {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;private final RoutineService routines;private final RoutinePlanHistory plans;
    private final WorkoutCalendarRepository calendars;private final Clock clock;private final DeloadAnalysisService analysis;
    WeeklyScheduleService(JdbcTemplate jdbc,RoutineService routines,RoutinePlanHistory plans,WorkoutCalendarRepository calendars,ObjectProvider<Clock> clocks,DeloadAnalysisService analysis) {
        this.jdbc=jdbc;this.routines=routines;this.plans=plans;this.calendars=calendars;this.analysis=analysis;clock=clocks.getIfAvailable(Clock::systemUTC);
    }
    @Transactional(transactionManager="workoutTransactionManager",readOnly=true,isolation=Isolation.REPEATABLE_READ)
    WeeklySchedule.View read(UUID owner,String zone) {return view(owner,generation(owner,false),zone);}
    @Transactional(transactionManager="workoutTransactionManager",isolation=Isolation.READ_COMMITTED)
    WeeklySchedule.View save(UUID owner,WeeklySchedule.Save request) {
        long generation=generation(owner,true);if(generation!=request.generation())throw new RoutineFailure(DATA_GENERATION_MISMATCH);
        var current=view(owner,generation,request.timeZone());long version=Long.parseLong(current.version());
        if(version!=request.expectedVersion())throw new RoutineFailure(SCHEDULE_VERSION_CONFLICT);
        if(version!=0&&!current.timeZone().equals(request.timeZone()))throw new RoutineFailure(SCHEDULE_TIME_ZONE_CONFLICT);
        if(version!=0&&current.assignments().equals(request.assignments()))return current;
        plans.requireEditableSchedule(owner,generation);
        if(version==Long.MAX_VALUE)throw new RoutineFailure(WRITE_CONFLICT);
        var now=clock.instant().truncatedTo(ChronoUnit.MICROS);
        calendars.requireOpenTime(owner,generation,now);
        if(current.appliedAt()!=null&&now.isBefore(current.appliedAt()))throw new RoutineFailure(WRITE_CONFLICT);
        analysis.bindForSchedule(owner,generation,request.timeZone());
        for(UUID id:request.assignments().stream().map(WeeklySchedule.Assignment::routineId).distinct().sorted().toList())
            plans.record(routines.detail(owner,id,false),now,false);
        jdbc.update("INSERT INTO weekly_schedule_versions(user_id,data_generation,version,recorded_at,effective_from,time_zone,assignments) VALUES (?,?,?,?,?,?,?::jsonb)",
                owner,generation,version+1,java.sql.Timestamp.from(now),now.atZone(ZoneId.of(request.timeZone())).toLocalDate(),request.timeZone(),JSON.writeValueAsString(request.assignments()));
        if(version==0)calendars.register(owner,generation,request.timeZone(),1,now);
        return view(owner,generation,request.timeZone());
    }
    private long generation(UUID owner,boolean lock) {
        var rows=jdbc.query("SELECT data_generation FROM users WHERE id=?"+(lock?" FOR UPDATE":""),(r,i)->r.getLong(1),owner);
        if(rows.isEmpty())throw new RoutineFailure(AUTHENTICATION_REQUIRED);return rows.getFirst();
    }
    @Transactional(transactionManager="workoutTransactionManager",readOnly=true,isolation=Isolation.REPEATABLE_READ)
    WeeklySchedule.Page history(UUID owner,long before) {
        long generation=generation(owner,false);var c=calendars.control(owner,generation);
        var rows=jdbc.query("SELECT version,effective_from,time_zone,recorded_at,assignments::text FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? AND version<? ORDER BY version DESC LIMIT 21",
                (r,i)->new WeeklySchedule.HistoryVersion(r.getString(1),r.getObject(2,LocalDate.class),r.getString(3),r.getTimestamp(4).toInstant(),
                    c.isPresent()&&r.getLong(1)>=c.get().baselineVersion()?r.getTimestamp(4).toInstant():null,List.of(JSON.readValue(r.getString(5),WeeklySchedule.Assignment[].class))),owner,generation,before);
        var page=rows.stream().limit(20).toList();return new WeeklySchedule.Page(owner,Long.toString(generation),page,rows.size()>20?page.getLast().version():null);
    }
    private WeeklySchedule.View view(UUID owner,long generation,String zone) {
        var c=calendars.control(owner,generation);
        if(c.isEmpty())return new WeeklySchedule.View(owner,Long.toString(generation),"0",zone,null,List.of());
        var rows=jdbc.query("SELECT version,recorded_at,assignments::text FROM weekly_schedule_versions WHERE user_id=? AND data_generation=? AND version>=? ORDER BY version DESC LIMIT 1",
                (r,i)->new WeeklySchedule.View(owner,Long.toString(generation),r.getString(1),c.get().timeZone(),r.getTimestamp(2).toInstant(),List.of(JSON.readValue(r.getString(3),WeeklySchedule.Assignment[].class))),owner,generation,c.get().baselineVersion());
        return rows.getFirst();
    }
}
