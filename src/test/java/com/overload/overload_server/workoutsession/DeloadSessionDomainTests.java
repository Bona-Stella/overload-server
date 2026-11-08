package com.overload.overload_server.workoutsession;

import com.overload.overload_server.deload.DeloadTemporaryPlans;
import com.overload.overload_server.routine.RoutineResponses;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeloadSessionDomainTests {
    final JsonMapper json=JsonMapper.builder().build();
    @Test void normalInitialPlanRetainsItsOldJsonShapeAndDeloadHasExplicitNonRoutineOrigin() {
        var normal=new InitialPlan(2,UUID.randomUUID(),"normal","0",List.of(),List.of());assertThat(json.readTree(normal.json()).has("deloadOrigin")).isFalse();
        var w=new RoutineResponses.Weight(UUID.randomUUID(),UUID.randomUUID(),"lift",null,"PLATE",2,5,8,"50",null,null);
        var content=new DeloadTemporaryPlans.Content("temporary",List.of(w),List.of());
        var source=new DeloadTemporaryPlans.Executable(UUID.randomUUID(),UUID.randomUUID(),"4",LocalDate.of(2026,11,8),"Asia/Seoul",content,json.readTree("{\"sourceRoutineIds\":[\"one\",\"two\"]}"));
        var initial=InitialPlan.deload(source);assertThat(initial.routineId()).isNull();assertThat(initial.weightExercises().getFirst().initialWeightKg()).isEqualTo("50");assertThat(initial.weightExercises().getFirst().approvedWeightKg()).isNull();
        assertThat(initial.deloadOrigin().get("initialSource").get("sourceRoutineIds").size()).isEqualTo(2);assertThat(initial.weightExercises().getFirst().sessionExerciseId()).isNotEqualTo(w.id());
    }
    @Test void deloadAssessmentNeverRequestsGeneralBenchmarksOrReturnsWeightAdvice() {
        var sessions=mock(WorkoutSessionRepository.class);var records=mock(WorkoutSnapshotRepository.class);var session=new WorkoutSession();session.id=UUID.randomUUID();session.deloadPeriodId=UUID.randomUUID();session.initialPlan=new InitialPlan(2,null,"deload","0",List.of(new InitialPlan.Weight(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"lift",null,null,1,1,1,"50",null)),List.of()).json();
        when(records.sets(session.id)).thenReturn(List.of());var result=new WorkoutAssessment(sessions,records).assess(session,Instant.EPOCH);
        assertThat(result.overallWeightPlanComplete()).isNull();assertThat(result.exercises().getFirst().eligible()).isFalse();assertThat(result.exercises().getFirst().reasonCodes()).containsExactly("DELOAD_SESSION");assertThat(result.exercises().getFirst().advice()).isEqualTo("UNAVAILABLE");verify(sessions,never()).finishedBefore(any());
    }
    @Test void deloadSetCannotClaimInitialOrRequestedNormalApproval() {
        var session=new WorkoutSession();session.deloadPeriodId=UUID.randomUUID();
        for(String kind:List.of("INITIAL_APPROVED","APPROVAL_REQUEST")) {
            var value=new WorkoutSnapshotData.SetValue(UUID.randomUUID(),UUID.randomUUID(),1,"50",8,Instant.EPOCH,new WorkoutSnapshotData.Context(kind,kind.equals("APPROVAL_REQUEST")?UUID.randomUUID():null),null,null);var set=new PerformedSet(UUID.randomUUID(),value);
            assertThatThrownBy(()->WorkoutSnapshotService.context(session,set,Map.of())).isInstanceOfSatisfying(WorkoutSessionFailure.class,e->assertThat(e.reason).isEqualTo(WorkoutSessionFailure.Reason.DELOAD_APPROVAL_FORBIDDEN));
        }
    }
}
