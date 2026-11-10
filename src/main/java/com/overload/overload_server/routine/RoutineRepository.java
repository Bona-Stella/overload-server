package com.overload.overload_server.routine;

import com.overload.overload_server.catalog.StandardExercise;
import jakarta.persistence.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Aggregate-scoped persistence, always called inside workoutTransactionManager. */
@Repository
public class RoutineRepository {
	@PersistenceContext private EntityManager entityManager;
	private final JdbcTemplate jdbc;
    private final com.overload.overload_server.catalog.StandardCardioCatalog cardioCatalog;
	private final RoutinePlanHistory history;
	private final com.overload.overload_server.deload.DeloadPeriods periods;
	RoutineRepository(JdbcTemplate jdbc, RoutinePlanHistory history,com.overload.overload_server.deload.DeloadPeriods periods,
                      com.overload.overload_server.catalog.StandardCardioCatalog cardioCatalog) { this.cardioCatalog=cardioCatalog; this.jdbc = jdbc; this.history = history;this.periods=periods; }
	void requireGeneralWrite(UUID owner, long generation) {
		if (generalWriteBlocked(owner, generation)) throw new RoutineFailure(RoutineFailure.Reason.DELOAD_ACTIVE_PERIOD);
	}
	boolean generalWriteBlocked(UUID owner, long generation) {
		return periods.generalWriteBlocked(owner, generation);
	}
	/** Caller owns workoutTransactionManager. This scope does not start or commit a second transaction. */
	public ApprovalScope lockForWorkoutApproval(UUID owner,long generation,UUID id) {
		return new ApprovalScope(find(owner,generation,id,true));
	}
	public final class ApprovalScope {
		private final Routine routine;
		private final Map<UUID,RoutineWeightPlacement> placements=new HashMap<>();
		private boolean changed;
		private ApprovalScope(Routine r){routine=r;weights(r).forEach(p->placements.put(p.id,p));}
		public long version(){return routine.version;}
		public boolean available(UUID id){var p=placements.get(id);return routine.deletedAt==null&&p!=null&&p.deletedAt==null;}
		public java.math.BigDecimal weight(UUID id){return placements.get(id).approvedWeight;}
		public boolean changed(){return changed;}
		public void apply(UUID placement,UUID session,UUID exercise,UUID request,String routineName,String exerciseName,
				java.math.BigDecimal next,java.time.Instant occurred,java.time.Instant now){
			var p=placements.get(placement);
			if(p.approvedWeight.compareTo(next)!=0){
				insert(ProgressChangeEvent.workout(routine,p,session,exercise,request,routineName,exerciseName,next,occurred,now));
				p.approvedWeight=next;p.updatedAt=now;changed=true;
			}
		}
		public void complete(java.time.Instant now){if(changed){routine.changed(now);entityManager.flush();history.record(planForHistory(routine),now,false);}}
	}

	RoutineResponses.Detail schedulePlan(UUID owner, long generation, UUID id) {
		var routine = find(owner, generation, id, false);
		if (routine.deletedAt != null) throw new RoutineFailure(RoutineFailure.Reason.ROUTINE_NOT_FOUND);
		return planForHistory(routine);
	}

	private RoutineResponses.Detail planForHistory(Routine routine) {
		var weights = weights(routine).stream().filter(p -> p.deletedAt == null).toList();
		var definitions = exercises(routine.userId, routine.dataGeneration, weights.stream().map(p -> p.standardExerciseId).collect(java.util.stream.Collectors.toSet()), false);
		return new RoutineResponses.Detail(routine.userId, Long.toString(routine.dataGeneration), routine.id, Long.toString(routine.version), routine.name,
				weights.stream().map(p -> new RoutineResponses.Weight(p.id,p.standardExerciseId,definitions.get(p.standardExerciseId).getName(),p.manufacturerId,p.loadType,
						p.setCount,p.minReps,p.maxReps,RoutineResponses.decimal(p.initialWeight),RoutineResponses.decimal(p.approvedWeight),null)).toList(),
				cardio(routine).stream().filter(p -> p.deletedAt == null).map(RoutineResponses::cardio).toList());
	}

	/** START's existing workout transaction owns these locks through InitialPlan insertion and commit. */
	public Optional<RoutineResponses.Detail> planForWorkoutStart(UUID owner, long generation, UUID id) {
		var source = entityManager.createQuery("select r from Routine r where r.userId=:owner and r.dataGeneration=:generation and r.id=:id and r.deletedAt is null", Routine.class)
				.setParameter("owner", owner).setParameter("generation", generation).setParameter("id", id)
				.setLockMode(LockModeType.PESSIMISTIC_READ).getResultStream().findFirst();
		if (source.isEmpty()) return Optional.empty();
		var routine = source.get();
		var weights = weights(routine).stream().filter(p -> p.deletedAt == null).toList();
		var cardio = cardio(routine).stream().filter(p -> p.deletedAt == null).toList();
		var ids = new HashSet<UUID>(); weights.forEach(p -> ids.add(p.standardExerciseId));
		var definitions = exercises(owner, generation, ids, true);
		if (weights.stream().anyMatch(p -> !definitions.containsKey(p.standardExerciseId))) return Optional.empty();
		var result = weights.stream().map(p -> new RoutineResponses.Weight(p.id, p.standardExerciseId,
				definitions.get(p.standardExerciseId).getName(), p.manufacturerId, p.loadType, p.setCount, p.minReps, p.maxReps,
				RoutineResponses.decimal(p.initialWeight), RoutineResponses.decimal(p.approvedWeight), null)).toList();
		return Optional.of(new RoutineResponses.Detail(owner, Long.toString(generation), id, Long.toString(routine.version),
				routine.name, result, cardio.stream().map(RoutineResponses::cardio).toList()));
	}

