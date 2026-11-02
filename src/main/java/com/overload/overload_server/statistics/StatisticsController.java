package com.overload.overload_server.statistics;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class StatisticsController {
    private final StatisticsService service;
    StatisticsController(StatisticsService service) { this.service = service; }
    @GetMapping("/api/v1/statistics")
    ResponseEntity<StatisticsResponse> read(@AuthenticationPrincipal SessionPrincipal principal,
            @RequestParam MultiValueMap<String, String> query, HttpServletRequest request) {
        if (request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) throw StatisticsQuery.invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(principal.userId(), StatisticsQuery.parse(query)));
    }
}
