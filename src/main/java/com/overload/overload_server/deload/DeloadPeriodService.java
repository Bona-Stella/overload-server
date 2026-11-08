package com.overload.overload_server.deload;

import com.overload.overload_server.routine.WeeklyScheduleRotation;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import static com.overload.overload_server.deload.DeloadPlan.*;

@Service
@Transactional(transactionManager="workoutTransactionManager",readOnly=true,isolation=Isolation.REPEATABLE_READ)
class DeloadPeriodService {
    record EligibilityView(UUID userId,String dataGeneration,DeloadPeriodRepository.Eligibility eligibility,boolean activePeriod){}
    record Preview(UUID userId,String dataGeneration,DeloadPeriodRepository.Eligibility eligibility,Source source,Choice choice,Generated generated,String previewToken){}
    record Options(UUID userId,String dataGeneration,UUID sourcePresetId,String sourcePresetVersion,List<Option> options){}
    record Current(UUID userId,String dataGeneration,DeloadPeriodRepository.Stored period){}
    record History(UUID userId,String dataGeneration,List<DeloadPeriodRepository.HistoryItem> periods,Integer nextOffset){}
    private final DeloadPeriodRepository data;private final Clock clock;
    private final WeeklyScheduleRotation rotation;
    DeloadPeriodService(DeloadPeriodRepository data,WeeklyScheduleRotation rotation,ObjectProvider<Clock> clocks){
        this.data=data;this.rotation=rotation;clock=clocks.getIfAvailable(Clock::systemUTC);
    }
    EligibilityView eligibility(UUID owner){long g=data.generation(owner,false);return new EligibilityView(owner,Long.toString(g),data.eligibility(owner,g,now()),data.active(owner,g).isPresent());}
    Options options(UUID owner,UUID presetId) {
        long generation=data.generation(owner,false);
        var e=data.eligibility(owner,generation,now());
        var source=data.source(owner,generation,e,presetId,false);
        return new Options(owner,Long.toString(generation),source.presetId(),source.presetVersion(),DeloadPlanGenerator.options(source));
    }
    Current current(UUID owner){long g=data.generation(owner,false);return new Current(owner,Long.toString(g),data.current(owner,g,now()).orElse(null));}
    Current detail(UUID owner,UUID period){long g=data.generation(owner,false);return new Current(owner,Long.toString(g),data.detail(owner,g,period));}
    History history(UUID owner,int offset) {
        long generation=data.generation(owner,false);
        var periods=data.history(owner,generation,offset);
        return new History(owner,Long.toString(generation),periods,periods.size()==50?offset+50:null);
    }
    Preview preview(UUID owner,long generation,UUID presetId,Choice choice) {
        if(data.generation(owner,false)!=generation)throw DeloadPeriodFailure.conflict("DATA_GENERATION_MISMATCH");
        return preview(owner,generation,presetId,choice,false,now());
    }
    private Preview preview(UUID owner,long generation,UUID presetId,Choice choice,boolean lock,Instant at) {
        var e=data.eligibility(owner,generation,at);var source=data.source(owner,generation,e,presetId,lock);var generated=DeloadPlanGenerator.generate(source,choice);
        var normalized=new Choice(choice.method(),choice.split(),DeloadPlanGenerator.dates(source,choice));
        String token=hash(List.of(2,source,e.revision(),normalized));
        return new Preview(owner,Long.toString(generation),e,source,normalized,generated,token);
    }
    @Transactional(transactionManager="workoutTransactionManager",isolation=Isolation.READ_COMMITTED,readOnly=false)
    Current start(UUID owner,DeloadPeriodRequests.Start request) {
        if(data.generation(owner,true)!=request.generation())throw DeloadPeriodFailure.conflict("DATA_GENERATION_MISMATCH");
        var requestEvidence=new ArrayList<Object>(List.of(request.generation(),request.previewToken(),request.choice()));
        if(request.presetId()!=null)requestEvidence.add(request.presetId());
        String requestHash=hash(requestEvidence);
        var previous=data.request(owner,request.generation(),request.requestId());
        if(previous.isPresent()){if(!previous.get().hash().equals(requestHash))throw DeloadPeriodFailure.conflict("DELOAD_REQUEST_CONFLICT");return new Current(owner,Long.toString(request.generation()),previous.get().period());}
        var at=now();
        if(data.current(owner,request.generation(),at).isPresent())throw DeloadPeriodFailure.conflict("DELOAD_ACTIVE_PERIOD");
        var p=checkPreview(owner,request,at);
        if(!p.eligibility().eligible())throw DeloadPeriodFailure.conflict("DELOAD_NOT_ELIGIBLE");
        if(!p.previewToken().equals(request.previewToken()))throw DeloadPeriodFailure.conflict("DELOAD_PREVIEW_STALE");
        if(!p.generated().ready())throw DeloadPeriodFailure.conflict("DELOAD_PLAN_REQUIRES_POLICY");
        var checked=checkPreview(owner,request,at);
        if(!checked.previewToken().equals(p.previewToken())||!checked.eligibility().eligible())throw DeloadPeriodFailure.conflict("DELOAD_PREVIEW_STALE");
        var stored=data.insert(owner,request.generation(),request.requestId(),requestHash,hash(p.source()),p.source(),p.choice(),p.generated(),at);
        rotation.pauseForDeload(owner,request.generation());
        return new Current(owner,Long.toString(request.generation()),stored);
    }
    private Preview checkPreview(UUID owner,DeloadPeriodRequests.Start request,Instant at) {
        try {
            return preview(owner,request.generation(),request.presetId(),request.choice(),true,at);
        } catch (DeloadPeriodFailure failure) {
            if (Set.of("INVALID_REQUEST","DELOAD_SOURCE_CHANGED","DELOAD_SOURCE_PRESET_NOT_FOUND").contains(failure.code()))
                throw DeloadPeriodFailure.conflict("DELOAD_PREVIEW_STALE");
            throw failure;
        }
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(DeloadPeriodRepository.JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