	long generation(UUID owner, boolean lock) {
		var values = jdbc.query("SELECT data_generation FROM users WHERE id=?" + (lock ? " FOR SHARE" : ""),
				(row, index) -> row.getLong(1), owner);
		if (values.isEmpty()) throw new RoutineFailure(RoutineFailure.Reason.AUTHENTICATION_REQUIRED);
		return values.getFirst();
	}
	long lockOwnerForScheduleChange(UUID owner) {
		var rows=jdbc.query("SELECT data_generation FROM users WHERE id=? FOR UPDATE",(r,i)->r.getLong(1),owner);
		if(rows.isEmpty())throw new RoutineFailure(RoutineFailure.Reason.AUTHENTICATION_REQUIRED);return rows.getFirst();
	}
	boolean tryLockOwnerForScheduleChange(UUID owner, long generation) {
		return !jdbc.query("""
				SELECT id FROM users WHERE id=? AND data_generation=? FOR UPDATE SKIP LOCKED
				""", (row, index) -> row.getObject(1, UUID.class), owner, generation).isEmpty();
	}
	void requireEditableSource(UUID owner,long generation,UUID routine) {
		if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workout_sessions WHERE user_id=? AND data_generation=? AND source_routine_id=? AND status='ACTIVE')",
				Boolean.class,owner,generation,routine)))throw new RoutineFailure(RoutineFailure.Reason.ACTIVE_SESSION_EXISTS);
	}
	boolean selectableCardio(RoutineRequests.Plan plan) { return cardioCatalog.selectable(plan); }
	List<Routine> list(UUID owner, long generation) {
		return entityManager.createQuery("select r from Routine r where r.userId=:owner and r.dataGeneration=:generation and r.deletedAt is null order by r.createdAt desc, r.id desc", Routine.class)
				.setParameter("owner", owner).setParameter("generation", generation).getResultList();
	}
	Routine find(UUID owner, long generation, UUID id, boolean lock) {
		var query = entityManager.createQuery("select r from Routine r where r.userId=:owner and r.dataGeneration=:generation and r.id=:id", Routine.class)
				.setParameter("owner", owner).setParameter("generation", generation).setParameter("id", id);
		if (lock) query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
		return query.getResultStream().findFirst().orElseThrow(() -> new RoutineFailure(RoutineFailure.Reason.ROUTINE_NOT_FOUND));
	}
	boolean occupied(String table, UUID id) {
		// Only fixed internal table names supplied by this aggregate service, never request strings.
		return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM " + table + " WHERE id=?)", Boolean.class, id);
	}
	List<RoutineWeightPlacement> weights(Routine routine) {
		return entityManager.createQuery("select p from RoutineWeightPlacement p where p.userId=:owner and p.dataGeneration=:generation and p.routineId=:id order by p.position,p.id", RoutineWeightPlacement.class)
				.setParameter("owner", routine.userId).setParameter("generation", routine.dataGeneration).setParameter("id", routine.id).getResultList();
	}
	List<RoutineCardioPlacement> cardio(Routine routine) {
		return entityManager.createQuery("select p from RoutineCardioPlacement p where p.userId=:owner and p.dataGeneration=:generation and p.routineId=:id order by p.position,p.id", RoutineCardioPlacement.class)
				.setParameter("owner", routine.userId).setParameter("generation", routine.dataGeneration).setParameter("id", routine.id).getResultList();
	}
	Map<UUID, StandardExercise> exercises(UUID owner, long generation, Set<UUID> ids, boolean lock) {
		if (ids.isEmpty()) return Map.of();
		var query = entityManager.createQuery("select e from StandardExercise e where e.id in :ids order by e.id", StandardExercise.class)
				.setParameter("ids", ids);
		if (lock) query.setLockMode(LockModeType.PESSIMISTIC_READ);
		var result = new HashMap<UUID, StandardExercise>();
		query.getResultList().forEach(value -> result.put(value.getId(), value));
		return result;
	}
	void insert(Object value) { entityManager.persist(value); }
	boolean manufacturerAvailable(UUID id, boolean preserved) {
		var value = entityManager.find(com.overload.overload_server.catalog.Manufacturer.class, id, LockModeType.PESSIMISTIC_READ);
		return value != null && (preserved || value.isActive());
	}
	void flush() { entityManager.flush(); }
}
