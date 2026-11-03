package com.overload.overload_server.workout;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkoutReadController {

	private final WorkoutReadService service;

	WorkoutReadController(WorkoutReadService service) {
		this.service = service;
	}

	@GetMapping("/api/v1/workout-settings")
	ResponseEntity<WorkoutSettingsResponse> settings(@AuthenticationPrincipal SessionPrincipal principal,
			HttpServletRequest request) {
		validate(request);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.settings(principal.userId()));
	}

	private static void validate(HttpServletRequest request) {
		if ((request.getQueryString() != null && !request.getQueryString().isEmpty())
				|| request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null) {
			throw new WorkoutReadFailure(WorkoutReadFailure.Reason.INVALID_REQUEST);
		}
	}
}
