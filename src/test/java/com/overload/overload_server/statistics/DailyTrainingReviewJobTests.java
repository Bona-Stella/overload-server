package com.overload.overload_server.statistics;

import com.overload.overload_server.deload.DeloadWeeklyFinalization;
import com.overload.overload_server.deload.DeloadPeriodLifecycle;
import com.overload.overload_server.routine.WeeklyScheduleRotation;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class DailyTrainingReviewJobTests {
    @Test void sameReviewTickRunsG3BeforeIndependentWeeklyAnalysis() {
        var g3=mock(DailyTrainingReviewService.class);var g4=mock(DeloadWeeklyFinalization.class);
        var lifecycle=mock(DeloadPeriodLifecycle.class);
        var rotation=mock(WeeklyScheduleRotation.class);
        new DailyTrainingReviewJob(g3,g4,lifecycle,rotation).review();var order=inOrder(lifecycle,rotation,g3,g4);
        order.verify(lifecycle).completeDuePeriods();order.verify(rotation).runBatch();
        order.verify(g3).reviewBatch();order.verify(g4).runBatch();order.verifyNoMoreInteractions();
    }
}
