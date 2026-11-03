package com.overload.overload_server.workout;

import com.overload.overload_server.persistence.TransactionExecution;
import com.overload.overload_server.workoutsettings.UserWorkoutSettingsRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

@Service
public class WorkoutWriteService {

	private final WorkoutOwnerRepository owners;
	private final UserWorkoutSettingsRepository settings;
	private final TransactionTemplate transaction;

	WorkoutWriteService(WorkoutOwnerRepository owners,
			UserWorkoutSettingsRepository settings,
			@Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
		this.owners = owners;
		this.settings = settings;
		this.transaction = new TransactionTemplate(manager);
	}

	public WorkoutSettingsResponse changeRest(UUID userId, WorkoutWriteRequests.ChangeRest request) {
		return TransactionExecution.execute(transaction, () -> {
			owner(userId, request.dataGeneration());
			var value = settings.findByUserIdAndDataGeneration(userId, request.dataGeneration())
					.orElseThrow(() -> new WorkoutWriteFailure(WorkoutWriteFailure.Reason.WORKOUT_SETTINGS_MISSING));
			value.changeRest(request.restLowSeconds(), request.restMediumSeconds(), request.restHighSeconds(), Instant.now());
			settings.flush();
			return new WorkoutSettingsResponse(userId, request.dataGeneration(), value.getRestLowSeconds(),
					value.getRestMediumSeconds(), value.getRestHighSeconds());
		});
	}

	private void owner(UUID userId, long expected) {
		long actual = owners.findGenerationForWrite(userId)
				.orElseThrow(() -> new WorkoutWriteFailure(WorkoutWriteFailure.Reason.AUTHENTICATION_REQUIRED));
		if (actual != expected) throw new WorkoutWriteFailure(WorkoutWriteFailure.Reason.DATA_GENERATION_MISMATCH);
	}
}
