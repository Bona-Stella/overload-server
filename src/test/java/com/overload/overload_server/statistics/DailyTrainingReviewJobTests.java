package com.overload.overload_server.statistics;

import com.overload.overload_server.deload.DeloadWeeklyFinalization;
import com.overload.overload_server.deload.DeloadPeriodLifecycle;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class DailyTrainingReviewJobTests {
    @Test void sameReviewTickRunsG3BeforeIndependentWeeklyAnalysis() {
        var g3=mock(DailyTrainingReviewService.class);var g4=mock(DeloadWeeklyFinalization.class);
        var lifecycle=mock(DeloadPeriodLifecycle.class);
        new DailyTrainingReviewJob(g3,g4,lifecycle).review();var order=inOrder(lifecycle,g3,g4);
        order.verify(lifecycle).completeDuePeriods();order.verify(g3).reviewBatch();order.verify(g4).runBatch();order.verifyNoMoreInteractions();
    }
}
