package com.overload.overload_server.workout;

import com.overload.overload_server.persistence.TransactionExecution;
import com.overload.overload_server.workoutsettings.UserWorkoutSettingsRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class WorkoutReadService {

	private final WorkoutOwnerRepository owners;
	private final UserWorkoutSettingsRepository settings;
	private final TransactionTemplate transaction;

	WorkoutReadService(WorkoutOwnerRepository owners,
			UserWorkoutSettingsRepository settings,
			@Qualifier("workoutTransactionManager") PlatformTransactionManager manager) {
		this.owners = owners;
		this.settings = settings;
		this.transaction = new TransactionTemplate(manager);
		this.transaction.setReadOnly(true);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
	}

	public WorkoutSettingsResponse settings(UUID userId) {
		return TransactionExecution.execute(transaction, () -> {
			long generation = generation(userId);
			var value = settings.findByUserIdAndDataGeneration(userId, generation)
					.orElseThrow(() -> new WorkoutReadFailure(WorkoutReadFailure.Reason.WORKOUT_SETTINGS_MISSING));
			return new WorkoutSettingsResponse(userId, generation, value.getRestLowSeconds(),
					value.getRestMediumSeconds(), value.getRestHighSeconds());
		});
	}

	private long generation(UUID userId) {
		return owners.findGeneration(userId)
				.orElseThrow(() -> new WorkoutReadFailure(WorkoutReadFailure.Reason.AUTHENTICATION_REQUIRED));
	}
}
