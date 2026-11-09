package com.overload.overload_server.routine;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/weekly-schedule")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WeeklyScheduleController {
    private final WeeklyScheduleService service;

    WeeklyScheduleController(WeeklyScheduleService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<WeeklySchedule.View> read(@AuthenticationPrincipal SessionPrincipal principal, HttpServletRequest request) {
        noQuery(request);
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw RoutineRequests.invalid();
        return response(200, service.read(principal.userId()));
    }

    @PostMapping(path = "/presets", consumes = "application/json")
    ResponseEntity<WeeklySchedule.View> create(@AuthenticationPrincipal SessionPrincipal principal, @RequestBody String raw,
                                               HttpServletRequest request) {
        noQuery(request);
        return response(201, service.create(principal.userId(), WeeklyScheduleRequests.create(raw)));
    }

    @PutMapping(path = "/presets/{id}/assignments", consumes = "application/json")
    ResponseEntity<WeeklySchedule.View> edit(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id,
                                             @RequestBody String raw, HttpServletRequest request) {
        noQuery(request);
        return response(200, service.edit(principal.userId(), RoutineRequests.uuid(id), WeeklyScheduleRequests.edit(raw)));
    }

    @PutMapping(path = "/active", consumes = "application/json")
    ResponseEntity<WeeklySchedule.View> activate(@AuthenticationPrincipal SessionPrincipal principal, @RequestBody String raw,
                                                 HttpServletRequest request) {
        noQuery(request);
        return response(200, service.activate(principal.userId(), WeeklyScheduleRequests.activate(raw)));
    }

    @PutMapping(path = "/presets/{id}/name", consumes = "application/json")
    ResponseEntity<WeeklySchedule.View> rename(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id,
                                               @RequestBody String raw, HttpServletRequest request) {
        noQuery(request);
        return response(200, service.rename(principal.userId(), RoutineRequests.uuid(id), WeeklyScheduleRequests.rename(raw)));
    }

    @PutMapping(path = "/rotation", consumes = "application/json")
    ResponseEntity<WeeklySchedule.View> rotation(@AuthenticationPrincipal SessionPrincipal principal, @RequestBody String raw,
                                                 HttpServletRequest request) {
        noQuery(request);
        return response(200, service.configureRotation(principal.userId(), WeeklyScheduleRequests.rotation(raw)));
    }

    @DeleteMapping(path = "/presets/{id}", consumes = "application/json")
    ResponseEntity<WeeklySchedule.View> delete(@AuthenticationPrincipal SessionPrincipal principal, @PathVariable String id,
                                               @RequestBody String raw, HttpServletRequest request) {
        noQuery(request);
        return response(200, service.delete(principal.userId(), RoutineRequests.uuid(id), WeeklyScheduleRequests.delete(raw)));
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw RoutineRequests.invalid();
    }

    private static ResponseEntity<WeeklySchedule.View> response(int status, WeeklySchedule.View body) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
