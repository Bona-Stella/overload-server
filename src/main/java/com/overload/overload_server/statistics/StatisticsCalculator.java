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
    record CardioFact(UUID sessionId, UUID activityId, long activeMs, BigDecimal distance,BigDecimal actualSpeedKmh,String distanceSource) {
        CardioFact(UUID sessionId,UUID activityId,long activeMs,BigDecimal distance){this(sessionId,activityId,activeMs,distance,null,null);}
    }
    record Mapping(UUID standardId, UUID muscleId, String name, String group, String role) { }
    record Input(List<Session> sessions, List<SetFact> sets, List<CardioFact> cardio, List<Mapping> mappings, List<Recent> recent) { }
    record PlannedWeight(LocalDate date, UUID standardId, String name, int sets) { }
    private record MuscleKey(UUID id, String name, String group, String role) { }
    private record GroupKey(String group, String role) { }
    private record CardioKey(String kind, String name) { }

    static StatisticsResponse calculate(UUID owner, long generation, StatisticsQuery query, Input input) {
        var plans=input.sessions().stream().flatMap(s->s.initial().weightExercises().stream()
                .map(w->new PlannedWeight(s.date(),w.standardExerciseId(),w.exerciseName(),w.setCount()))).toList();
        return calculate(owner,generation,query,input,plans);
    }
    static StatisticsResponse calculate(UUID owner, long generation, StatisticsQuery query, Input input, List<PlannedWeight> plans) {
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
        }
        for (var weight : plans) {
            exerciseNames.putIfAbsent(weight.standardId(), weight.name());
            total.planned += weight.sets();
            dayWeights.get(weight.date()).planned += weight.sets();
            exercises.computeIfAbsent(weight.standardId(), ignored -> new Count()).planned += weight.sets();
            contribute(mappings.getOrDefault(weight.standardId(), List.of()), muscles, groups, weight.sets(), null);
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
            var plan = cardioKey(session,fact);
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
    private static CardioKey cardioKey(Session session,CardioFact fact){
        return session.applied().cardioActivities().stream().filter(p->p.sessionActivityId().equals(fact.activityId()))
                .map(p->new CardioKey(p.plan().get("kind").asString(),p.plan().get("name").asString())).findFirst()
                .orElseGet(()->session.initial().cardioActivities().stream().filter(p->p.sessionActivityId().equals(fact.activityId()))
                    .map(p->new CardioKey(p.plan().kind(),p.plan().name())).findFirst().orElse(new CardioKey("UNCLASSIFIED","미분류 유산소")));
    }
    static List<StatisticsOverview.CardioTotal> cardioByKind(Input input){
        var sessions=new HashMap<UUID,Session>();input.sessions().forEach(s->sessions.put(s.id(),s));
        class Totals {long ms,missing;final BigDecimal[] amounts={BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO};final long[] counts=new long[3];}
        var groups=new TreeMap<String,Totals>();
        for(var fact:input.cardio()){
            var group=groups.computeIfAbsent(cardioKey(sessions.get(fact.sessionId()),fact).kind(),ignored->new Totals());group.ms=Math.addExact(group.ms,fact.activeMs());
            if(fact.distance()==null){group.missing++;continue;}
            int source="MANUAL".equals(fact.distanceSource())?0:"CALCULATED".equals(fact.distanceSource())?1:2;
            group.amounts[source]=group.amounts[source].add(fact.distance());group.counts[source]++;
        }
        return groups.entrySet().stream().map(entry->{var t=entry.getValue();long count=t.counts[0]+t.counts[1]+t.counts[2];
            return new StatisticsOverview.CardioTotal(entry.getKey(),Long.toString(t.ms),count==0?null:decimal(t.amounts[0].add(t.amounts[1]).add(t.amounts[2])),
                t.counts[0]==0?null:decimal(t.amounts[0]),t.counts[1]==0?null:decimal(t.amounts[1]),t.counts[2]==0?null:decimal(t.amounts[2]),
                Long.toString(t.counts[0]),Long.toString(t.counts[1]),Long.toString(t.counts[2]),Long.toString(t.missing));}).toList();
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
