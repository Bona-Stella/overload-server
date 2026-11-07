package com.overload.overload_server.routine;

final class RoutineFailure extends RuntimeException {
	enum Reason {
        DELOAD_ACTIVE_PERIOD(409), INVALID_REQUEST(400), SCHEDULE_CONTRACT_MISMATCH(409), AUTHENTICATION_REQUIRED(401), ROUTINE_NOT_FOUND(404),
		PLACEMENT_NOT_FOUND(404), EXERCISE_NOT_FOUND(404), DATA_GENERATION_MISMATCH(409),
		ROUTINE_VERSION_CONFLICT(409), ROUTINE_ID_CONFLICT(409), PLACEMENT_ID_CONFLICT(409),
		PLACEMENT_STATE_CONFLICT(409), SCHEDULE_VERSION_CONFLICT(409), SCHEDULE_TIME_ZONE_CONFLICT(409), ACTIVE_SESSION_EXISTS(409), WRITE_CONFLICT(409);
		final int status;
		Reason(int status) { this.status = status; }
	}
	final Reason reason;
	RoutineFailure(Reason reason) { super(reason.name()); this.reason = reason; }
}
