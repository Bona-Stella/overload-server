package com.overload.overload_server.statistics;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
@Transactional(transactionManager = "workoutTransactionManager", readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class StatisticsService {
    private final StatisticsRepository repository;
    private final PlanningRepository planning;
    StatisticsService(StatisticsRepository repository,PlanningRepository planning) { this.repository = repository; this.planning=planning; }
    StatisticsOverview overview(UUID owner,StatisticsQuery period) {
        long generation=repository.generation(owner);
        var facts=repository.read(owner,generation,period);
        var plan=PlanningCalculator.calculate(owner,generation,period,facts,planning.read(owner,generation,period));
        var weights=plan.days().stream().filter(d->d.plan()!=null).flatMap(d->d.plan().weightPlacements().stream()
                .map(w->new StatisticsCalculator.PlannedWeight(d.date(),w.standardExerciseId(),w.exerciseName(),w.setCount()))).toList();
        boolean known=plan.unknownDays()==0,verifiable=known&&plan.unverifiableDays()==0;
        boolean week=period.fromDate().getDayOfWeek()==java.time.DayOfWeek.MONDAY&&period.toDateExclusive().equals(period.fromDate().plusDays(7));
        var statistics=StatisticsCalculator.calculate(owner,generation,period,facts,weights).withPlanning(known?"FULL_SCHEDULE_HISTORY":"PARTIAL_SCHEDULE_HISTORY",
                known?plan.knownPlannedDays():null,verifiable&&week&&plan.knownPlannedDays()>0?plan.effective().percent(plan.knownPlannedDays()):null);
        return new StatisticsOverview(statistics,verifiable?plan.effective().decimal():null,plan.unknownDays(),plan.unverifiableDays(),plan.days(),StatisticsCalculator.cardioByKind(facts), StatisticsCalculator.cardioByCategory(facts));
    }
}
