package com.overload.overload_server.deload;

import com.overload.overload_server.routine.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static com.overload.overload_server.deload.DeloadPlan.*;

/** One owner/version boundary for the small, period-scoped plan aggregate. No general routines are created. */
@Service
@Transactional(transactionManager="workoutTransactionManager",readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class DeloadTemporaryPlans {
    public record Content(String label,List<RoutineResponses.Weight> weights,List<RoutineResponses.Cardio> cardio) { }
    public record Executable(UUID periodId,UUID planId,String version,LocalDate date,String timeZone,Content content,JsonNode initialSource) { }
    record Edit(long generation,long version,UUID request,UUID plan,RoutineRequests.Create content) { }
    record Move(long generation,long version,UUID request,UUID plan,LocalDate date,UUID swap) { }
    record Remove(long generation,long version,UUID request,UUID plan) { }
    record Change(long generation,long version,UUID request,Choice choice) { }
    private record Period(UUID id,String status,long version,LocalDate start,String zone,UUID last,String hash,String source) { }
    private record Item(UUID id,long version,LocalDate date,Instant deleted,Content content,String initial) { }
    private final JdbcTemplate jdbc;private final DeloadPeriodRepository periods;private final Clock clock;
    private final com.overload.overload_server.catalog.StandardCardioCatalog cardioCatalog;
    DeloadTemporaryPlans(JdbcTemplate jdbc, DeloadPeriodRepository periods, ObjectProvider<Clock> clocks,
                         com.overload.overload_server.catalog.StandardCardioCatalog cardioCatalog) {
        this.jdbc = jdbc;
        this.periods = periods;
        this.cardioCatalog = cardioCatalog;
        clock = clocks.getIfAvailable(Clock::systemUTC);
    }
    public boolean hasActivity(UUID owner,long generation,UUID period) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workout_sessions WHERE user_id=? AND data_generation=? AND deload_period_id=? AND first_deload_activity_at IS NOT NULL)",Boolean.class,owner,generation,period));
    }
    /** The caller already owns FOR UPDATE in a workout transaction. */
    @Transactional(transactionManager="workoutTransactionManager",propagation=Propagation.MANDATORY)
    public Executable forStart(UUID owner,long generation,UUID plan,long expected,LocalDate date,String zone,Instant started) {
        var ids=jdbc.queryForList("SELECT period_id FROM deload_temporary_plans WHERE id=? AND user_id=? AND data_generation=?",UUID.class,plan,owner,generation);
        if(ids.isEmpty())throw DeloadPeriodFailure.conflict("DELOAD_PLAN_NOT_FOUND");
        var period=period(owner,generation,ids.getFirst());active(period);var item=item(period.id(),plan);unstarted(item);var today=today(period);
        if(item.version()!=expected)throw DeloadPeriodFailure.conflict("DELOAD_PLAN_VERSION_CONFLICT");
        if(!zone.equals(period.zone())||!date.equals(today)||!date.equals(item.date())||started.isBefore(period.start().atStartOfDay(ZoneId.of(zone)).toInstant())||date.isAfter(period.start().plusDays(9)))throw DeloadPeriodFailure.conflict("DELOAD_DATE_CONFLICT");
        var content=validateWeights(item.content(), item.content().weights());
        return new Executable(period.id(),plan,Long.toString(item.version()),item.date(),zone,content,DeloadPeriodRepository.JSON.readTree(item.initial()));
    }
    @Transactional(transactionManager="workoutTransactionManager",isolation=Isolation.READ_COMMITTED,readOnly=false)
    Object edit(UUID owner,UUID id,Edit input) {
        var p=lock(owner,input.generation(),id);String hash=DeloadPeriodService.hash(Arrays.asList("EDIT",input));if(retry(p,input.version(),input.request(),hash))return view(owner,input.generation());
        var item=item(id,input.plan());unstarted(item);var r=input.content();
        var weights=r.weightPlacements().stream().map(w->new RoutineResponses.Weight(w.id(),w.standardExerciseId(),"",w.manufacturerId(),w.loadType(),w.setCount(),w.minReps(),w.maxReps(),w.initialWeightKg().toPlainString(),null,null)).toList();
        var cardio=r.cardioPlacements().stream().map(c->{var v=c.plan();return new RoutineResponses.Cardio(c.id(),new RoutineResponses.Plan(v.kind(),v.name(),v.targetSeconds(),decimal(v.targetDistanceKm()),v.intensityMode()==null?null:new RoutineResponses.Intensity(v.intensityMode(),decimal(v.intensityValue())),decimal(v.inclinePercent()),decimal(v.resistance()),v.activityId(),v.standardExerciseId()),null);}).toList();
        var existingCardio = new HashMap<UUID, RoutineResponses.Plan>();
        for (var prior : item.content().cardio()) existingCardio.put(prior.id(), prior.plan());
        for (int i = 0; i < cardio.size(); i++) {
            var candidate = cardio.get(i);
            var prior = existingCardio.get(candidate.id());
            if (prior == null ? !cardioCatalog.selectable(r.cardioPlacements().get(i).plan())
                    : !candidate.plan().sameCardioIdentity(prior)) {
                throw DeloadPeriodFailure.conflict("DELOAD_EXERCISE_UNAVAILABLE");
            }
        }
        var content=validateWeights(new Content(r.name(),weights,cardio), item.content().weights());
        jdbc.update("UPDATE deload_temporary_plans SET current_plan=?::jsonb,version=version+1 WHERE id=?",DeloadPeriodRepository.JSON.writeValueAsString(content),item.id());finish(p,input.request(),hash);return view(owner,input.generation());
    }
    @Transactional(transactionManager="workoutTransactionManager",isolation=Isolation.READ_COMMITTED,readOnly=false)
    Object move(UUID owner,UUID id,Move input) {
        var p=lock(owner,input.generation(),id);String hash=DeloadPeriodService.hash(Arrays.asList("MOVE",input));if(retry(p,input.version(),input.request(),hash))return view(owner,input.generation());
        var item=item(id,input.plan());unstarted(item);validDate(p,input.date());
        var occupied=jdbc.queryForList("SELECT id FROM deload_temporary_plans WHERE period_id=? AND planned_date=? AND deleted_at IS NULL AND id<>?",UUID.class,id,input.date(),item.id());
        if(input.swap()==null&&!occupied.isEmpty()||input.swap()!=null&&(occupied.size()!=1||!occupied.getFirst().equals(input.swap())))throw DeloadPeriodFailure.conflict("DELOAD_DATE_OCCUPIED");
        if(input.swap()!=null){var other=item(id,input.swap());unstarted(other);validDate(p,item.date());jdbc.update("UPDATE deload_temporary_plans SET planned_date=?,version=version+1 WHERE id=?",item.date(),other.id());}
        jdbc.update("UPDATE deload_temporary_plans SET planned_date=?,version=version+1 WHERE id=?",input.date(),item.id());finish(p,input.request(),hash);return view(owner,input.generation());
    }
    @Transactional(transactionManager="workoutTransactionManager",isolation=Isolation.READ_COMMITTED,readOnly=false)
    Object remove(UUID owner,UUID id,Remove input) {
        var p=lock(owner,input.generation(),id);String hash=DeloadPeriodService.hash(Arrays.asList("DELETE",input));if(retry(p,input.version(),input.request(),hash))return view(owner,input.generation());
        var item=item(id,input.plan());unstarted(item);jdbc.update("UPDATE deload_temporary_plans SET deleted_at=?,version=version+1 WHERE id=?",Timestamp.from(clock.instant()),item.id());finish(p,input.request(),hash);return view(owner,input.generation());
    }
    @Transactional(transactionManager="workoutTransactionManager",isolation=Isolation.READ_COMMITTED,readOnly=false)
    Object change(UUID owner,UUID id,Change input) {
        var p=lock(owner,input.generation(),id);String hash=DeloadPeriodService.hash(Arrays.asList("METHOD",input));if(retry(p,input.version(),input.request(),hash))return view(owner,input.generation());
        if(hasActivity(owner,input.generation(),id)||Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workout_sessions WHERE user_id=? AND data_generation=? AND status='ACTIVE')",Boolean.class,owner,input.generation())))throw DeloadPeriodFailure.conflict("DELOAD_METHOD_FROZEN");
        var today=today(p);if(input.choice().method()!=Method.REST&&today.isAfter(p.start().plusDays(6)))throw DeloadPeriodFailure.conflict("DELOAD_GENERATION_WINDOW_CLOSED");
        var original=DeloadPeriodRepository.JSON.readValue(p.source(),Source.class);var source=new Source(owner,Long.toString(input.generation()),p.zone(),today,original.presetId(),original.presetVersion(),original.days(),original.classifications(),original.scheduleVersion());
        var generated=DeloadPlanGenerator.generate(source,input.choice());if(!generated.ready())throw DeloadPeriodFailure.conflict("DELOAD_PLAN_REQUIRES_POLICY");
        if(DeloadPlanGenerator.dates(source,input.choice()).stream().anyMatch(date->date.isAfter(p.start().plusDays(6))))throw DeloadPeriodFailure.conflict("DELOAD_INSUFFICIENT_DATES");
        // Finished (even empty) plans require explicit no-activity abandonment before replacing the method.
        if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workout_sessions WHERE deload_period_id=? AND abandoned_at IS NULL)",Boolean.class,id)))throw DeloadPeriodFailure.conflict("DELOAD_METHOD_FROZEN");
        jdbc.update("UPDATE deload_temporary_plans SET deleted_at=?,version=version+1 WHERE period_id=? AND deleted_at IS NULL",Timestamp.from(clock.instant()),id);
        for(int i=0;i<generated.plans().size();i++){var plan=generated.plans().get(i);jdbc.update("INSERT INTO deload_temporary_plans(id,user_id,data_generation,period_id,position,planned_date,initial_plan) VALUES (?,?,?,?,?,?,?::jsonb)",UUID.randomUUID(),owner,input.generation(),id,i,plan.date(),DeloadPeriodRepository.JSON.writeValueAsString(plan));}
        jdbc.update("UPDATE deload_periods SET current_method=?,current_split=? WHERE id=?",input.choice().method().name(),input.choice().split().name(),id);finish(p,input.request(),hash);return view(owner,input.generation());
    }
    private Period lock(UUID owner,long generation,UUID id){if(periods.generation(owner,true)!=generation)throw DeloadPeriodFailure.conflict("DATA_GENERATION_MISMATCH");var p=period(owner,generation,id);active(p);if(today(p).isAfter(p.start().plusDays(9)))throw DeloadPeriodFailure.conflict("DELOAD_PERIOD_WINDOW_CLOSED");return p;}
    private Period period(UUID owner,long generation,UUID id){return jdbc.query("SELECT * FROM deload_periods WHERE id=? AND user_id=? AND data_generation=?",(r,i)->new Period(id,r.getString("status"),r.getLong("version"),r.getObject("start_date",LocalDate.class),r.getString("time_zone"),r.getObject("last_change_id",UUID.class),r.getString("last_change_hash"),r.getString("source_snapshot")),id,owner,generation).stream().findFirst().orElseThrow(()->DeloadPeriodFailure.conflict("DELOAD_PERIOD_NOT_FOUND"));}
    private Item item(UUID period,UUID id){var item=jdbc.query("SELECT * FROM deload_temporary_plans WHERE id=? AND period_id=?",(r,i)->new Item(id,r.getLong("version"),r.getObject("planned_date",LocalDate.class),r.getTimestamp("deleted_at")==null?null:r.getTimestamp("deleted_at").toInstant(),DeloadPeriodRepository.JSON.readValue(r.getString("current_plan"),Content.class),r.getString("initial_plan")),id,period).stream().findFirst().orElseThrow(()->DeloadPeriodFailure.conflict("DELOAD_PLAN_NOT_FOUND"));if(item.deleted()!=null)throw DeloadPeriodFailure.conflict("DELOAD_PLAN_DELETED");return item;}
    private void unstarted(Item item){if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM workout_sessions WHERE deload_plan_id=? AND abandoned_at IS NULL)",Boolean.class,item.id())))throw DeloadPeriodFailure.conflict("DELOAD_PLAN_FROZEN");if(item.version()==Long.MAX_VALUE)throw DeloadPeriodFailure.conflict("WRITE_CONFLICT");}
    private void active(Period p){if(!p.status().equals("ACTIVE"))throw DeloadPeriodFailure.conflict("DELOAD_PERIOD_NOT_ACTIVE");}
    private LocalDate today(Period p){return clock.instant().atZone(ZoneId.of(p.zone())).toLocalDate();}
    private void validDate(Period p,LocalDate date){if(date.isBefore(today(p))||date.isBefore(p.start())||date.isAfter(p.start().plusDays(9)))throw DeloadPeriodFailure.conflict("DELOAD_DATE_CONFLICT");}
    private boolean retry(Period p,long version,UUID request,String hash){if(request.equals(p.last())){if(!hash.equals(p.hash()))throw DeloadPeriodFailure.conflict("DELOAD_REQUEST_CONFLICT");return true;}if(p.version()!=version)throw DeloadPeriodFailure.conflict("DELOAD_PERIOD_VERSION_CONFLICT");if(version==Long.MAX_VALUE)throw DeloadPeriodFailure.conflict("WRITE_CONFLICT");return false;}
    private void finish(Period p,UUID request,String hash){jdbc.update("UPDATE deload_periods SET version=version+1,last_change_id=?,last_change_hash=?,updated_at=greatest(updated_at,?) WHERE id=?",request,hash,Timestamp.from(clock.instant()),p.id());}
    private Object view(UUID owner,long generation){return new DeloadPeriodService.Current(owner,Long.toString(generation),periods.active(owner,generation).orElseThrow());}
    private static String decimal(java.math.BigDecimal n){return n==null?null:n.stripTrailingZeros().toPlainString();}
    private record CatalogWeight(String name, boolean selectable) { }

    private Content validateWeights(Content value, List<RoutineResponses.Weight> existingWeights) {
        var existing = new HashMap<UUID, UUID>();
        for (var weight : existingWeights) existing.put(weight.id(), weight.standardExerciseId());
        var ids = value.weights().stream().map(RoutineResponses.Weight::standardExerciseId).distinct().sorted().toList();
        var catalogWeights = new HashMap<UUID, CatalogWeight>();
        if (!ids.isEmpty()) {
            jdbc.query("""
                    SELECT id, name, is_active AND muscle_targets_reviewed AND splits_reviewed AND record_method='SETS_REPS'
                    FROM standard_exercise WHERE id IN (%s) ORDER BY id FOR SHARE
                    """.formatted(marks(ids.size())), (org.springframework.jdbc.core.RowCallbackHandler) row ->
                    catalogWeights.put(row.getObject(1, UUID.class), new CatalogWeight(row.getString(2), row.getBoolean(3))), ids.toArray());
        }
        var manufacturers=value.weights().stream().map(RoutineResponses.Weight::manufacturerId).filter(Objects::nonNull).distinct().toList();
        if(!manufacturers.isEmpty()&&jdbc.queryForList("SELECT id FROM manufacturer WHERE id IN("+marks(manufacturers.size())+")",UUID.class,manufacturers.toArray()).size()!=manufacturers.size())throw DeloadPeriodFailure.invalid();
        var weights=new ArrayList<RoutineResponses.Weight>();
        for(var w:value.weights()) {
            var definition = catalogWeights.get(w.standardExerciseId());
            boolean preserved = w.standardExerciseId().equals(existing.get(w.id()));
            if (definition == null || (!preserved && !definition.selectable())) {
                throw DeloadPeriodFailure.conflict("DELOAD_EXERCISE_UNAVAILABLE");
            }
            weights.add(new RoutineResponses.Weight(w.id(),w.standardExerciseId(),definition.name(),w.manufacturerId(),w.loadType(),w.setCount(),w.minReps(),w.maxReps(),w.initialWeightKg(),null,null));
        }
        return new Content(value.label(),List.copyOf(weights),value.cardio());
    }
    private static String marks(int count){return String.join(",",Collections.nCopies(count,"?"));}
}
