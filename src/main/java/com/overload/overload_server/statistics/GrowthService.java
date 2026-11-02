package com.overload.overload_server.statistics;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.util.UUID;

@Service
@Transactional(transactionManager="workoutTransactionManager",readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class GrowthService {
    private final GrowthRepository repository;private final StatisticsRepository owners;private final Clock clock;
    GrowthService(GrowthRepository repository,StatisticsRepository owners,ObjectProvider<Clock> clocks){this.repository=repository;this.owners=owners;this.clock=clocks.getIfAvailable(Clock::systemUTC);}
    GrowthResponse.Search search(UUID owner,GrowthQuery query){
        long g=owners.generation(owner);var range=query.range(clock.instant());var rows=repository.search(owner,g,query,range);
        return new GrowthResponse.Search(owner,Long.toString(g),query.period(),range,query.search(),query.offset(),rows.stream().limit(50).toList(),rows.size()>50?query.offset()+50:null);
    }
    GrowthResponse.Options options(UUID owner,UUID standard,GrowthQuery query){
        long g=owners.generation(owner);var range=query.range(clock.instant());String name=repository.knownExercise(owner,g,standard);
        return new GrowthResponse.Options(owner,Long.toString(g),query.period(),range,standard,name,repository.placements(owner,g,standard,range));
    }
    GrowthResponse.History history(UUID owner,UUID standard,UUID placement,GrowthQuery query){
        long g=owners.generation(owner);var range=query.range(clock.instant());repository.knownExercise(owner,g,standard);
        var found=repository.placements(owner,g,standard,range).stream().filter(p->p.placementId().equals(placement)).findFirst().orElseThrow(()->new StatisticsFailure(404,"GROWTH_PLACEMENT_NOT_FOUND"));
        var rows=repository.points(owner,g,standard,placement,query,range);
        return new GrowthResponse.History(owner,Long.toString(g),query.period(),range,standard,found,query.offset(),rows.stream().limit(100).toList(),rows.size()>100?query.offset()+100:null);
    }
}
