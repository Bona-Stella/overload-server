package com.overload.overload_server.workoutsession;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;

@RestController
@RequestMapping("/api/v1/workout-sessions")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkoutSessionController {
    private final WorkoutSessionService service;
    private final WorkoutSnapshotService snapshots;
    private final WorkoutRecordService records;
    WorkoutSessionController(WorkoutSessionService service,WorkoutSnapshotService snapshots,WorkoutRecordService records) { this.service = service; this.snapshots=snapshots;this.records=records; }
    @PatchMapping(value="/{id}/records",consumes="application/json")
    ResponseEntity<WorkoutRecordData.Ack> edit(@AuthenticationPrincipal SessionPrincipal principal,@PathVariable String id,
            @RequestBody String body,HttpServletRequest request) {
        validate(request,false);return ok(records.edit(principal.userId(),WorkoutSessionRequests.uuid(id),WorkoutRecordRequests.edit(body)));
    }
    @PostMapping(value="/{id}/recoveries",consumes="application/json")
    ResponseEntity<WorkoutRecordData.Ack> recover(@AuthenticationPrincipal SessionPrincipal principal,@PathVariable String id,
            @RequestBody String body,HttpServletRequest request) {
        validate(request,false);return ok(records.recover(principal.userId(),WorkoutSessionRequests.uuid(id),WorkoutRecordRequests.recover(body)));
    }
    @DeleteMapping(value="/{id}",consumes="application/json")
    ResponseEntity<WorkoutRecordData.Ack> delete(@AuthenticationPrincipal SessionPrincipal principal,@PathVariable String id,
            @RequestBody String body,HttpServletRequest request) {
        validate(request,false);return ok(records.delete(principal.userId(),WorkoutSessionRequests.uuid(id),WorkoutRecordRequests.delete(body)));
    }
    @PostMapping(value="/{id}/closed-approval-resolutions",consumes="application/json")
    ResponseEntity<WorkoutRecordData.ResolutionAck> resolveClosed(@AuthenticationPrincipal SessionPrincipal principal,@PathVariable String id,
            @RequestBody String body,HttpServletRequest request) {
        validate(request,false);return ok(snapshots.resolveClosed(principal.userId(),WorkoutSessionRequests.uuid(id),WorkoutRecordRequests.resolve(body)));
    }
    @GetMapping("/{id}/progress-assessment")
    ResponseEntity<WorkoutRecordData.ProgressAssessment> progressAssessment(@AuthenticationPrincipal SessionPrincipal principal,@PathVariable String id,HttpServletRequest request) {
        validate(request,true);return ok(records.progressAssessment(principal.userId(),WorkoutSessionRequests.uuid(id)));
    }
    @GetMapping("/{id}/assessment")
    ResponseEntity<WorkoutRecordData.Assessment> assessment(@AuthenticationPrincipal SessionPrincipal principal,@PathVariable String id,HttpServletRequest request) {
        validate(request,true);return ok(records.assessment(principal.userId(),WorkoutSessionRequests.uuid(id)));
    }
    @GetMapping("/{id}/record-change-events")
    ResponseEntity<WorkoutRecordData.Changes> changes(@AuthenticationPrincipal SessionPrincipal principal,@PathVariable String id,HttpServletRequest request) {
        validate(request,true);return ok(records.changes(principal.userId(),WorkoutSessionRequests.uuid(id)));
    }
    @PostMapping(value="/{id}/finish",consumes="application/json")
    ResponseEntity<WorkoutSnapshotData.FinishAck> finish(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable String id,@RequestBody String body,HttpServletRequest request) {
        validate(request,false);
        return ok(snapshots.finish(principal.userId(),WorkoutSessionRequests.uuid(id),WorkoutSnapshotRequests.finish(body)));
    }
    @PutMapping(value="/{id}/snapshot",consumes="application/json")
    ResponseEntity<WorkoutSnapshotData.Ack> snapshot(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable String id,@RequestBody String body,HttpServletRequest request){
        validate(request,false);return ok(snapshots.save(principal.userId(),WorkoutSessionRequests.uuid(id),WorkoutSnapshotRequests.save(body)));
    }
    @PostMapping(value="/{id}/approval-resolutions",consumes="application/json")
    ResponseEntity<WorkoutSnapshotData.Resolution> resolve(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable String id,@RequestBody String body,HttpServletRequest request){
        validate(request,false);return ok(snapshots.resolve(principal.userId(),WorkoutSessionRequests.uuid(id),WorkoutSnapshotRequests.resolve(body)));
    }

    @PostMapping(consumes = "application/json")
    ResponseEntity<WorkoutSessionResponses.Detail> start(@AuthenticationPrincipal SessionPrincipal principal,
            @RequestBody String body, HttpServletRequest request) {
        validate(request, false);
        var result = service.start(principal.userId(), WorkoutSessionRequests.start(body));
        return result.created() ? ResponseEntity.created(URI.create("/api/v1/workout-sessions/" + result.session().id()))
                .cacheControl(CacheControl.noStore()).body(result.session()) : ok(result.session());
    }
    @PostMapping(value="/deload",consumes="application/json")
    ResponseEntity<WorkoutSessionResponses.Detail> startDeload(@AuthenticationPrincipal SessionPrincipal p,@RequestBody String body,HttpServletRequest request) {
        validate(request,false);var result=service.startDeload(p.userId(),WorkoutSessionRequests.deloadStart(body));
        return result.created()?ResponseEntity.created(URI.create("/api/v1/workout-sessions/"+result.session().id())).cacheControl(CacheControl.noStore()).body(result.session()):ok(result.session());
    }
    @PostMapping(value="/{id}/abandon",consumes="application/json")
    ResponseEntity<WorkoutSessionResponses.Detail> abandon(@AuthenticationPrincipal SessionPrincipal p,@PathVariable String id,@RequestBody String body,HttpServletRequest request){validate(request,false);return ok(service.abandon(p.userId(),WorkoutSessionRequests.uuid(id),WorkoutSessionRequests.abandon(body)));}
    @GetMapping("/deload/{period}") ResponseEntity<?> period(@AuthenticationPrincipal SessionPrincipal p,@PathVariable String period,HttpServletRequest request){validate(request,true);return ok(service.period(p.userId(),WorkoutSessionRequests.uuid(period)));}
    @GetMapping("/active")
    ResponseEntity<WorkoutSessionResponses.Active> active(@AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        validate(request, true); return ok(service.active(principal.userId()));
    }
    @GetMapping("/closed")
    ResponseEntity<WorkoutSessionResponses.ClosedPage> closed(@AuthenticationPrincipal SessionPrincipal principal,
            @RequestParam org.springframework.util.MultiValueMap<String,String> query,HttpServletRequest request) {
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw WorkoutSessionRequests.invalid();
        return ok(service.closed(principal.userId(),WorkoutSessionRequests.closed(query)));
    }
    @GetMapping("/{id}")
    ResponseEntity<WorkoutSessionResponses.Detail> detail(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable String id, HttpServletRequest request) {
        validate(request, true); return ok(service.detail(principal.userId(), WorkoutSessionRequests.uuid(id)));
    }
    @PostMapping(value = "/{id}/takeover", consumes = "application/json")
    ResponseEntity<WorkoutSessionResponses.Detail> takeover(@AuthenticationPrincipal SessionPrincipal principal,
            @PathVariable String id, @RequestBody String body, HttpServletRequest request) {
        validate(request, false);
        return ok(service.takeover(principal.userId(), WorkoutSessionRequests.uuid(id), WorkoutSessionRequests.takeover(body)));
    }
    private static <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private static void validate(HttpServletRequest request, boolean read) {
        if (request.getQueryString() != null && !request.getQueryString().isEmpty()
                || read && (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null)) throw WorkoutSessionRequests.invalid();
    }
}
