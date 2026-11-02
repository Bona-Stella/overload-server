package com.overload.overload_server.statistics;

import com.overload.overload_server.workoutsession.InitialPlan;
import com.overload.overload_server.workoutsession.WorkoutSnapshotData;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.overload.overload_server.statistics.StatisticsResponse.*;

/** Aggregates current facts; never changes plans, approval state or performed records. */
final class StatisticsCalculator {
    record Session(UUID id, LocalDate date, InitialPlan initial, WorkoutSnapshotData.Plan applied) { }
    record SetFact(UUID id, UUID sessionId, UUID exerciseId, UUID standardId, String name,
                   BigDecimal weight, int reps, String loadType) { }
    record CardioFact(UUID sessionId, UUID activityId, long activeMs, BigDecimal distance) { }
    record Mapping(UUID standardId, UUID muscleId, String name, String group, String role) { }
    record Input(List<Session> sessions, List<SetFact> sets, List<CardioFact> cardio, List<Mapping> mappings, List<Recent> recent) { }
    private record MuscleKey(UUID id, String name, String group, String role) { }
    private record GroupKey(String group, String role) { }
    private record CardioKey(String kind, String name) { }

    static StatisticsResponse calculate(UUID owner, long generation, StatisticsQuery query, Input input) {
        var total = new Count();
        var dayWeights = new TreeMap<LocalDate, Count>();
        var dayCardio = new HashMap<LocalDate, Long>();
        query.fromDate().datesUntil(query.toDateExclusive()).forEach(date -> dayWeights.put(date, new Count()));
        var sessions = new HashMap<UUID, Session>();
        var exerciseNames = new HashMap<UUID, String>();
        var exercises = new TreeMap<UUID, Count>();
        var muscles = new LinkedHashMap<MuscleKey, Count>();
        var groups = new LinkedHashMap<GroupKey, Count>();
        var mappings = new HashMap<UUID, List<Mapping>>();
        input.mappings().forEach(m -> mappings.computeIfAbsent(m.standardId(), ignored -> new ArrayList<>()).add(m));
        for (var session : input.sessions()) {
            sessions.put(session.id(), session);
            for (var weight : session.initial().weightExercises()) {
                exerciseNames.putIfAbsent(weight.standardExerciseId(), weight.exerciseName());
                total.planned += weight.setCount();
                dayWeights.get(session.date()).planned += weight.setCount();
                exercises.computeIfAbsent(weight.standardExerciseId(), ignored -> new Count()).planned += weight.setCount();
                contribute(mappings.getOrDefault(weight.standardExerciseId(), List.of()), muscles, groups, weight.setCount(), null);
            }
        }
        long unmapped = 0;
        for (var set : input.sets()) {
            var session = sessions.get(set.sessionId());
            if (session == null) throw new IllegalStateException("Set outside selected sessions");
            exerciseNames.putIfAbsent(set.standardId(), set.name());
            total.add(set);
            dayWeights.get(session.date()).add(set);
            exercises.computeIfAbsent(set.standardId(), ignored -> new Count()).add(set);
            var links = mappings.getOrDefault(set.standardId(), List.of());
            if (links.stream().noneMatch(m -> m.role().equals("PRIMARY"))) unmapped++;
            contribute(links, muscles, groups, 0, set);
        }
        var cardio = new LinkedHashMap<CardioKey, CardioCount>();
        long cardioMs = 0;
        for (var fact : input.cardio()) {
            var session = sessions.get(fact.sessionId());
            if (session == null) throw new IllegalStateException("Cardio outside selected sessions");
            var plan = session.applied().cardioActivities().stream().filter(p -> p.sessionActivityId().equals(fact.activityId()))
                    .map(p -> new CardioKey(p.plan().get("kind").asString(), p.plan().get("name").asString())).findFirst()
                    .orElseGet(() -> session.initial().cardioActivities().stream().filter(p -> p.sessionActivityId().equals(fact.activityId()))
                            .map(p -> new CardioKey(p.plan().kind(), p.plan().name())).findFirst().orElse(new CardioKey("UNCLASSIFIED", "미분류 유산소")));
            var count = cardio.computeIfAbsent(plan, ignored -> new CardioCount());
            count.ms = Math.addExact(count.ms, fact.activeMs());
            cardioMs = Math.addExact(cardioMs, fact.activeMs());
            dayCardio.merge(session.date(), fact.activeMs(), Math::addExact);
            if (fact.distance() == null) count.missing++; else { count.distance = count.distance.add(fact.distance()); count.distances++; }
        }
        return new StatisticsResponse(owner, Long.toString(generation), query.fromDate(), query.toDateExclusive(),
                "STARTED_SESSIONS_ONLY", null, null, total.value(), Long.toString(cardioMs), Long.toString(unmapped),
                dayWeights.entrySet().stream().map(e -> new Day(e.getKey(), e.getValue().value(), Long.toString(dayCardio.getOrDefault(e.getKey(), 0L)))).toList(),
                exercises.entrySet().stream().map(e -> new Exercise(e.getKey(), exerciseNames.get(e.getKey()), e.getValue().value())).toList(),
                muscles.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().id().toString() + e.getKey().role()))
                        .map(e -> new Muscle(e.getKey().id(), e.getKey().name(), e.getKey().group(), e.getKey().role(), e.getValue().value())).toList(),
                groups.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().group() + e.getKey().role()))
                        .map(e -> new Group(e.getKey().group(), e.getKey().role(), e.getValue().value())).toList(),
                cardio.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().kind() + e.getKey().name()))
                        .map(e -> new Cardio(e.getKey().kind(), e.getKey().name(), Long.toString(e.getValue().ms),
                                e.getValue().distances == 0 ? null : decimal(e.getValue().distance), Long.toString(e.getValue().missing))).toList(),
                input.recent());
    }

    private static void contribute(List<Mapping> links, Map<MuscleKey, Count> muscles, Map<GroupKey, Count> groups, int planned, SetFact set) {
        var seenMuscles = new HashSet<MuscleKey>();
        var seenGroups = new HashSet<GroupKey>();
        for (var mapping : links) {
            var muscle = new MuscleKey(mapping.muscleId(), mapping.name(), mapping.group(), mapping.role());
            var group = new GroupKey(mapping.group(), mapping.role());
            if (seenMuscles.add(muscle)) add(muscles.computeIfAbsent(muscle, ignored -> new Count()), planned, set);
            if (seenGroups.add(group)) add(groups.computeIfAbsent(group, ignored -> new Count()), planned, set);
        }
    }
    private static void add(Count count, int planned, SetFact set) { count.planned += planned; if (set != null) count.add(set); }
    private static String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static final class Count {
        long planned, completed, reps, withoutVolume;
        BigDecimal volume = BigDecimal.ZERO;
        void add(SetFact set) {
            completed++;
            reps = Math.addExact(reps, set.reps());
            // Existing BODYWEIGHT numbers do not distinguish body mass from explicit extra load.
            if ("BODYWEIGHT".equals(set.loadType())) withoutVolume++;
            else volume = volume.add(set.weight().multiply(BigDecimal.valueOf(set.reps())));
        }
        Weight value() { return new Weight(Long.toString(planned), Long.toString(completed), Long.toString(reps),
                completed == withoutVolume ? null : decimal(volume), Long.toString(withoutVolume)); }
    }
    private static final class CardioCount { long ms, missing, distances; BigDecimal distance = BigDecimal.ZERO; }
}
