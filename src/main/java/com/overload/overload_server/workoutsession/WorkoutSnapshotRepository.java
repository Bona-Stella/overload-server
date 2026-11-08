package com.overload.overload_server.workoutsession;

import jakarta.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.overload.overload_server.catalog.StandardExercise;
import java.util.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;

@Repository
public class WorkoutSnapshotRepository {
    @PersistenceContext private EntityManager em;
    private final JdbcTemplate jdbc;
    WorkoutSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }
    UUID source(UUID owner, long generation, UUID session) {
        var rows = jdbc.query("SELECT source_routine_id FROM workout_sessions WHERE user_id=? AND data_generation=? AND id=?", (r, i) -> r.getObject(1, UUID.class), owner, generation, session);
        if (rows.isEmpty())throw new WorkoutSessionFailure(WorkoutSessionFailure.Reason.SESSION_NOT_FOUND);
        return rows.getFirst();
    }
    List<SessionExercise> exercises(UUID session) {
        return em.createQuery("select e from SessionExercise e where e.sessionId=:id order by e.id", SessionExercise.class).setParameter("id", session).getResultList();
    }
    List<PerformedSet> sets(UUID session) {
        return em.createQuery("select e from PerformedSet e where e.sessionId=:id order by e.id", PerformedSet.class).setParameter("id", session).getResultList();
    }
    List<PerformedSet> setsForSessions(List<UUID> sessions) {
        if (sessions.isEmpty()) return List.of();
        if (sessions.size()==1) return sets(sessions.getFirst());
        return em.createQuery("select e from PerformedSet e where e.sessionId in :ids order by e.sessionId,e.id", PerformedSet.class)
                .setParameter("ids", sessions).getResultList();
    }
    List<CardioResult> cardio(UUID session) {
        return em.createQuery("select e from CardioResult e where e.sessionId=:id order by e.id", CardioResult.class).setParameter("id", session).getResultList();
    }
    List<WeightApprovalRequest> approvals(UUID session) {
        return em.createQuery("select e from WeightApprovalRequest e where e.sessionId=:id order by e.exerciseId,e.sequence", WeightApprovalRequest.class).setParameter("id", session).getResultList();
    }
    List<RecordChangeEvent> changes(UUID session) {
        return em.createQuery("select e from RecordChangeEvent e where e.sessionId=:id order by e.recordedAt,e.id",RecordChangeEvent.class).setParameter("id",session).getResultList();
    }
    boolean occupied(String table, UUID id) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM "+table+" WHERE id=?)", Boolean.class, id);
    }
    void insert(Object value) {
        em.persist(value);
    }
    void flush() {
        em.flush();
    }
    void validateCardioCatalog(java.util.List<CardioPlan> plans){
        for(var item:plans){var p=item.plan();if(p.has("activityId")&&!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM cardio_activity_catalog WHERE id=? AND active AND kind=? AND name=?)",Boolean.class,UUID.fromString(p.get("activityId").asString()),p.get("kind").asString(),p.get("name").asString())))throw new WorkoutSessionFailure(WorkoutSessionFailure.Reason.INVALID_SNAPSHOT);}
    }
    void validateManufacturers(Collection<UUID> ids) {
        var selected = ids.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (selected.isEmpty()) return;
        var found = em.createQuery("select m.id from Manufacturer m where m.id in :ids", UUID.class)
                .setParameter("ids", selected).getResultList();
        if (found.size() != selected.size()) throw new WorkoutSessionFailure(WorkoutSessionFailure.Reason.INVALID_SNAPSHOT);
    }
    Map<UUID, StandardExercise> definitions(UUID user, long generation, Collection<UUID> ids) {
        if (ids.isEmpty())return Map.of();
        var values = em.createQuery("select e from StandardExercise e where e.id in :ids order by e.id", StandardExercise.class)
        .setParameter("ids", ids).setLockMode(LockModeType.PESSIMISTIC_READ).getResultList();
        var result = new HashMap<UUID, StandardExercise>();
        values.forEach(e -> result.put(e.getId(), e));
        return result;
    }
    void initialize(WorkoutSession session) {
        var initial = InitialPlan.read(session.initialPlan);
        for (var p:initial.weightExercises())insert(new SessionExercise(session, new Exercise(p.sessionExerciseId(), "WEIGHT", p.standardExerciseId()), session.deload()?p.exerciseName():null));
        for (var p:initial.cardioActivities())insert(new SessionExercise(session, new Exercise(p.sessionActivityId(), "CARDIO", null), null));
    }
    List<ApprovalResult> results(UUID session, List<WeightApprovalRequest> requests) {
        var events = new HashMap<UUID, UUID>();
        jdbc.query("SELECT approval_request_id,id FROM progress_change_events WHERE session_id=?", r -> {
            events.put(r.getObject(1, UUID.class), r.getObject(2, UUID.class));
            }, session);
        return requests.stream().map(r -> new ApprovalResult(r.id, r.exerciseId, Long.toString(r.sequence), r.status, r.reason, events.get(r.id),
            r.versionBefore==null?null:r.versionBefore.toString(), r.versionAfter==null?null:r.versionAfter.toString(), r.resolvedAt, r.resolvedBy)).toList();
    }
    View view(WorkoutSession session) {
        var requests = approvals(session.id);
        var sets = sets(session.id);
        var cardio = cardio(session.id);
        return new View(new State(exercises(session.id).stream().map(SessionExercise::value).toList(), JSON.readValue(session.appliedPlan, Plan.class),
            sets.stream().filter(s -> s.deletedAt==null).map(PerformedSet::value).toList(), cardio.stream().filter(s -> s.deletedAt==null).map(CardioResult::value).toList(),
            sets.stream().filter(s -> s.deletedAt!=null).map(s -> s.id).toList(), cardio.stream().filter(s -> s.deletedAt!=null).map(s -> s.id).toList(),
            requests.stream().map(WeightApprovalRequest::value).toList()), results(session.id, requests),
            sets.stream().map(s -> new RecordedContext(s.id, s.approvalState, decimal(s.approvedWeight))).toList());
    }
}
