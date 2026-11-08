package com.overload.overload_server.deload;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/deload-periods")
class DeloadPeriodController {
    private final DeloadPeriodService service;
    private final DeloadTemporaryPlans plans;
    DeloadPeriodController(DeloadPeriodService service,DeloadTemporaryPlans plans){this.service=service;this.plans=plans;}
    @PutMapping(path="/{period}/plans/{plan}",consumes="application/json") ResponseEntity<?> edit(@AuthenticationPrincipal SessionPrincipal p,@PathVariable String period,@PathVariable String plan,@RequestBody String raw,HttpServletRequest r){query(r);return ok(plans.edit(p.userId(),DeloadPeriodRequests.id(period),DeloadPeriodRequests.edit(raw,DeloadPeriodRequests.id(plan))));}
    @PutMapping(path="/{period}/plans/{plan}/date",consumes="application/json") ResponseEntity<?> move(@AuthenticationPrincipal SessionPrincipal p,@PathVariable String period,@PathVariable String plan,@RequestBody String raw,HttpServletRequest r){query(r);return ok(plans.move(p.userId(),DeloadPeriodRequests.id(period),DeloadPeriodRequests.move(raw,DeloadPeriodRequests.id(plan))));}
    @DeleteMapping(path="/{period}/plans/{plan}",consumes="application/json") ResponseEntity<?> remove(@AuthenticationPrincipal SessionPrincipal p,@PathVariable String period,@PathVariable String plan,@RequestBody String raw,HttpServletRequest r){query(r);return ok(plans.remove(p.userId(),DeloadPeriodRequests.id(period),DeloadPeriodRequests.remove(raw,DeloadPeriodRequests.id(plan))));}
    @PutMapping(path="/{period}/method",consumes="application/json") ResponseEntity<?> method(@AuthenticationPrincipal SessionPrincipal p,@PathVariable String period,@RequestBody String raw,HttpServletRequest r){query(r);return ok(plans.change(p.userId(),DeloadPeriodRequests.id(period),DeloadPeriodRequests.change(raw)));}
    @GetMapping("/eligibility") ResponseEntity<?> eligibility(@AuthenticationPrincipal SessionPrincipal p,HttpServletRequest r){read(r);return ok(service.eligibility(p.userId()));}
    @GetMapping("/options") ResponseEntity<?> options(@AuthenticationPrincipal SessionPrincipal p,HttpServletRequest r){read(r);return ok(service.options(p.userId()));}
    @GetMapping("/current") ResponseEntity<?> current(@AuthenticationPrincipal SessionPrincipal p,HttpServletRequest r){read(r);return ok(service.current(p.userId()));}
    @PostMapping(path="/preview",consumes="application/json") ResponseEntity<?> preview(@AuthenticationPrincipal SessionPrincipal p,@RequestBody String raw,HttpServletRequest r){query(r);var input=DeloadPeriodRequests.preview(raw);return ok(service.preview(p.userId(),input.generation(),input.choice()));}
    @PostMapping(consumes="application/json") ResponseEntity<?> start(@AuthenticationPrincipal SessionPrincipal p,@RequestBody String raw,HttpServletRequest r){query(r);return ok(service.start(p.userId(),DeloadPeriodRequests.start(raw)));}
    private static void query(HttpServletRequest r){if(r.getQueryString()!=null&&!r.getQueryString().isEmpty())throw DeloadPeriodFailure.invalid();}
    private static void read(HttpServletRequest r){query(r);if(r.getContentLengthLong()>0||r.getHeader("Transfer-Encoding")!=null)throw DeloadPeriodFailure.invalid();}
    private static ResponseEntity<?> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
