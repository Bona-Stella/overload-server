package com.overload.overload_server.routine;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.overload.overload_server.routine.RoutineFailure.Reason.*;
import static com.overload.overload_server.routine.WeeklyScheduleStore.*;

@Service
@Transactional(transactionManager = "workoutTransactionManager", isolation = Isolation.READ_COMMITTED)
public class WeeklyScheduleService {
    private static final int MAX_PRESETS = 4;
    private final WeeklyScheduleStore store;
    private final RoutineRepository routines;
    private final AppliedWeeklySchedule applied;
    private final WeeklyScheduleRotation rotation;
    private final Clock clock;

    WeeklyScheduleService(WeeklyScheduleStore store, RoutineRepository routines, AppliedWeeklySchedule applied,
                          WeeklyScheduleRotation rotation, ObjectProvider<Clock> clocks) {
        this.store = store;
        this.routines = routines;
        this.applied = applied;
        this.rotation = rotation;
        this.clock = clocks.getIfAvailable(Clock::systemUTC);
    }

    @Transactional(transactionManager = "workoutTransactionManager", readOnly = true, isolation = Isolation.REPEATABLE_READ)
    WeeklySchedule.View read(UUID owner) {
        return view(new Scope(owner, routines.generation(owner, false)));
    }

    WeeklySchedule.View create(UUID owner, WeeklySchedule.Configuration request) {
        var scope = lock(owner, request);
        var state = checkedState(scope, request);
        int count = store.presets(scope).size();
        if (count >= MAX_PRESETS) throw new RoutineFailure(PRESET_LIMIT_REACHED);
        store.create(scope, count + 1);
        store.changeState(scope, state.activePresetId(), state.autoEnabled(), state.nextRotationAt());
        return view(scope);
    }

    WeeklySchedule.View edit(UUID owner, UUID id, WeeklySchedule.Edit request) {
        var scope = lock(owner, request.configuration());
        var state = checkedState(scope, request.configuration());
        var preset = preset(store.presets(scope), id);
        checkVersion(preset, request.expectedPresetVersion());
        boolean changed = !preset.assignments().equals(request.assignments());

        if (id.equals(state.activePresetId())) {
            var current = applied.current(scope);
            String zone = applied.resolveTimeZone(scope, current, request.timeZone());
            if (changed) store.edit(scope, id, request.assignments());
            if (current.isEmpty() || !current.get().assignments().equals(request.assignments())) {
                applied.apply(scope, current, zone, request.assignments(), now());
                if (current.isEmpty()) store.changeState(scope, id, state.autoEnabled(), state.nextRotationAt());
            }
        } else if (changed) {
            for (UUID routineId : request.assignments().stream().map(WeeklySchedule.Assignment::routineId).distinct().toList()) {
                if (routines.find(owner, scope.generation(), routineId, false).deletedAt != null)
                    throw new RoutineFailure(ROUTINE_NOT_FOUND);
            }
            store.edit(scope, id, request.assignments());
        }
        return view(scope);
    }

    WeeklySchedule.View activate(UUID owner, WeeklySchedule.Activate request) {
        var scope = lock(owner, request.configuration());
        var state = checkedState(scope, request.configuration());
        if (state.autoEnabled()) throw new RoutineFailure(AUTO_ROTATION_ENABLED);
        var target = preset(store.presets(scope), request.presetId());
        var current = applied.current(scope);
        String zone = applied.resolveTimeZone(scope, current, request.timeZone());
        applied.applyPreset(scope, state, target, current, zone, now());
        if (!target.id().equals(state.activePresetId()) || current.isEmpty())
            store.changeState(scope, target.id(), false, null);
        return view(scope);
    }

    WeeklySchedule.View rename(UUID owner, UUID id, WeeklySchedule.Rename request) {
        var scope = lock(owner, request.configuration());
        checkedState(scope, request.configuration());
        var target = preset(store.presets(scope), id);
        checkVersion(target, request.expectedPresetVersion());
        if (!target.name().equals(request.name())) store.rename(scope, id, request.name());
        return view(scope);
    }

    WeeklySchedule.View delete(UUID owner, UUID id, WeeklySchedule.Delete request) {
        var scope = lock(owner, request.configuration());
        var state = checkedState(scope, request.configuration());
        var presets = store.presets(scope);
        var target = preset(presets, id);
        checkVersion(target, request.expectedPresetVersion());
        if (presets.size() == 1) throw new RoutineFailure(LAST_PRESET);
        if (id.equals(state.activePresetId())) throw new RoutineFailure(ACTIVE_PRESET);
        store.delete(scope, target);
        if (state.autoEnabled()) rotation.restart(scope, state, store.presets(scope), null, now());
        else store.changeState(scope, state.activePresetId(), false, null);
        return view(scope);
    }

