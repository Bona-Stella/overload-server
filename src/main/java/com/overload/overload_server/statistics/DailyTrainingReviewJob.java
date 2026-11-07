package com.overload.overload_server.statistics;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="overload.workout.review.enabled",havingValue="true",matchIfMissing=true)
class DailyTrainingReviewJob {
    private final DailyTrainingReviewService review;
    private final com.overload.overload_server.deload.DeloadWeeklyFinalization weekly;
    DailyTrainingReviewJob(DailyTrainingReviewService review,com.overload.overload_server.deload.DeloadWeeklyFinalization weekly){this.review=review;this.weekly=weekly;}
    @Scheduled(fixedDelayString="${overload.workout.review.delay-ms:60000}")
    void review(){review.reviewBatch();weekly.runBatch();}
}
