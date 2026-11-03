package com.overload.overload_server.workout;

final class WorkoutWriteFailure extends RuntimeException {

	enum Reason {
		INVALID_REQUEST(400), AUTHENTICATION_REQUIRED(401),
		DATA_GENERATION_MISMATCH(409), WORKOUT_SETTINGS_MISSING(500);

		final int status;
		Reason(int status) { this.status = status; }
	}

	final Reason reason;
	WorkoutWriteFailure(Reason reason) { super(reason.name()); this.reason = reason; }
}
