package com.overload.overload_server.statistics;

import com.overload.overload_server.deload.DeloadPeriodLifecycle;
import com.overload.overload_server.deload.DeloadWeeklyFinalization;
import com.overload.overload_server.routine.WeeklyScheduleRotation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="overload.workout.review.enabled",havingValue="true",matchIfMissing=true)
class DailyTrainingReviewJob {
    private final DailyTrainingReviewService review;
    private final DeloadWeeklyFinalization weekly;
    private final DeloadPeriodLifecycle lifecycle;
    private final WeeklyScheduleRotation rotation;
    DailyTrainingReviewJob(DailyTrainingReviewService review,DeloadWeeklyFinalization weekly,DeloadPeriodLifecycle lifecycle,WeeklyScheduleRotation rotation){
        this.review=review;this.weekly=weekly;this.lifecycle=lifecycle;this.rotation=rotation;
    }
    @Scheduled(fixedDelayString="${overload.workout.review.delay-ms:60000}")
    void review(){
        lifecycle.completeDuePeriods();
        rotation.runBatch();
        review.reviewBatch();
        weekly.runBatch();
    }
}
