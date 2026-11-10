package com.overload.overload_server.catalog;

import com.overload.overload_server.security.SessionPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "overload.gif", name = "serving-enabled", havingValue = "true")
public class ExerciseGifAuthorizationController {
    static final String PATH = "/internal/media/exercise-gif/authorize";
    static final String TOKEN_HEADER = "X-Overload-Gif-Proxy-Token";
    static final String SOURCE_HEADER = "X-Overload-Gif-Source-Id";
    private final ExerciseGifAuthorizationService service;

    ExerciseGifAuthorizationController(ExerciseGifAuthorizationService service) {
        this.service = service;
    }

    @GetMapping(PATH)
    ResponseEntity<Void> authorize(@AuthenticationPrincipal SessionPrincipal principal,
                                   @RequestHeader(name = TOKEN_HEADER, required = false) String suppliedToken,
                                   @RequestHeader(name = SOURCE_HEADER, required = false) String sourceId) {
        if (principal == null) return result(401);
        return switch (service.authorize(principal.userId(), suppliedToken, sourceId)) {
            case ALLOWED -> ResponseEntity.noContent().cacheControl(CacheControl.noStore())
                    .header("X-Overload-Gif-Decision", "allow-v1").build();
            case FORBIDDEN -> result(403);
            case NOT_FOUND -> result(404);
        };
    }

    private static ResponseEntity<Void> result(int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
    }
}
