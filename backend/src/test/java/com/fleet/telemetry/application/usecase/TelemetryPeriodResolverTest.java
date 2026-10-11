package com.fleet.telemetry.application.usecase;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelemetryPeriodResolverTest {

	private final TelemetryPeriodResolver resolver = new TelemetryPeriodResolver(Clock.fixed(
			Instant.parse("2026-10-10T02:30:00Z"),
			ZoneOffset.UTC));

	@Test
	void resolvesTodayAndYesterdayByBogotaCalendarDay() {
		assertEquals(new TelemetryTimeRange(
				Instant.parse("2026-10-09T05:00:00Z"),
				Instant.parse("2026-10-10T05:00:00Z")),
				resolver.resolve(TelemetryPeriod.TODAY, null, null));
		assertEquals(new TelemetryTimeRange(
				Instant.parse("2026-10-08T05:00:00Z"),
				Instant.parse("2026-10-09T05:00:00Z")),
				resolver.resolve(TelemetryPeriod.YESTERDAY, null, null));
	}

	@Test
	void resolvesTrailingPeriodsRelativeToInjectedClock() {
		assertEquals(new TelemetryTimeRange(
				Instant.parse("2026-10-10T01:30:00Z"),
				Instant.parse("2026-10-10T02:30:00Z")),
				resolver.resolve(TelemetryPeriod.LAST_HOUR, null, null));
		assertEquals(new TelemetryTimeRange(
				Instant.parse("2026-10-03T02:30:00Z"),
				Instant.parse("2026-10-10T02:30:00Z")),
				resolver.resolve(TelemetryPeriod.LAST_7_DAYS, null, null));
	}

	@Test
	void interpretsNaiveExplicitTimesInBogotaAndDateEndAsInclusiveCalendarDay() {
		assertEquals(new TelemetryTimeRange(
				Instant.parse("2026-10-09T15:00:00Z"),
				Instant.parse("2026-10-09T20:00:00Z")),
				resolver.resolve(
						TelemetryPeriod.EXPLICIT,
						"2026-10-09T10:00:00",
						"2026-10-09T15:00:00"));
		assertEquals(new TelemetryTimeRange(
				Instant.parse("2026-10-09T05:00:00Z"),
				Instant.parse("2026-10-11T05:00:00Z")),
				resolver.resolve(
						TelemetryPeriod.EXPLICIT,
						"2026-10-09",
						"2026-10-10"));
	}

	@Test
	void rejectsInvalidOrReversedExplicitIntervals() {
		assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(TelemetryPeriod.EXPLICIT, null, "2026-10-09"));
		assertThrows(IllegalArgumentException.class,
				() -> resolver.resolve(
						TelemetryPeriod.EXPLICIT,
						"2026-10-10T15:00:00Z",
						"2026-10-10T14:00:00Z"));
	}
}
