package com.overload.overload_server.deload;

import org.slf4j.LoggerFactory;
import org.springframework.dao.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.*;

@RestControllerAdvice(assignableTypes = DeloadAnalysisController.class)
public class DeloadAnalysisErrors {
	public record Body(String code) { }
	@ExceptionHandler(DeloadAnalysisFailure.class)
	ResponseEntity<Body> failure(DeloadAnalysisFailure failure) { return response(failure.status(), failure.code()); }
	@ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class, MethodArgumentTypeMismatchException.class})
	ResponseEntity<Body> invalid() { return response(400, "INVALID_REQUEST"); }
	@ExceptionHandler(Exception.class)
	ResponseEntity<Body> failed(Exception failure) throws Exception {
		if (failure instanceof AuthenticationException || failure instanceof AccessDeniedException || failure instanceof ErrorResponse) throw failure;
		boolean unavailable = false, conflict = false;
		var pending = new ArrayDeque<Throwable>(); var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
		pending.add(failure);
		while (!pending.isEmpty()) {
			var cause = pending.removeFirst(); if (!visited.add(cause)) continue;
			if (cause instanceof SQLException sql && sql.getSQLState() != null) {
				String state = sql.getSQLState();
				conflict |= Set.of("23505", "40001", "40P01", "55P03").contains(state);
				unavailable |= state.startsWith("08") || Set.of("57P01", "57P02", "57P03").contains(state);
			}
			conflict |= cause instanceof OptimisticLockingFailureException || cause instanceof jakarta.persistence.OptimisticLockException;
			unavailable |= cause instanceof DataAccessResourceFailureException || cause instanceof TransientDataAccessResourceException
					|| cause instanceof QueryTimeoutException || cause instanceof SQLTimeoutException;
			if (cause.getCause() != null) pending.addLast(cause.getCause());
			Collections.addAll(pending, cause.getSuppressed());
			if (cause instanceof TransactionSystemException transaction && transaction.getApplicationException() != null) pending.addLast(transaction.getApplicationException());
		}
		return unavailable ? response(503, "DELOAD_DATA_UNAVAILABLE") : conflict ? response(409, "WRITE_CONFLICT")
				: response(500, "DELOAD_DATA_FAILED");
	}
	private static ResponseEntity<Body> response(int status, String code) {
		if (status >= 500) LoggerFactory.getLogger(DeloadAnalysisErrors.class).warn("Deload analysis request failed code={}", code);
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Body(code));
	}
}
