package com.overload.overload_server.routine;

import com.overload.overload_server.persistence.TransactionExecution;
import com.overload.overload_server.catalog.StandardExercise;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import static com.overload.overload_server.routine.RoutineFailure.Reason.*;

@Service
public class RoutineService {
	private final RoutineRepository repository;
	private final RoutinePlanHistory history;
	private final Clock clock;
	private final TransactionTemplate writes;
	private final TransactionTemplate reads;
	RoutineService(RoutineRepository repository, RoutinePlanHistory history, ObjectProvider<Clock> clocks,
			@Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
		this.repository = repository; this.history = history; this.clock = clocks.getIfAvailable(Clock::systemUTC);
		writes = new TransactionTemplate(manager);
		writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		reads = new TransactionTemplate(manager); reads.setReadOnly(true);
		reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
	}
	public RoutineResponses.Listing list(UUID user) {
		return TransactionExecution.execute(reads, () -> {
			long generation = repository.generation(user, false);
			return new RoutineResponses.Listing(user, Long.toString(generation), repository.list(user, generation).stream()
					.map(value -> new RoutineResponses.Summary(value.id, value.name, Long.toString(value.version),value.tag,value.createdAt)).toList());
		});
	}
	public RoutineResponses.Detail detail(UUID user, UUID id, boolean removed) {
		return TransactionExecution.execute(reads, () -> {
			var routine = active(repository.find(user, repository.generation(user, false), id, false));
			return response(routine, removed);
		});
	}
	public RoutineResponses.Detail create(UUID user, RoutineRequests.Create request) {
		return TransactionExecution.execute(writes, () -> {
			owner(user, request.dataGeneration());
			if (repository.occupied("routines", request.id())) throw new RoutineFailure(ROUTINE_ID_CONFLICT);
			Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
			var routine = Routine.create(request.id(), user, request.dataGeneration(), request.name(), now);
			routine.tag=request.tag();
			repository.insert(routine);
			apply(routine, request.weightPlacements(), request.cardioPlacements(), List.of(), now);
			repository.flush();
			var result = response(routine, false); history.record(result, now, false); return result;
		});
	}
	public RoutineResponses.Detail update(UUID user, UUID id, RoutineRequests.Update request) {
		return TransactionExecution.execute(writes, () -> {
			if(repository.lockOwnerForScheduleChange(user)!=request.dataGeneration())throw new RoutineFailure(DATA_GENERATION_MISMATCH);
			var routine = active(repository.find(user, request.dataGeneration(), id, true));
			version(routine, request.expectedVersion());
			Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); boolean renamed = !routine.name.equals(request.name()) || request.tagProvided() && !Objects.equals(routine.tag,request.tag());
			if(request.tagProvided())routine.tag=request.tag();
			routine.name = request.name();
			boolean changed = apply(routine, request.weightPlacements(), request.cardioPlacements(), request.weightApprovals(), now);
			if (renamed || changed) routine.changed(now);
			repository.flush();
			var result = response(routine, false); if (renamed || changed) history.record(result, now, false); return result;
		});
	}
	public void delete(UUID user, UUID id, RoutineRequests.Delete request) {
		TransactionExecution.execute(writes, () -> {
			if(repository.lockOwnerForScheduleChange(user)!=request.dataGeneration())throw new RoutineFailure(DATA_GENERATION_MISMATCH);
			var routine = repository.find(user, request.dataGeneration(), id, true);
			if (routine.deletedAt == null) {
				version(routine, request.expectedVersion());
				repository.requireEditableSource(user,request.dataGeneration(),id);
				Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); routine.deletedAt = now; routine.changed(now);
				repository.flush();
				history.record(response(routine, false), now, true);
				history.removeFromSchedule(user,request.dataGeneration(),id,now);
			}
			return null;
		});
	}
	private void owner(UUID user, long expected) {
		if (repository.generation(user, true) != expected) throw new RoutineFailure(DATA_GENERATION_MISMATCH);
	}
	private static Routine active(Routine value) {
		if (value.deletedAt != null) throw new RoutineFailure(ROUTINE_NOT_FOUND); return value;
	}
	private static void version(Routine value, long expected) {
		if (value.version != expected) throw new RoutineFailure(ROUTINE_VERSION_CONFLICT);
	}

	/** Only walks submitted/existing placements, never the number of planned sets or repetitions. */
	private boolean apply(Routine routine, List<RoutineRequests.Weight> weights, List<RoutineRequests.Cardio> cardio,
			List<RoutineRequests.Approval> approvals, Instant now) {
		var oldWeights = repository.weights(routine);
		var oldCardio = repository.cardio(routine);
		var weightById = oldWeights.stream().collect(Collectors.toMap(value -> value.id, value -> value));
		var cardioById = oldCardio.stream().collect(Collectors.toMap(value -> value.id, value -> value));
		var changedWeights = new HashSet<UUID>(); var changedCardio = new HashSet<UUID>();
		var definitions = new HashSet<UUID>();
		for (int i = 0; i < weights.size(); i++) {
			var input = weights.get(i); var old = weightById.get(input.id());
			placement(input.mode(), old == null, old != null && old.deletedAt != null);
			if (input.mode() == RoutineRequests.Mode.NEW) {
				available(input.id()); definitions.add(input.standardExerciseId()); changedWeights.add(input.id());
			} else {
				definitions.add(old.standardExerciseId);
				if (old.differs(input, i)) changedWeights.add(input.id());
			}
		}
		for (int i = 0; i < cardio.size(); i++) {
			var input = cardio.get(i); var old = cardioById.get(input.id());
			if(input.plan().activityId()!=null&&!repository.validCardio(input.plan()))throw new RoutineFailure(INVALID_REQUEST);
			placement(input.mode(), old == null, old != null && old.deletedAt != null);
			if (input.mode() == RoutineRequests.Mode.NEW) { available(input.id()); changedCardio.add(input.id()); }
			else if (old.differs(input, i)) changedCardio.add(input.id());
		}
		var exercises = repository.exercises(routine.userId, routine.dataGeneration, definitions, true);
		if (definitions.stream().anyMatch(id -> !exercises.containsKey(id))) throw new RoutineFailure(EXERCISE_NOT_FOUND);
		for (var input : weights) {
			if (input.mode() == RoutineRequests.Mode.NEW && !exercises.get(input.standardExerciseId()).isActive()) throw new RoutineFailure(EXERCISE_NOT_FOUND);
			var old = weightById.get(input.id());
			if (input.manufacturerId() != null && !repository.manufacturerAvailable(input.manufacturerId(), old != null && input.manufacturerId().equals(old.manufacturerId))) throw new RoutineFailure(INVALID_REQUEST);
		}
		var weightIds = weights.stream().map(RoutineRequests.Weight::id).collect(Collectors.toSet());
		var cardioIds = cardio.stream().map(RoutineRequests.Cardio::id).collect(Collectors.toSet());
		boolean removed = oldWeights.stream().anyMatch(p -> p.deletedAt == null && !weightIds.contains(p.id))
				|| oldCardio.stream().anyMatch(p -> p.deletedAt == null && !cardioIds.contains(p.id));
		boolean changed = removed || !changedWeights.isEmpty() || !changedCardio.isEmpty();
		// Approval-only and metadata/no-op writes keep their existing semantics.
		if (changed) repository.requireEditableSource(routine.userId,routine.dataGeneration,routine.id);
		for (var value : oldWeights) if (value.deletedAt == null && !weightIds.contains(value.id)) { value.deletedAt = now; value.updatedAt = now; }
		for (var value : oldCardio) if (value.deletedAt == null && !cardioIds.contains(value.id)) { value.deletedAt = now; value.updatedAt = now; }
		if (changed) {
			// Vacate final positions before inserts/restores/swaps. Deleted rows do not occupy the partial index.
			stageWeights(oldWeights, weights.size()); stageCardio(oldCardio, cardio.size()); repository.flush();
			for (int i = 0; i < weights.size(); i++) {
				var input = weights.get(i); var value = weightById.get(input.id());
				if (value == null) { value = RoutineWeightPlacement.create(routine, input, i, now); repository.insert(value); weightById.put(value.id, value); }
				else {
					value.position = i;
					if (changedWeights.contains(value.id)) value.apply(input, i, now);
				}
			}
			for (int i = 0; i < cardio.size(); i++) {
				var input = cardio.get(i); var value = cardioById.get(input.id());
				if (value == null) repository.insert(RoutineCardioPlacement.create(routine, input, i, now));
				else { value.position = i; if (changedCardio.contains(value.id)) value.apply(input.plan(), i, now); }
			}
		}
		for (var approval : approvals) {
			var value = weightById.get(approval.placementId());
			if (value == null || value.deletedAt != null || !weightIds.contains(value.id)) throw new RoutineFailure(PLACEMENT_NOT_FOUND);
			if (value.approvedWeight.compareTo(approval.nextApprovedWeightKg()) != 0) {
				repository.insert(ProgressChangeEvent.create(routine, value, exercises.get(value.standardExerciseId).getName(), approval, now));
				value.approvedWeight = approval.nextApprovedWeightKg(); value.updatedAt = now; changed = true;
			}
		}
		return changed;
	}
	private void available(UUID id) {
		if (repository.occupied("routine_weight_placements", id) || repository.occupied("routine_cardio_placements", id)) throw new RoutineFailure(PLACEMENT_ID_CONFLICT);
	}
	private static void placement(RoutineRequests.Mode mode, boolean missing, boolean deleted) {
		if (mode == RoutineRequests.Mode.NEW) { if (!missing) throw new RoutineFailure(PLACEMENT_ID_CONFLICT); }
		else {
			if (missing) throw new RoutineFailure(PLACEMENT_NOT_FOUND);
			if ((mode == RoutineRequests.Mode.RESTORE) != deleted) throw new RoutineFailure(PLACEMENT_STATE_CONFLICT);
		}
	}
	private static int temporaryBase(int maximum, int desiredCount, int activeCount) {
		long base = Math.max((long)maximum + 1, desiredCount);
		if (base + activeCount > Integer.MAX_VALUE) throw new RoutineFailure(WRITE_CONFLICT);
		return (int)base;
	}
	private static void stageWeights(List<RoutineWeightPlacement> values, int desiredCount) {
		var active = values.stream().filter(value -> value.deletedAt == null).toList();
		int next = temporaryBase(active.stream().mapToInt(value -> value.position).max().orElse(-1), desiredCount, active.size());
		for (var value : active) value.position = next++;
	}
	private static void stageCardio(List<RoutineCardioPlacement> values, int desiredCount) {
		var active = values.stream().filter(value -> value.deletedAt == null).toList();
		int next = temporaryBase(active.stream().mapToInt(value -> value.position).max().orElse(-1), desiredCount, active.size());
		for (var value : active) value.position = next++;
	}
	private RoutineResponses.Detail response(Routine routine, boolean removed) {
		var weights = repository.weights(routine).stream().filter(value -> (value.deletedAt != null) == removed).toList();
		var cardio = repository.cardio(routine).stream().filter(value -> (value.deletedAt != null) == removed).toList();
		var ids = weights.stream().map(value -> value.standardExerciseId).collect(Collectors.toSet());
		Map<UUID, StandardExercise> definitions = repository.exercises(routine.userId, routine.dataGeneration, ids, false);
		var weightResponses = weights.stream().map(value -> new RoutineResponses.Weight(value.id, value.standardExerciseId,
				definitions.get(value.standardExerciseId).getName(), value.manufacturerId, value.loadType, value.setCount, value.minReps, value.maxReps,
				RoutineResponses.decimal(value.initialWeight), RoutineResponses.decimal(value.approvedWeight), value.deletedAt)).toList();
		return new RoutineResponses.Detail(routine.userId, Long.toString(routine.dataGeneration), routine.id, Long.toString(routine.version),
				routine.name, weightResponses, cardio.stream().map(RoutineResponses::cardio).toList(),routine.tag,routine.createdAt);
	}
}
