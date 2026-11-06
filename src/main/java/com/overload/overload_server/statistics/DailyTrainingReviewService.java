package com.overload.overload_server.statistics;

import com.overload.overload_server.routine.WorkoutCalendarRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** A bounded owner batch. Facts/evidence are read once per <=31-day window, not per date. */
@Service
public class DailyTrainingReviewService {
    private final JdbcTemplate jdbc;private final WorkoutCalendarRepository calendars;
    private final StatisticsRepository facts;private final PlanningRepository planning;private final Clock clock;
    private final TransactionTemplate writes;
    public DailyTrainingReviewService(JdbcTemplate jdbc,WorkoutCalendarRepository calendars,StatisticsRepository facts,PlanningRepository planning,ObjectProvider<Clock> clocks,
            @Qualifier("workoutTransactionManager") PlatformTransactionManager manager){
        this.jdbc=jdbc;this.calendars=calendars;this.facts=facts;this.planning=planning;clock=clocks.getIfAvailable(Clock::systemUTC);
        writes=new TransactionTemplate(manager);writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public static LocalDate dueDate(Instant now,String zone){var local=now.atZone(ZoneId.of(zone));return local.toLocalDate().minusDays(local.toLocalTime().isBefore(LocalTime.of(4,0))?2:1);}
    public int reviewBatch(){
        record Owner(UUID id,long generation) { }
        var now=clock.instant();var at=java.sql.Timestamp.from(now);
        var owners=jdbc.query("""
            SELECT c.user_id,c.data_generation FROM workout_calendar_control c
            WHERE c.reviewed_through < ((?::timestamptz AT TIME ZONE c.time_zone)-interval '4 hours')::date-1
               OR EXISTS(SELECT 1 FROM daily_training_assessments d WHERE d.user_id=c.user_id AND d.data_generation=c.data_generation
                 AND d.needs_recheck AND(d.next_check_at IS NULL OR d.next_check_at<=?)
                 AND d.workout_date<=((?::timestamptz AT TIME ZONE c.time_zone)-interval '4 hours')::date-1)
            ORDER BY c.reviewed_through,c.user_id LIMIT 20
            """,(r,i)->new Owner(r.getObject(1,UUID.class),r.getLong(2)),at,at,at);
        int reviewed=0;
        for(var owner:owners)try{reviewed+=Objects.requireNonNull(writes.execute(tx->review(owner.id(),owner.generation(),now)));}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Daily training review rolled back type={}",failure.getClass().getSimpleName());}
        return reviewed;
    }
    private int review(UUID owner,long generation,Instant now){
        if(jdbc.query("SELECT id FROM users WHERE id=? AND data_generation=? FOR UPDATE SKIP LOCKED",(r,i)->r.getObject(1,UUID.class),owner,generation).isEmpty())return 0;
        var optional=calendars.control(owner,generation);if(optional.isEmpty())return 0;var c=optional.get();
        var due=dueDate(now,c.timeZone());
        var dirty=jdbc.query("SELECT workout_date FROM daily_training_assessments WHERE user_id=? AND data_generation=? AND needs_recheck AND(next_check_at IS NULL OR next_check_at<=?) AND workout_date<=? ORDER BY workout_date LIMIT 1",(r,i)->r.getObject(1,LocalDate.class),owner,generation,java.sql.Timestamp.from(now),due);
        var from=c.reviewedThrough().plusDays(1);
        if(!dirty.isEmpty()&&dirty.getFirst().isBefore(from))from=dirty.getFirst();
        if(from.isAfter(due))return 0;
        var end=from.plusDays(31);if(end.isAfter(due.plusDays(1)))end=due.plusDays(1);
        var period=new StatisticsQuery(from,end);var actual=facts.read(owner,generation,period);var evidence=planning.read(owner,generation,period);
        var result=PlanningCalculator.calculate(owner,generation,period,actual,evidence);
        var performedSessions=new HashSet<UUID>();actual.sets().forEach(s->performedSessions.add(s.sessionId()));actual.cardio().stream().filter(a->a.activeMs()>0).forEach(a->performedSessions.add(a.sessionId()));
        var performedDates=new HashSet<LocalDate>();actual.sessions().stream().filter(s->performedSessions.contains(s.id())).forEach(s->performedDates.add(s.date()));
        for(var day:result.days()){
            var existing=evidence.daily().get(day.date());var anchor=PlanningCalculator.firstStart(evidence,day.date());
            String state=evidence.activeDates().contains(day.date())?"PENDING_ACTIVE":performedDates.contains(day.date())?"PERFORMED":
                day.status().equals("REST")||day.status().equals("BEFORE_REGISTRATION")?"EMPTY":
                day.plan()==null||day.date().isBefore(c.reviewFrom())||day.plannedExercises()==null||day.plannedExercises()==0?"UNKNOWN":"MISSED";
            if(state.equals("EMPTY")&&existing==null)continue; // Rest checks advance only the cursor.
            boolean miss=state.equals("MISSED"),pending=state.equals("UNKNOWN")||state.equals("PENDING_ACTIVE");
            jdbc.update("""
                INSERT INTO daily_training_assessments(user_id,data_generation,workout_date,first_session_id,first_missed_at,missed_schedule_version,missed_routine_id,missed_plan_version,state,revision,needs_recheck,next_check_at,reviewed_at)
                VALUES(?,?,?,?,?,?,?,?,?,1,?,?,?)
                ON CONFLICT(user_id,data_generation,workout_date) DO UPDATE SET
                  first_session_id=CASE WHEN daily_training_assessments.first_missed_at IS NULL THEN coalesce(daily_training_assessments.first_session_id,EXCLUDED.first_session_id) ELSE daily_training_assessments.first_session_id END,
                  first_missed_at=coalesce(daily_training_assessments.first_missed_at,EXCLUDED.first_missed_at),
                  missed_schedule_version=CASE WHEN daily_training_assessments.first_missed_at IS NULL THEN EXCLUDED.missed_schedule_version ELSE daily_training_assessments.missed_schedule_version END,
                  missed_routine_id=CASE WHEN daily_training_assessments.first_missed_at IS NULL THEN EXCLUDED.missed_routine_id ELSE daily_training_assessments.missed_routine_id END,
                  missed_plan_version=CASE WHEN daily_training_assessments.first_missed_at IS NULL THEN EXCLUDED.missed_plan_version ELSE daily_training_assessments.missed_plan_version END,
                  state=EXCLUDED.state,revision=daily_training_assessments.revision+CASE WHEN daily_training_assessments.state IS DISTINCT FROM EXCLUDED.state OR daily_training_assessments.first_missed_at IS NULL AND EXCLUDED.first_missed_at IS NOT NULL THEN 1 ELSE 0 END,
                  needs_recheck=EXCLUDED.needs_recheck,next_check_at=EXCLUDED.next_check_at,reviewed_at=EXCLUDED.reviewed_at
                """,owner,generation,day.date(),anchor==null?null:anchor.id(),miss?java.sql.Timestamp.from(now):null,miss?Long.parseLong(day.scheduleVersion()):null,
                miss?day.plan().id():null,miss?Long.parseLong(day.plan().version()):null,state,pending,pending?java.sql.Timestamp.from(now.plus(Duration.ofMinutes(10))):null,java.sql.Timestamp.from(now));
        }
        if(!end.minusDays(1).isBefore(c.reviewedThrough()))jdbc.update("UPDATE workout_calendar_control SET reviewed_through=? WHERE user_id=? AND data_generation=?",end.minusDays(1),owner,generation);
        return result.days().size();
    }
}
