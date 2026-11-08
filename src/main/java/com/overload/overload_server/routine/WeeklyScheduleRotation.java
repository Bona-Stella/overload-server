package com.overload.overload_server.routine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.overload.overload_server.routine.RoutineFailure.Reason.*;
import static com.overload.overload_server.routine.WeeklyScheduleStore.*;

@Service
public class WeeklyScheduleRotation {
    private static final Logger LOG = LoggerFactory.getLogger(WeeklyScheduleRotation.class);
    private static final int PAGE_SIZE = 20;
    private final WeeklyScheduleStore store;
    private final RoutineRepository routines;
    private final RoutinePlanHistory plans;
    private final AppliedWeeklySchedule applied;
    private final Clock clock;
    private final TransactionTemplate writes;

    WeeklyScheduleRotation(WeeklyScheduleStore store, RoutineRepository routines, RoutinePlanHistory plans,
                           AppliedWeeklySchedule applied, ObjectProvider<Clock> clocks,
                           @Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
        this.store = store;
        this.routines = routines;
        this.plans = plans;
        this.applied = applied;
        clock = clocks.getIfAvailable(Clock::systemUTC);
        writes = new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Called under the owner lock after a configuration change. */
    void restart(Scope scope, State state, List<WeeklySchedule.Preset> presets, String requestedZone, Instant at) {
        var included = included(presets);
        var current = applied.current(scope);
        String zone = applied.resolveTimeZone(scope, current, requestedZone);
        if (zone == null) throw new RoutineFailure(SCHEDULE_TIME_ZONE_REQUIRED);
        var timeZone = ZoneId.of(zone);
        var target = included.getFirst();
        applied.applyPreset(scope, state, target, current, zone, at);
        store.changeState(scope, target.id(), true, WeeklyRotationWeeks.nextMonday(at.atZone(timeZone).toLocalDate(), timeZone));
    }

    public int runBatch() {
        Instant cutoff = now();
        Due after = null;
        int advanced = 0;
        List<Due> page;
        do {
            page = store.due(cutoff, after, PAGE_SIZE);
            for (var due : page) {
                try {
                    if (Boolean.TRUE.equals(writes.execute(tx -> advance(due.scope())))) advanced++;
                } catch (RuntimeException failure) {
                    LOG.warn("Weekly rotation rolled back type={}", failure.getClass().getSimpleName());
                }
            }
            if (!page.isEmpty()) after = page.getLast();
        } while (page.size() == PAGE_SIZE);
        return advanced;
    }

    private boolean advance(Scope scope) {
        if (!routines.tryLockOwnerForScheduleChange(scope.owner(), scope.generation())) return false;
        var state = store.state(scope).orElse(null);
        Instant at = now();
        if (state == null || !state.autoEnabled() || state.nextRotationAt() == null || state.nextRotationAt().isAfter(at)
                || routines.generalWriteBlocked(scope.owner(), scope.generation()) || plans.hasActiveWorkout(scope.owner(), scope.generation()))
            return false;

        var included = included(store.presets(scope));
        int active = included.stream().map(WeeklySchedule.Preset::id).toList().indexOf(state.activePresetId());
        if (active < 0) throw new RoutineFailure(WRITE_CONFLICT);
        var current = applied.current(scope);
        String zone = applied.timeZone(scope, current);
        if (zone == null) throw new RoutineFailure(SCHEDULE_TIME_ZONE_REQUIRED);
        var timeZone = ZoneId.of(zone);
        var target = included.get(WeeklyRotationWeeks.targetIndex(active, included.size(), state.nextRotationAt(), at, timeZone));
        applied.applyPreset(scope, state, target, current, zone, at);
        store.changeState(scope, target.id(), true, WeeklyRotationWeeks.nextMonday(at.atZone(timeZone).toLocalDate(), timeZone));
        return true;
    }

    /** G5 START already owns the user lock and writes the period in this transaction. */
    @Transactional(transactionManager = "workoutTransactionManager", propagation = Propagation.MANDATORY)
    public void pauseForDeload(UUID owner, long generation) {
        var scope = new Scope(owner, generation);
        store.state(scope).filter(state -> state.autoEnabled() && state.nextRotationAt() != null)
                .ifPresent(state -> store.changeState(scope, state.activePresetId(), true, null));
    }

    /** The active ID stays fixed while G5 protects normal schedule writes. */
    @Transactional(transactionManager = "workoutTransactionManager", propagation = Propagation.MANDATORY)
    public void resumeAfterDeload(UUID owner, long generation, LocalDate returnDate, ZoneId zone) {
        var scope = new Scope(owner, generation);
        store.state(scope).filter(State::autoEnabled).ifPresent(state ->
                store.changeState(scope, state.activePresetId(), true, WeeklyRotationWeeks.nextMonday(returnDate, zone)));
    }

    private static List<WeeklySchedule.Preset> included(List<WeeklySchedule.Preset> presets) {
        var included = presets.stream().filter(WeeklySchedule.Preset::rotationIncluded).toList();
        if (included.size() < 2) throw new RoutineFailure(ROTATION_REQUIRES_TWO_PRESETS);
        return included;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
