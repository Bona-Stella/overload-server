package com.overload.overload_server.deload;

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
    record Options(UUID userId,String dataGeneration,List<Option> options){}
    record Current(UUID userId,String dataGeneration,DeloadPeriodRepository.Stored period){}
    private final DeloadPeriodRepository data;private final Clock clock;
    DeloadPeriodService(DeloadPeriodRepository data,ObjectProvider<Clock> clocks){this.data=data;clock=clocks.getIfAvailable(Clock::systemUTC);}
    EligibilityView eligibility(UUID owner){long g=data.generation(owner,false);return new EligibilityView(owner,Long.toString(g),data.eligibility(owner,g,now()),data.active(owner,g).isPresent());}
    Options options(UUID owner){long g=data.generation(owner,false);var e=data.eligibility(owner,g,now());return new Options(owner,Long.toString(g),DeloadPlanGenerator.options(data.source(owner,g,e,false)));}
    Current current(UUID owner){long g=data.generation(owner,false);return new Current(owner,Long.toString(g),data.active(owner,g).orElse(null));}
    Preview preview(UUID owner,long generation,Choice choice){if(data.generation(owner,false)!=generation)throw DeloadPeriodFailure.conflict("DATA_GENERATION_MISMATCH");return preview(owner,generation,choice,false,now());}
    private Preview preview(UUID owner,long generation,Choice choice,boolean lock,Instant at) {
        var e=data.eligibility(owner,generation,at);var source=data.source(owner,generation,e,lock);var generated=DeloadPlanGenerator.generate(source,choice);
        var normalized=new Choice(choice.method(),choice.split(),DeloadPlanGenerator.dates(source,choice));
        String token=hash(Arrays.asList(1,source,e.revision(),normalized));
        return new Preview(owner,Long.toString(generation),e,source,normalized,generated,token);
    }
    @Transactional(transactionManager="workoutTransactionManager",isolation=Isolation.READ_COMMITTED,readOnly=false)
    Current start(UUID owner,DeloadPeriodRequests.Start request) {
        if(data.generation(owner,true)!=request.generation())throw DeloadPeriodFailure.conflict("DATA_GENERATION_MISMATCH");
        String requestHash=hash(Arrays.asList(request.generation(),request.previewToken(),request.choice()));
        var previous=data.request(owner,request.generation(),request.requestId());
        if(previous.isPresent()){if(!previous.get().hash().equals(requestHash))throw DeloadPeriodFailure.conflict("DELOAD_REQUEST_CONFLICT");return new Current(owner,Long.toString(request.generation()),previous.get().period());}
        if(data.active(owner,request.generation()).isPresent())throw DeloadPeriodFailure.conflict("DELOAD_ACTIVE_PERIOD");
        var at=now();var p=preview(owner,request.generation(),request.choice(),true,at);
        if(!p.eligibility().eligible())throw DeloadPeriodFailure.conflict("DELOAD_NOT_ELIGIBLE");
        if(!p.generated().ready())throw DeloadPeriodFailure.conflict("DELOAD_PLAN_REQUIRES_POLICY");
        if(!p.previewToken().equals(request.previewToken()))throw DeloadPeriodFailure.conflict("DELOAD_PREVIEW_STALE");
        var checked=preview(owner,request.generation(),request.choice(),true,at);
        if(!checked.previewToken().equals(p.previewToken())||!checked.eligibility().eligible())throw DeloadPeriodFailure.conflict("DELOAD_PREVIEW_STALE");
        var stored=data.insert(owner,request.generation(),request.requestId(),requestHash,hash(p.source()),p.source(),p.choice(),p.generated(),at);
        return new Current(owner,Long.toString(request.generation()),stored);
    }
    private Instant now(){return clock.instant().truncatedTo(ChronoUnit.MICROS);}
    static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(DeloadPeriodRepository.JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
