package com.sm.spendly.user.entity;

import java.time.DayOfWeek;

/**
 * The days a week may start on. Only these three, matching the users_week_start_valid
 * CHECK in V2. Using our own enum (not java.time.DayOfWeek with 7 values) means a bad
 * value like "FRIDAY" is rejected when the JSON is read, and Swagger lists exactly the
 * three allowed values for the app.
 *
 * <p>Stored by name, the same strings DayOfWeek used, so no migration was needed.
 */
public enum WeekStart {
	MONDAY,
	SATURDAY,
	SUNDAY;

	/** For date math later (budgets, reports): "start of this week". */
	public DayOfWeek toDayOfWeek() {
		return DayOfWeek.valueOf(name());
	}
}
