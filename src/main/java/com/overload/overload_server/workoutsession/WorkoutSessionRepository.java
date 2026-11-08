package com.overload.overload_server.workoutsession;

import jakarta.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import java.time.Instant;

@Repository
public class WorkoutSessionRepository {
    @PersistenceContext private EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final com.overload.overload_server.deload.DeloadPeriods periods;
    WorkoutSessionRepository(JdbcTemplate jdbc,com.overload.overload_server.deload.DeloadPeriods periods) { this.jdbc = jdbc;this.periods=periods; }
    void requireGeneralStart(UUID owner,WorkoutSessionRequests.Start input) {
        if(periods.generalStartBlocked(owner,input.dataGeneration(),input.startedAt()))
            throw new WorkoutSessionFailure(WorkoutSessionFailure.Reason.DELOAD_ACTIVE_PERIOD);
    }
    void requireSessionWrite(WorkoutSession session) {
        if(!session.deload()&&periods.generalWriteBlocked(session.userId,session.dataGeneration))
            throw new WorkoutSessionFailure(WorkoutSessionFailure.Reason.DELOAD_ACTIVE_PERIOD);
    }
    boolean hadActivity(UUID session){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT first_deload_activity_at IS NOT NULL FROM workout_sessions WHERE id=?",Boolean.class,session));}
    List<WorkoutSession> period(UUID owner,long generation,UUID period) {
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deload_periods WHERE user_id=? AND data_generation=? AND id=?)",Boolean.class,owner,generation,period)))throw new WorkoutSessionFailure(WorkoutSessionFailure.Reason.SESSION_NOT_FOUND);
        return entityManager.createQuery("select s from WorkoutSession s where s.userId=:u and s.dataGeneration=:g and s.deloadPeriodId=:p and s.abandonedAt is null order by s.startedAt,s.id",WorkoutSession.class).setParameter("u",owner).setParameter("g",generation).setParameter("p",period).getResultList();
    }

    record ExpiryCandidate(UUID owner, long generation, UUID session) { }
    Optional<Long> currentGeneration(UUID owner) {
        return jdbc.query("SELECT data_generation FROM users WHERE id=? FOR SHARE",(r,i)->r.getLong(1),owner).stream().findFirst();
    }
    boolean exists(UUID owner,long generation,UUID id) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workout_sessions WHERE user_id=? AND data_generation=? AND id=?)",Boolean.class,owner,generation,id);
    }
    List<WorkoutSession> closed(UUID owner,long generation,WorkoutSessionRequests.ClosedQuery page) {
        var query=entityManager.createQuery("select s from WorkoutSession s where s.userId=:u and s.dataGeneration=:g and s.abandonedAt is null and s.status in ('FINISHED','AUTO_CLOSED')"
                +(page.beforeId()==null?"":" and (s.closedAt<:at or (s.closedAt=:at and s.id<:id))")+" order by s.closedAt desc,s.id desc",WorkoutSession.class)
                .setParameter("u",owner).setParameter("g",generation).setMaxResults(page.limit()+1);
        if(page.beforeId()!=null)query.setParameter("at",page.beforeClosedAt()).setParameter("id",page.beforeId());
        return query.getResultList();
    }
    List<WorkoutSession> finishedBefore(WorkoutSession current) {
        return entityManager.createQuery("select s from WorkoutSession s where s.userId=:u and s.dataGeneration=:g and s.deloadPeriodId is null and s.status='FINISHED' and s.deletedAt is null and s.id<>:id and s.endedAt<=:start order by s.id",WorkoutSession.class)
                .setParameter("u",current.userId).setParameter("g",current.dataGeneration).setParameter("id",current.id).setParameter("start",current.startedAt).getResultList();
    }
    List<ExpiryCandidate> expiryCandidates(Instant cutoff) {
        // No row locks here. Every candidate transaction locks its owner before its session.
        return jdbc.query("SELECT user_id,data_generation,id FROM workout_sessions WHERE status='ACTIVE' AND last_workout_synced_at<=? ORDER BY last_workout_synced_at,id LIMIT 1000",
                (r,i)->new ExpiryCandidate(r.getObject(1,UUID.class),r.getLong(2),r.getObject(3,UUID.class)),java.sql.Timestamp.from(cutoff));
    }
    Optional<UUID> activeId(UUID owner,long generation) {
        return jdbc.query("SELECT id FROM workout_sessions WHERE user_id=? AND data_generation=? AND status='ACTIVE'",
                (r,i)->r.getObject(1,UUID.class),owner,generation).stream().findFirst();
    }
    boolean activeNotDue(UUID owner,long generation,Instant cutoff) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workout_sessions WHERE user_id=? AND data_generation=? AND status='ACTIVE' AND last_workout_synced_at>?)",
                Boolean.class,owner,generation,java.sql.Timestamp.from(cutoff));
    }
    Optional<WorkoutSession> lockSkippingBusy(ExpiryCandidate candidate) {
        var ids=jdbc.query("SELECT id FROM workout_sessions WHERE user_id=? AND data_generation=? AND id=? FOR UPDATE SKIP LOCKED",
                (r,i)->r.getObject(1,UUID.class),candidate.owner(),candidate.generation(),candidate.session());
        return ids.isEmpty()?Optional.empty():find(candidate.owner(),candidate.generation(),candidate.session(),false);
    }

    long generation(UUID owner, boolean lock) {
        var values = jdbc.query("SELECT data_generation FROM users WHERE id=?" + (lock ? " FOR SHARE" : ""),
                (row, index) -> row.getLong(1), owner);
        if (values.isEmpty()) throw new WorkoutSessionFailure(WorkoutSessionFailure.Reason.AUTHENTICATION_REQUIRED);
        return values.getFirst();
    }
    long lockOwnerForStart(UUID owner) {
        var values=jdbc.query("SELECT data_generation FROM users WHERE id=? FOR UPDATE",(row,index)->row.getLong(1),owner);
        if(values.isEmpty())throw new WorkoutSessionFailure(WorkoutSessionFailure.Reason.AUTHENTICATION_REQUIRED);
        return values.getFirst();
    }
    Optional<WorkoutSession> find(UUID owner, long generation, UUID id, boolean lock) {
        var query = entityManager.createQuery("select s from WorkoutSession s where s.userId=:owner and s.dataGeneration=:generation and s.id=:id", WorkoutSession.class)
                .setParameter("owner", owner).setParameter("generation", generation).setParameter("id", id);
        if (lock) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
        return query.getResultStream().findFirst();
    }
    Optional<WorkoutSession> active(UUID owner, long generation) {
        return entityManager.createQuery("select s from WorkoutSession s where s.userId=:owner and s.dataGeneration=:generation and s.status='ACTIVE'", WorkoutSession.class)
                .setParameter("owner", owner).setParameter("generation", generation).getResultStream().findFirst();
    }
    boolean occupied(UUID id) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workout_sessions WHERE id=?)", Boolean.class, id);
    }
    void insert(WorkoutSession value) { entityManager.persist(value); }
    void flush() { entityManager.flush(); }
}
