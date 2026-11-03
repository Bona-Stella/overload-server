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
    private final GrowthService growth;
    StatisticsController(StatisticsService service,GrowthService growth) { this.service = service; this.growth=growth; }
    @GetMapping("/api/v1/statistics/overview")
    ResponseEntity<StatisticsOverview> overview(@AuthenticationPrincipal SessionPrincipal principal,
            @RequestParam MultiValueMap<String,String> query,HttpServletRequest request) {
        if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw StatisticsQuery.invalid();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.overview(principal.userId(),StatisticsQuery.parse(query)));
    }
    @GetMapping("/api/v1/statistics/growth/exercises")
    ResponseEntity<GrowthResponse.Search> exercises(@AuthenticationPrincipal SessionPrincipal principal,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request){
        noBody(request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(growth.search(principal.userId(),GrowthQuery.parse(query,true,true)));
    }
    @GetMapping("/api/v1/statistics/growth/exercises/{standardId}")
    ResponseEntity<GrowthResponse.Options> growthOptions(@AuthenticationPrincipal SessionPrincipal principal,@PathVariable String standardId,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request){
        noBody(request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(growth.options(principal.userId(),GrowthQuery.id(standardId),GrowthQuery.parse(query,false,false)));
    }
    @GetMapping("/api/v1/statistics/growth/exercises/{standardId}/placements/{placementId}")
    ResponseEntity<GrowthResponse.History> growthHistory(@AuthenticationPrincipal SessionPrincipal principal,@PathVariable String standardId,@PathVariable String placementId,@RequestParam MultiValueMap<String,String> query,HttpServletRequest request){
        noBody(request);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(growth.history(principal.userId(),GrowthQuery.id(standardId),GrowthQuery.id(placementId),GrowthQuery.parse(query,false,true)));
    }
    private static void noBody(HttpServletRequest request){if(request.getContentLengthLong()>0||request.getHeader("Transfer-Encoding")!=null)throw StatisticsQuery.invalid();}
}
