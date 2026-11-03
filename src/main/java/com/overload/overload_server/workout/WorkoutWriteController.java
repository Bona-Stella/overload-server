package com.overload.overload_server.workout;

import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkoutWriteController {

	private final WorkoutWriteService service;
	WorkoutWriteController(WorkoutWriteService service) { this.service = service; }

	@PatchMapping("/api/v1/workout-settings")
	ResponseEntity<WorkoutSettingsResponse> settings(@AuthenticationPrincipal SessionPrincipal principal,
			@RequestBody WorkoutWriteRequests.ChangeRest body, HttpServletRequest request) {
		validate(request);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.changeRest(principal.userId(), body));
	}

	private static void validate(HttpServletRequest request) {
		if (request.getQueryString() != null && !request.getQueryString().isEmpty()) {
			throw new WorkoutWriteFailure(WorkoutWriteFailure.Reason.INVALID_REQUEST);
		}
	}
}
