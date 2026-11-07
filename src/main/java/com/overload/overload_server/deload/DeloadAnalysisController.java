package com.overload.overload_server.deload;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/deload-analysis")
public class DeloadAnalysisController {
    private final DeloadAnalysisService service;
    public DeloadAnalysisController(DeloadAnalysisService service) { this.service=service; }
    @GetMapping
    ResponseEntity<DeloadAnalysisService.View> read(@AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest request) {
        noQuery(request);
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw DeloadAnalysisRequests.invalid();
        return ok(service.read(principal.userId()));
    }
    @PutMapping(path="/settings",consumes="application/json")
    ResponseEntity<DeloadAnalysisService.View> save(@AuthenticationPrincipal SessionPrincipal principal,@RequestBody String raw,HttpServletRequest request) {
        noQuery(request);return ok(service.save(principal.userId(),DeloadAnalysisRequests.parse(raw)));
    }
    private static void noQuery(HttpServletRequest request) {
        if(request.getQueryString()!=null&&!request.getQueryString().isEmpty())throw DeloadAnalysisRequests.invalid();
    }
    private static ResponseEntity<DeloadAnalysisService.View> ok(DeloadAnalysisService.View view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }
}
