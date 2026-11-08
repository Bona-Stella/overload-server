package com.overload.overload_server.deload;

import com.overload.overload_server.completion.ExactFraction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.UUID;
import static com.overload.overload_server.deload.DeloadAnalysisFailure.Reason.*;

@Service
@Transactional(transactionManager="workoutTransactionManager")
public class DeloadAnalysisService {
    public record Fraction(String numerator,String denominator) { }
    public record View(UUID userId,String dataGeneration,boolean enabled,String version,String epoch,Instant epochStartedAt,
                       String timeZone,String calculationStatus,Fraction epochInitialWeeks,Fraction confirmedWeeks,
                       LocalDate confirmedFrom,LocalDate confirmedThrough,LocalDate blockedAt,Fraction recommendationThreshold,boolean recommendationReady,
                       LocalDate analysisResumesOn) { }
    private final DeloadAnalysisRepository repository;
    private final DeloadAnalysisQuery query;
    public DeloadAnalysisService(DeloadAnalysisRepository repository,DeloadAnalysisQuery query) { this.repository=repository;this.query=query; }
    @Transactional(transactionManager="workoutTransactionManager",readOnly=true,isolation=Isolation.REPEATABLE_READ)
    View read(UUID owner) { long generation=repository.generation(owner,false);return view(owner,generation,repository.current(owner,generation)); }
    @Transactional(transactionManager="workoutTransactionManager",isolation=Isolation.READ_COMMITTED)
    View save(UUID owner,DeloadAnalysisRequests.Save input) {
        requireGeneration(owner,input.generation());
        var current=repository.current(owner,input.generation());
        if(current.version()!=input.expectedVersion())throw new DeloadAnalysisFailure(DELOAD_VERSION_CONFLICT);
        requireScheduleZone(owner,input.generation(),input.timeZone());
        var next=current.change(input.enabled(),input.timeZone(),repository.now());
        if(next!=current)repository.append(owner,input.generation(),next);
        return view(owner,input.generation(),next);
    }
    /** Called only inside the existing START owner transaction; a session's travel zone stays independent. */
    @Transactional(transactionManager="workoutTransactionManager",propagation=Propagation.MANDATORY)
    public void bindForStart(UUID owner,long generation,String zone) {
        var current=repository.current(owner,generation);
        if(current.timeZone()!=null)return;
        String scheduled=repository.scheduleZone(owner,generation);
        bind(owner,generation,current,scheduled==null?zone:scheduled);
    }
    @Transactional(transactionManager="workoutTransactionManager",propagation=Propagation.MANDATORY)
    public void bindForSchedule(UUID owner,long generation,String zone) {
        var current=repository.current(owner,generation);
        bind(owner,generation,current,zone);
    }
    private void bind(UUID owner,long generation,DeloadAnalysisState current,String zone) {
        DeloadAnalysisRequests.zone(zone);
        var next=current.change(current.enabled(),zone,repository.now());
        if(next!=current)repository.append(owner,generation,next);
    }
    /** Internal, explicit adoption only. No controller, scheduler, startup hook or default T_adopt. */
    @Transactional(transactionManager="workoutTransactionManager",isolation=Isolation.READ_COMMITTED)
    public boolean adoptExisting(UUID owner,long generation,Instant adoptedAt) {
        if(adoptedAt==null||adoptedAt.getNano()%1000!=0)throw DeloadAnalysisRequests.invalid();
        requireGeneration(owner,generation);
        if(repository.find(owner,generation).isPresent())return false;
        if(adoptedAt.isBefore(repository.createdAt(owner))||adoptedAt.isAfter(repository.now()))throw DeloadAnalysisRequests.invalid();
        repository.append(owner,generation,new DeloadAnalysisState(0,true,0,adoptedAt,adoptedAt,repository.scheduleZone(owner,generation),"ADOPTED_INITIAL"));
        return true;
    }
    private void requireGeneration(UUID owner,long generation) {
        if(repository.generation(owner,true)!=generation)throw new DeloadAnalysisFailure(DATA_GENERATION_MISMATCH);
    }
    private void requireScheduleZone(UUID owner,long generation,String zone) {
        String scheduled=repository.scheduleZone(owner,generation);
        if(scheduled!=null&&!scheduled.equals(zone))throw new DeloadAnalysisFailure(DELOAD_TIME_ZONE_CONFLICT);
    }
    private View view(UUID owner,long generation,DeloadAnalysisState state) {
        var result=query.read(owner,generation,state);
        return new View(owner,Long.toString(generation),state.enabled(),Long.toString(state.version()),Long.toString(state.epoch()),state.epochStartedAt(),state.timeZone(),
            result.status(),new Fraction("0","1"),fraction(result.weeks()),
            result.from(),result.through(),result.blockedAt(),fraction(DeloadAnalysisQuery.THRESHOLD),result.recommendationReady(),state.enabled()?state.resumeDate():null);
    }
    private static Fraction fraction(ExactFraction value) {return value==null?null:new Fraction(value.numerator().toString(),value.denominator().toString());}
}
