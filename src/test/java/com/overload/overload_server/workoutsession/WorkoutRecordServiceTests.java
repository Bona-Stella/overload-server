package com.overload.overload_server.workoutsession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.core.JacksonException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.overload.overload_server.workoutsession.WorkoutSnapshotData.*;
import static com.overload.overload_server.workoutsession.WorkoutRecordData.*;
import static com.overload.overload_server.workoutsession.WorkoutSessionFailure.Reason.*;

class WorkoutRecordServiceTests {
    final WorkoutSessionRepository sessions=mock(WorkoutSessionRepository.class);
    final WorkoutSnapshotRepository data=mock(WorkoutSnapshotRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final SimpleTransactionStatus transaction=new SimpleTransactionStatus();
    final WorkoutSession session=new WorkoutSession();
    final Instant at=Instant.parse("2026-11-01T00:00:00Z");
    final UUID exercise=UUID.randomUUID(),record=UUID.randomUUID();
    WorkoutRecordService service;

    @BeforeEach void prepare() {
        session.id=UUID.randomUUID();session.userId=UUID.randomUUID();session.status="FINISHED";session.version=7;
        session.startedAt=at;session.endedAt=at.plusSeconds(3600);session.startedDeviceId=UUID.randomUUID();session.editorDeviceId=session.startedDeviceId;
        when(sessions.generation(session.userId,true)).thenReturn(0L);
        when(sessions.find(session.userId,0,session.id,true)).thenReturn(Optional.of(session));
        when(data.exercises(session.id)).thenReturn(List.of(new SessionExercise(session,new Exercise(exercise,"WEIGHT",UUID.randomUUID()),"test")));
        when(manager.getTransaction(any())).thenReturn(transaction);
        var clocks=new StaticListableBeanFactory();clocks.addBean("clock",Clock.fixed(at.plusSeconds(7200),ZoneOffset.UTC));
        service=new WorkoutRecordService(sessions,data,mock(WorkoutAssessment.class),clocks.getBeanProvider(Clock.class),manager);
    }

    @ParameterizedTest @NullSource @ValueSource(strings={"0","0.000001","5","CLEAR"})
    void identicalExistingRecoveryPreservesLoadAndReturnsUnchangedAck(String amount) {
        var load=amount==null?null:new BodyweightLoad(amount.equals("CLEAR")?null:amount);
        var submitted=value(load,new Context("TODAY_ONLY",null));var old=spy(new PerformedSet(session.id,submitted));
        when(data.sets(session.id)).thenReturn(List.of(old));var stored=old.bodyweightLoad;
        var result=service.recover(session.userId,session.id,recovery(submitted));
        assertThat(result).isEqualTo(new WorkoutRecordData.Ack(session.userId,"0",session.id,"FINISHED","7",false,List.of(),List.of(),List.of(record),null));
        assertThat(old.bodyweightLoad).isEqualTo(stored);assertThat(session.version).isEqualTo(7);
        verify(old).value();verify(data,never()).insert(any());verify(manager).commit(transaction);verify(manager,never()).rollback(any());
    }

    @Test void recoveredOriginalClaimStillMatchesAnUnverifiedStoredContext() {
        var claim=new Context("APPROVAL_REQUEST",UUID.randomUUID());var load=new BodyweightLoad("5");
        var old=new PerformedSet(session.id,value(load,new Context("UNVERIFIED",null)));
        when(data.sets(session.id)).thenReturn(List.of(old));
        when(data.changes(session.id)).thenReturn(List.of(new RecordChangeEvent(session.id,"SET",record,"RECOVERED",null,
                value(load,claim),Map.of("claimedWeightContext",claim),at)));
        assertThat(service.recover(session.userId,session.id,recovery(value(load,claim))).alreadyPresentIds()).containsExactly(record);
        assertThat(old.contextKind).isEqualTo("UNVERIFIED");assertThat(old.approvalRequestId).isNull();
        verify(data,never()).insert(any());
    }

    @ParameterizedTest @ValueSource(strings={"not json","[]","{\"additionalWeightKg\":{}}"})
    void unreadableStoredLoadKeepsItsFailureAndRollsBackEvenWhenARecoveredClaimExists(String stored) {
        var submitted=value(new BodyweightLoad("5"),new Context("TODAY_ONLY",null));
        var old=new PerformedSet(session.id,submitted);old.bodyweightLoad=stored;
        when(data.sets(session.id)).thenReturn(List.of(old));
        when(data.changes(session.id)).thenReturn(List.of(new RecordChangeEvent(session.id,"SET",record,"RECOVERED",null,
                submitted,Map.of("claimedWeightContext",submitted.weightContext()),at)));
        assertThatThrownBy(()->service.recover(session.userId,session.id,recovery(submitted))).isInstanceOf(JacksonException.class);
        assertThat(session.version).isEqualTo(7);verify(manager).rollback(transaction);verify(manager,never()).commit(any());verify(data,never()).insert(any());
    }

    @Test void deletedRecordIsRejectedBeforeItsStoredLoadIsRead() {
        var submitted=value(null,new Context("TODAY_ONLY",null));var old=spy(new PerformedSet(session.id,submitted));
        old.deletedAt=at;old.bodyweightLoad="not json";when(data.sets(session.id)).thenReturn(List.of(old));
        assertThatThrownBy(()->service.recover(session.userId,session.id,recovery(submitted)))
                .isInstanceOfSatisfying(WorkoutSessionFailure.class,failure->assertThat(failure.reason).isEqualTo(RECORD_DELETED));
        verify(old,never()).value();verify(manager).rollback(transaction);verify(data,never()).insert(any());
    }

    SetValue value(BodyweightLoad load,Context context) {
        return new SetValue(record,exercise,1,"80",8,at.plusSeconds(60),context,null,"BODYWEIGHT",load);
    }
    Recovery recovery(SetValue submitted) {
        return new Recovery(new Scope(0,7),new Origin(session.startedDeviceId,"0","12"),List.of(submitted),List.of());
    }
}
