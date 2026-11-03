package com.overload.overload_server.auth.email;

import com.overload.overload_server.account.AccountIdentityService;
import com.overload.overload_server.security.SessionPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class EmailReauthenticationController {
	private final AccountIdentityService identities;
	private final EmailReauthenticationService reauthentication;

	EmailReauthenticationController(AccountIdentityService identities, EmailReauthenticationService reauthentication) {
		this.identities = identities; this.reauthentication = reauthentication;
	}

	@GetMapping("/api/v1/auth/identities")
	ResponseEntity<Map<String, List<AccountIdentityService.Summary>>> identities(@AuthenticationPrincipal SessionPrincipal principal) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("identities", identities.list(principal.userId())));
	}

	@PostMapping("/api/v1/auth/reauth/email/request")
	ResponseEntity<Map<String, Object>> request(@AuthenticationPrincipal SessionPrincipal principal,
			@RequestBody Request body, HttpServletRequest request) {
		rejectQuery(request);
		var challenge = reauthentication.request(principal.userId(), request);
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("status", "ACCEPTED", "challengeId", challenge));
	}

	@PostMapping("/api/v1/auth/reauth/email/verify")
	ResponseEntity<Void> verify(@AuthenticationPrincipal SessionPrincipal principal, @RequestBody Verification body,
			HttpServletRequest request, HttpServletResponse response) {
		rejectQuery(request);
		reauthentication.verify(principal.userId(), body.challengeId(), body.code(), request, response);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	public record Request() {
		@com.fasterxml.jackson.annotation.JsonAnySetter public void unexpected(String field,Object value){throw new IllegalArgumentException("Unexpected field");}
		@Override public String toString() { return "ReauthenticationRequest[redacted]"; }
	}
	public record Verification(UUID challengeId, String code) {
		@com.fasterxml.jackson.annotation.JsonAnySetter public void unexpected(String field,Object value){throw new IllegalArgumentException("Unexpected field");}
		@Override public String toString() { return "ReauthenticationVerification[redacted]"; }
	}

	@GetMapping("/api/v1/auth/reauth/email/contact")
	ResponseEntity<Map<String,Object>> contact(@AuthenticationPrincipal SessionPrincipal principal,HttpServletRequest request){
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reauthentication.status(principal.userId(),request));
	}
	@PostMapping("/api/v1/auth/reauth/email/registration/request")
	ResponseEntity<Map<String,Object>> enroll(@AuthenticationPrincipal SessionPrincipal principal,@RequestBody Enrollment body,HttpServletRequest request){
		rejectQuery(request);
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("status","ACCEPTED","challengeId",reauthentication.enrollRequest(principal.userId(),body.candidateId(),request)));
	}
	@PostMapping("/api/v1/auth/reauth/email/registration/verify")
	ResponseEntity<Void> enrolled(@AuthenticationPrincipal SessionPrincipal principal,@RequestBody EnrollmentVerification body,HttpServletRequest request,HttpServletResponse response){
		rejectQuery(request);
		reauthentication.enrollVerify(principal.userId(),body.candidateId(),body.challengeId(),body.code(),request,response);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}
	@PostMapping("/api/v1/auth/reauth/email/registration/cancel")
	ResponseEntity<Void> cancel(@AuthenticationPrincipal SessionPrincipal principal){reauthentication.cancel(principal.userId());return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();}
	public record Enrollment(UUID candidateId){
		@com.fasterxml.jackson.annotation.JsonAnySetter public void unexpected(String field,Object value){throw new IllegalArgumentException("Unexpected field");}
		@Override public String toString(){return "ContactEnrollment[redacted]";}
	}
	public record EnrollmentVerification(UUID candidateId,UUID challengeId,String code){
		@com.fasterxml.jackson.annotation.JsonAnySetter public void unexpected(String field,Object value){throw new IllegalArgumentException("Unexpected field");}
		@Override public String toString(){return "ContactEnrollmentVerification[redacted]";}
	}
	private static void rejectQuery(HttpServletRequest request){if(request.getParameterMap().keySet().stream().anyMatch(key->!key.equals("_csrf"))){throw new OtpFailure(400,"INVALID_REQUEST");}}
}
