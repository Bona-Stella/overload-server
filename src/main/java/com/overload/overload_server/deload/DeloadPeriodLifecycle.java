package com.overload.overload_server.deload;

import com.overload.overload_server.routine.WeeklyScheduleRotation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.overload.overload_server.deload.DeloadPeriodFailure.conflict;

@Service
public class DeloadPeriodLifecycle {
    private static final Logger LOG=LoggerFactory.getLogger(DeloadPeriodLifecycle.class);
    private static final String MANUAL="MANUAL_COMPLETION";
    private static final String AUTOMATIC="AUTOMATIC_COMPLETION";
    private static final String CANCELLED="USER_CANCELLED";
    private final DeloadPeriodRepository periods;
    private final DeloadAnalysisRepository analysis;
    private final DeloadWeeklyRepository weekly;
    private final Clock clock;
    private final WeeklyScheduleRotation rotation;
    private final TransactionTemplate writes;

    DeloadPeriodLifecycle(DeloadPeriodRepository periods, DeloadAnalysisRepository analysis, DeloadWeeklyRepository weekly,
                          WeeklyScheduleRotation rotation, ObjectProvider<Clock> clocks, @Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
        this.periods=periods;
        this.analysis=analysis;
        this.weekly=weekly;
        this.rotation=rotation;
        clock=clocks.getIfAvailable(Clock::systemUTC);
        writes=new TransactionTemplate(manager);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Transactional(transactionManager="workoutTransactionManager",isolation=Isolation.READ_COMMITTED)
    public DeloadPeriodService.Current end(UUID owner, UUID id, DeloadPeriodRequests.End request, boolean cancel) {
        long generation=request.generation();
        if(periods.generation(owner,true)!=generation)throw conflict("DATA_GENERATION_MISMATCH");
        var period=periods.lifecycle(owner,generation,id);
        if(request.requestId().equals(period.endRequestId())) {
            if(!sameTransition(period,request,cancel))throw conflict("DELOAD_REQUEST_CONFLICT");
        } else {
            if(periods.endRequestUsed(owner,generation,request.requestId()))throw conflict("DELOAD_REQUEST_CONFLICT");
            if(period.status().equals("ACTIVE")) {
                if(period.version()!=request.expectedVersion())throw conflict("DELOAD_PERIOD_VERSION_CONFLICT");
                var at=now();
                boolean overdue=!at.isBefore(period.deadline());
                if(cancel&&overdue)throw conflict("DELOAD_AUTO_COMPLETION_DUE");
                requireNoActiveWorkout(owner,generation);
                if(cancel) {
                    if(periods.hasActivity(id))throw conflict("DELOAD_ACTIVITY_RECORDED");
                    if(periods.hasUnabandonedWorkout(id))throw conflict("DELOAD_SESSION_ABANDON_REQUIRED");
                }
                close(owner,generation,period,request.requestId(),cancel?CANCELLED:overdue?AUTOMATIC:MANUAL,at);
            } else if(cancel||!AUTOMATIC.equals(period.endReason())||period.endRequestId()!=null||!sameTransition(period,request,false)) {
                throw conflict("DELOAD_PERIOD_NOT_ACTIVE");
            }
        }
        return new DeloadPeriodService.Current(owner,Long.toString(generation),periods.detail(owner,generation,id));
    }

    private boolean sameTransition(DeloadPeriodRepository.Lifecycle period, DeloadPeriodRequests.End request, boolean cancel) {
        return period.version()>0&&period.version()-1==request.expectedVersion()
            &&period.status().equals(cancel?"CANCELLED":"COMPLETED");
    }

    public int completeDuePeriods() {
        int ended=0;
        for(var due:periods.due(now())) {
            try {
                if(Boolean.TRUE.equals(writes.execute(tx->completeDue(due))))ended++;
            } catch(RuntimeException failure) {
                LOG.warn("Deload auto completion rolled back type={}",failure.getClass().getSimpleName());
            }
        }
        return ended;
    }

    private boolean completeDue(DeloadPeriodRepository.DuePeriod due) {
        if(!weekly.lock(due.owner(),due.generation(),true))return false;
        var period=periods.lifecycle(due.owner(),due.generation(),due.period());
        var at=now();
        if(!period.status().equals("ACTIVE")||at.isBefore(period.deadline())||periods.activeWorkout(due.owner(),due.generation()))return false;
        close(due.owner(),due.generation(),period,null,AUTOMATIC,at);
        return true;
    }

    private void requireNoActiveWorkout(UUID owner, long generation) {
        if(periods.activeWorkout(owner,generation))throw conflict("ACTIVE_SESSION_EXISTS");
    }

    private void close(UUID owner, long generation, DeloadPeriodRepository.Lifecycle period, UUID request, String reason, Instant at) {
        if(period.version()==Long.MAX_VALUE||at.isBefore(period.updatedAt()))throw conflict("WRITE_CONFLICT");
        boolean automatic=AUTOMATIC.equals(reason);
        boolean cancelled=CANCELLED.equals(reason);
        var closedAt=automatic?period.deadline().minus(1,ChronoUnit.MICROS):at;
        var processedDate=at.atZone(period.zone()).toLocalDate();
        var closedDate=automatic?period.maximumEndDate():processedDate;
        periods.end(owner,generation,period,request,cancelled?"CANCELLED":"COMPLETED",reason,closedAt,closedDate,at);
        weekly.invalidateProtection(owner,generation,period.startDate(),automatic?processedDate.minusDays(1):closedDate,at);
        if(!cancelled)analysis.resetAfterCompletion(owner,generation,period.id(),closedAt,at);
        var returnDate = cancelled || automatic ? processedDate : closedDate.plusDays(1);
        rotation.resumeAfterDeload(owner,generation,returnDate,period.zone());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
