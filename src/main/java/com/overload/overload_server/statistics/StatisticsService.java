package com.overload.overload_server.statistics;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.UUID;

@Service
@Transactional(transactionManager = "workoutTransactionManager", readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class StatisticsService {
    private final StatisticsRepository repository;
    StatisticsService(StatisticsRepository repository) { this.repository = repository; }
    StatisticsResponse read(UUID owner, StatisticsQuery period) {
        long generation = repository.generation(owner);
        return StatisticsCalculator.calculate(owner, generation, period, repository.read(owner, generation, period));
    }
}
