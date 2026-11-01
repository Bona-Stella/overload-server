package com.overload.overload_server.workout;

import com.overload.overload_server.catalog.CatalogReadController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Workout and catalog reads share this contract; authentication retains its own boundary. */
@RestControllerAdvice(assignableTypes = {WorkoutReadController.class, CatalogReadController.class})
public class WorkoutReadErrors {

	private static final Logger LOG = LoggerFactory.getLogger(WorkoutReadErrors.class);

	public record Body(String code) { }

	@ExceptionHandler(WorkoutReadFailure.class)
	ResponseEntity<Body> failure(WorkoutReadFailure failure) {
		return response(failure.reason.status, failure.reason.name());
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
			MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
			MethodArgumentTypeMismatchException.class})
	ResponseEntity<Body> invalidRequest() {
		return response(400, "INVALID_REQUEST");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Body> readFailed(Exception failure) throws Exception {
		// Let the existing Security handlers and MVC protocol status handling finish their work.
		if (failure instanceof AuthenticationException || failure instanceof AccessDeniedException
				|| failure instanceof ErrorResponse) {
			throw failure;
		}
		return unavailable(failure) ? response(503, "WORKOUT_DATA_UNAVAILABLE")
				: response(500, "WORKOUT_DATA_READ_FAILED");
	}

	static boolean unavailable(Throwable failure) {
		// Includes transaction-begin connection failures; mapping/grammar/integrity errors stay 500.
		var pending = new ArrayDeque<Throwable>();
		var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
		pending.add(failure);
		while (!pending.isEmpty()) {
			var cause = pending.removeFirst();
			if (!visited.add(cause)) continue;
			if (cause instanceof DataAccessResourceFailureException || cause instanceof TransientDataAccessResourceException
					|| cause instanceof QueryTimeoutException || cause instanceof SQLTimeoutException
					|| (cause instanceof SQLException sql && sql.getSQLState() != null
						&& (sql.getSQLState().startsWith("08") || Set.of("57P01", "57P02", "57P03").contains(sql.getSQLState())))) {
				return true;
			}
			if (cause.getCause() != null) pending.addLast(cause.getCause());
			Collections.addAll(pending, cause.getSuppressed());
			// A disconnected connection can fail again during rollback, retaining the original SQL failure here.
			if (cause instanceof TransactionSystemException transaction && transaction.getApplicationException() != null) {
				pending.addLast(transaction.getApplicationException());
			}
		}
		return false;
	}

	private static ResponseEntity<Body> response(int status, String code) {
		if (status >= 500) LOG.warn("Workout read failed code={}", code);
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new Body(code));
	}
}
