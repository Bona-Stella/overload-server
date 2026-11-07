package com.overload.overload_server.statistics;

import com.overload.overload_server.deload.DeloadWeeklyFinalization;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class DailyTrainingReviewJobTests {
    @Test void sameReviewTickRunsG3BeforeIndependentWeeklyAnalysis() {
        var g3=mock(DailyTrainingReviewService.class);var g4=mock(DeloadWeeklyFinalization.class);
        new DailyTrainingReviewJob(g3,g4).review();var order=inOrder(g3,g4);
        order.verify(g3).reviewBatch();order.verify(g4).runBatch();order.verifyNoMoreInteractions();
    }
}
