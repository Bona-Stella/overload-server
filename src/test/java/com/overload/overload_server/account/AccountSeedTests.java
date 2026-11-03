package com.overload.overload_server.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountSeedTests {

	@ParameterizedTest
	@CsvSource({"1,180", "10,180", "11,120", "12,120", "13,90", "2147483647,90"})
	void selectsRestFromTheTargetUpperBound(int maximumReps, int seconds) {
		assertThat(AccountSeed.WORKOUT_SETTINGS.restSecondsFor(maximumReps)).isEqualTo(seconds);
	}

	@Test
	void rejectsNonpositiveSettingsAndRepetitionBounds() {
		assertThatThrownBy(() -> new WorkoutSettings(0, 120, 90)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WorkoutSettings(180, -1, 90)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WorkoutSettings(180, 120, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AccountSeed.WORKOUT_SETTINGS.restSecondsFor(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AccountSeed.WORKOUT_SETTINGS.restSecondsFor(-1))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