    WeeklySchedule.View configureRotation(UUID owner, WeeklySchedule.Rotation request) {
        var scope = lock(owner, request.configuration());
        var state = checkedState(scope, request.configuration());
        var presets = store.presets(scope);
        var previous = presets.stream().map(p -> new WeeklySchedule.RotationEntry(p.id(), p.rotationIncluded())).toList();
        if (request.presets().size() != presets.size()) throw RoutineRequests.invalid();
        for (var entry : request.presets()) preset(presets, entry.id());
        boolean changed = !previous.equals(request.presets());
        if (changed) store.configure(scope, request.presets());
        if (request.autoEnabled() && (changed || !state.autoEnabled())) {
            rotation.restart(scope, state, store.presets(scope), request.timeZone(), now());
        } else if (changed || state.autoEnabled() != request.autoEnabled()) {
            store.changeState(scope, state.activePresetId(), false, null);
        }
        return view(scope);
    }

    /** Reads only saved preset evidence inside the G5 preview or owner-locked START transaction. */
    @Transactional(transactionManager = "workoutTransactionManager", propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<WeeklySchedule.Preset> sourcePreset(UUID owner, long generation, UUID requested) {
        var scope = new Scope(owner, generation);
        return store.state(scope).flatMap(state -> {
            UUID selected = requested == null ? state.activePresetId() : requested;
            return store.presets(scope).stream().filter(preset -> preset.id().equals(selected)).findFirst();
        });
    }

    /** Called after logical routine deletion under the same owner lock and workout transaction. */
    void removeRoutine(UUID owner, long generation, UUID routineId, Instant at) {
        var scope = new Scope(owner, generation);
        var state = store.state(scope);
        if (state.isEmpty()) {
            if (applied.current(scope).stream().flatMap(version -> version.assignments().stream())
                    .anyMatch(assignment -> assignment.routineId().equals(routineId)))
                throw new RoutineFailure(PRESET_STATE_MISSING);
            return;
        }
        List<WeeklySchedule.Assignment> activeAssignments = null;
        for (var preset : store.presets(scope)) {
            var assignments = preset.assignments().stream().filter(a -> !a.routineId().equals(routineId)).toList();
            if (assignments.size() == preset.assignments().size()) continue;
            store.edit(scope, preset.id(), assignments);
            if (preset.id().equals(state.get().activePresetId())) activeAssignments = assignments;
        }
        if (activeAssignments != null) {
            var current = applied.current(scope);
            applied.apply(scope, current, applied.timeZone(scope, current), activeAssignments, at);
        }
    }

    private Scope lock(UUID owner, WeeklySchedule.Configuration request) {
        long generation = routines.lockOwnerForScheduleChange(owner);
        if (generation != request.generation()) throw new RoutineFailure(DATA_GENERATION_MISMATCH);
        routines.requireGeneralWrite(owner, generation);
        return new Scope(owner, generation);
    }

    private State state(Scope scope) {
        return store.state(scope).orElseThrow(() -> new RoutineFailure(PRESET_STATE_MISSING));
    }

    private State checkedState(Scope scope, WeeklySchedule.Configuration request) {
        var state = state(scope);
        if (state.version() != request.expectedStateVersion()) throw new RoutineFailure(SCHEDULE_STATE_VERSION_CONFLICT);
        return state;
    }

    private static WeeklySchedule.Preset preset(List<WeeklySchedule.Preset> presets, UUID id) {
        return presets.stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new RoutineFailure(PRESET_NOT_FOUND));
    }

    private static void checkVersion(WeeklySchedule.Preset preset, long expected) {
        if (Long.parseLong(preset.presetVersion()) != expected) throw new RoutineFailure(PRESET_VERSION_CONFLICT);
    }

    private WeeklySchedule.View view(Scope scope) {
        var state = state(scope);
        var current = applied.current(scope);
        return new WeeklySchedule.View(Long.toString(scope.generation()), Long.toString(state.version()), state.activePresetId(),
                state.autoEnabled(), applied.timeZone(scope, current), state.nextRotationAt(),
                current.map(WeeklySchedule.Version::version).orElse(null),
                current.map(WeeklySchedule.Version::recordedAt).orElse(null), store.presets(scope));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
