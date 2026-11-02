package com.overload.overload_server.routine;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/weekly-schedule")
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class WeeklyScheduleController {
    private final WeeklyScheduleService service;
    WeeklyScheduleController(WeeklyScheduleService service){this.service=service;}
    @GetMapping ResponseEntity<WeeklySchedule.View> read(@AuthenticationPrincipal SessionPrincipal principal,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        if(!query.keySet().equals(Set.of("timeZone"))||query.get("timeZone").size()!=1||request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw RoutineRequests.invalid();
        return ok(service.read(principal.userId(),WeeklyScheduleRequests.zone(query.getFirst("timeZone"))));
    }
    @PutMapping(consumes="application/json") ResponseEntity<WeeklySchedule.View> save(@AuthenticationPrincipal SessionPrincipal principal,@RequestBody String raw,HttpServletRequest request) {
        if(request.getQueryString()!=null&&!request.getQueryString().isEmpty())throw RoutineRequests.invalid();
        return ok(service.save(principal.userId(),WeeklyScheduleRequests.parse(raw)));
    }
    @GetMapping("/history") ResponseEntity<WeeklySchedule.Page> history(@AuthenticationPrincipal SessionPrincipal principal,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        if(!query.keySet().equals(Set.of("beforeVersion"))||query.get("beforeVersion").size()!=1||request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw RoutineRequests.invalid();
        String raw=query.getFirst("beforeVersion");if(raw==null||!raw.matches("[1-9][0-9]{0,18}"))throw RoutineRequests.invalid();
        long before;try{before=Long.parseLong(raw);}catch(NumberFormatException failure){throw RoutineRequests.invalid();}
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(principal.userId(),before));
    }
    private static ResponseEntity<WeeklySchedule.View> ok(WeeklySchedule.View body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
