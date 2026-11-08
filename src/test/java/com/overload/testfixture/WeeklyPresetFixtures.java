package com.overload.testfixture;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/** For disposable fixtures that remove an account or replace its data generation. */
public final class WeeklyPresetFixtures {

	private WeeklyPresetFixtures() {
	}

	public static void clear(JdbcTemplate jdbc, UUID owner) {
		jdbc.update("DELETE FROM weekly_schedule_state WHERE user_id = ?", owner);
		jdbc.update("DELETE FROM weekly_schedule_presets WHERE user_id = ?", owner);
	}
}
