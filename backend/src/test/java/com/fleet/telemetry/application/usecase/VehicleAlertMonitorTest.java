package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.domain.model.TelemetryEvent;
import com.fleet.telemetry.domain.model.VehicleAlert;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleAlertMonitorTest {

	private static final Instant START = Instant.parse("2026-10-11T12:00:00Z");

	@Test
	void activatesStoppedAlertOnlyAfterTwoLowSpeedSamplesSpanMoreThanTwentyMinutes() {
		AdjustableClock clock = new AdjustableClock(START);
		VehicleAlertMonitor monitor = new VehicleAlertMonitor(clock);

		assertTrue(monitor.onTelemetry(event("truck-17", START, 2.0)).isEmpty());
		assertTrue(monitor.onTelemetry(event("truck-17", START.plus(Duration.ofMinutes(20)), 1.0)).isEmpty());
		List<VehicleAlert> alerts = monitor.onTelemetry(
				event("truck-17", START.plus(Duration.ofMinutes(20)).plusSeconds(1), 0.0));

		assertEquals(VehicleAlert.Type.STOPPED, alerts.get(0).type());
		assertEquals(VehicleAlert.Status.ACTIVE, alerts.get(0).status());
		assertTrue(monitor.getActiveAlerts().contains(alerts.get(0)));
	}

	@Test
	void doesNotRepeatStoppedAlertAndResolvesItWhenSpeedExceedsThreshold() {
		AdjustableClock clock = new AdjustableClock(START);
		VehicleAlertMonitor monitor = new VehicleAlertMonitor(clock);
		monitor.onTelemetry(event("truck-17", START, 2.0));
		monitor.onTelemetry(event("truck-17", START.plus(Duration.ofMinutes(21)), 2.0));

		assertTrue(monitor.onTelemetry(event("truck-17", START.plus(Duration.ofMinutes(22)), 1.0)).isEmpty());
		List<VehicleAlert> resolved = monitor.onTelemetry(
				event("truck-17", START.plus(Duration.ofMinutes(23)), 3.1));

		assertEquals(VehicleAlert.Status.RESOLVED, resolved.get(0).status());
		assertTrue(monitor.getActiveAlerts().isEmpty());
	}

	@Test
	void activatesSpeedingOnlyOnTwoConsecutiveHighSpeedSamplesAndResolvesOnNormalSpeed() {
		AdjustableClock clock = new AdjustableClock(START);
		VehicleAlertMonitor monitor = new VehicleAlertMonitor(clock);

		assertTrue(monitor.onTelemetry(event("truck-17", START, 81.0)).isEmpty());
		List<VehicleAlert> activated = monitor.onTelemetry(
				event("truck-17", START.plusSeconds(1), 90.0));
		assertEquals(VehicleAlert.Type.SPEEDING, activated.get(0).type());
		assertTrue(monitor.onTelemetry(event("truck-17", START.plusSeconds(2), 85.0)).isEmpty());

		List<VehicleAlert> resolved = monitor.onTelemetry(
				event("truck-17", START.plusSeconds(3), 80.0));
		assertEquals(VehicleAlert.Status.RESOLVED, resolved.get(0).status());
		assertTrue(monitor.getActiveAlerts().isEmpty());
	}

	@Test
	void reportsSilenceAtFifteenMinutesOnceAndClearsItOnANewerReport() {
		AdjustableClock clock = new AdjustableClock(START);
		VehicleAlertMonitor monitor = new VehicleAlertMonitor(clock);
		monitor.onTelemetry(event("truck-17", START, null));

		clock.advance(Duration.ofMinutes(14).plusSeconds(59));
		assertTrue(monitor.evaluateNoRecentReports().isEmpty());
		clock.advance(Duration.ofSeconds(1));
		List<VehicleAlert> active = monitor.evaluateNoRecentReports();
		assertEquals(VehicleAlert.Type.NO_RECENT_REPORTS, active.get(0).type());
		assertTrue(monitor.evaluateNoRecentReports().isEmpty());

		List<VehicleAlert> resolved = monitor.onTelemetry(
				event("truck-17", START.plus(Duration.ofMinutes(15)), null));
		assertEquals(VehicleAlert.Status.RESOLVED, resolved.get(0).status());
		assertTrue(monitor.getActiveAlerts().isEmpty());
	}

	@Test
	void ignoresOutOfOrderTelemetryWithoutRewindingTheOperationalState() {
		VehicleAlertMonitor monitor = new VehicleAlertMonitor(new AdjustableClock(START));
		monitor.onTelemetry(event("truck-17", START.plus(Duration.ofMinutes(25)), 2.0));

		assertTrue(monitor.onTelemetry(event("truck-17", START, 2.0)).isEmpty());
		assertTrue(monitor.onTelemetry(
				event("truck-17", START.plus(Duration.ofMinutes(26)), 2.0)).isEmpty());
		assertTrue(monitor.getActiveAlerts().isEmpty());
	}

	@Test
	void missingSpeedDoesNotCreateSpeedAlertsOrResolveAnActiveSpeedingAlert() {
		VehicleAlertMonitor monitor = new VehicleAlertMonitor(new AdjustableClock(START));
		monitor.onTelemetry(event("truck-17", START, 81.0));
		VehicleAlert active = monitor.onTelemetry(
				event("truck-17", START.plusSeconds(1), 81.0)).get(0);

		assertTrue(monitor.onTelemetry(event("truck-17", START.plusSeconds(2), null)).isEmpty());
		assertEquals(active, monitor.getActiveAlerts().get(0));
	}

	private TelemetryEvent event(String vehicleId, Instant timestamp, Double speed) {
		return new TelemetryEvent(
				UUID.randomUUID(), vehicleId, 4.711, -74.0721, timestamp, speed);
	}

	private static final class AdjustableClock extends Clock {
		private Instant instant;

		private AdjustableClock(Instant instant) {
			this.instant = instant;
		}

		private void advance(Duration duration) {
			instant = instant.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			if (!ZoneId.of("UTC").equals(zone)) {
				throw new IllegalArgumentException("Only UTC is supported by this test clock.");
			}
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
