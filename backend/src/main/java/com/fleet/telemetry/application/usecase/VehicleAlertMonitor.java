package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.domain.model.TelemetryEvent;
import com.fleet.telemetry.domain.model.VehicleAlert;
import com.fleet.telemetry.domain.model.VehicleAlert.Status;
import com.fleet.telemetry.domain.model.VehicleAlert.Type;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class VehicleAlertMonitor {

	public static final double STOPPED_SPEED_THRESHOLD_KMH = 3.0;
	public static final double SPEEDING_THRESHOLD_KMH = 80.0;
	public static final Duration STOPPED_DURATION = Duration.ofMinutes(20);
	public static final Duration NO_REPORT_DURATION = Duration.ofMinutes(15);

	private final Clock clock;
	private final Map<String, VehicleState> vehicleStates = new HashMap<>();
	private final Map<AlertKey, VehicleAlert> activeAlerts = new HashMap<>();

	public VehicleAlertMonitor(Clock clock) {
		this.clock = clock;
	}

	public synchronized List<VehicleAlert> onTelemetry(TelemetryEvent event) {
		VehicleState state = vehicleStates.get(event.vehicleId());
		if (state != null && compareEvents(event, state.latestEvent) <= 0) {
			return List.of();
		}

		if (state == null) {
			state = new VehicleState();
			vehicleStates.put(event.vehicleId(), state);
		}
		state.latestEvent = event;

		Instant now = clock.instant();
		List<VehicleAlert> changes = new ArrayList<>();
		resolve(event.vehicleId(), Type.NO_RECENT_REPORTS, now, changes);

		if (event.speed() == null || !Double.isFinite(event.speed()) || event.speed() < 0) {
			state.resetSpeedCandidates();
			return List.copyOf(changes);
		}

		if (event.speed() <= STOPPED_SPEED_THRESHOLD_KMH) {
			if (state.stoppedSince == null) {
				state.stoppedSince = event.timestamp();
				state.stoppedSamples = 1;
			} else {
				state.stoppedSamples++;
				if (state.stoppedSamples >= 2
						&& Duration.between(state.stoppedSince, event.timestamp())
								.compareTo(STOPPED_DURATION) > 0) {
					activate(
							event.vehicleId(),
							Type.STOPPED,
							"Velocidad ≤ " + STOPPED_SPEED_THRESHOLD_KMH
									+ " km/h en muestras separadas por más de "
									+ STOPPED_DURATION.toMinutes() + " minutos.",
							event.speed(),
							now,
							changes);
				}
			}
		} else {
			state.stoppedSince = null;
			state.stoppedSamples = 0;
			resolve(event.vehicleId(), Type.STOPPED, now, changes);
		}

		if (event.speed() > SPEEDING_THRESHOLD_KMH) {
			state.speedingSamples++;
			if (state.speedingSamples >= 2) {
				activate(
						event.vehicleId(),
						Type.SPEEDING,
						"Velocidad superior a " + SPEEDING_THRESHOLD_KMH
								+ " km/h en dos muestras consecutivas.",
						event.speed(),
						now,
						changes);
			}
		} else {
			state.speedingSamples = 0;
			resolve(event.vehicleId(), Type.SPEEDING, now, changes);
		}

		return List.copyOf(changes);
	}

	public synchronized List<VehicleAlert> evaluateNoRecentReports() {
		Instant now = clock.instant();
		List<VehicleAlert> changes = new ArrayList<>();
		vehicleStates.forEach((vehicleId, state) -> {
			Instant lastReport = state.latestEvent.timestamp();
			if (!now.isBefore(lastReport.plus(NO_REPORT_DURATION))) {
				activate(
						vehicleId,
						Type.NO_RECENT_REPORTS,
						"Sin telemetría válida desde " + lastReport + ".",
						state.latestEvent.speed(),
						now,
						changes);
			}
		});
		return List.copyOf(changes);
	}

	public synchronized List<VehicleAlert> getActiveAlerts() {
		return activeAlerts.values().stream()
				.sorted(Comparator.comparing(VehicleAlert::detectedAt)
						.thenComparing(VehicleAlert::vehicleId)
						.thenComparing(alert -> alert.type().name()))
				.toList();
	}

	private void activate(
			String vehicleId,
			Type type,
			String description,
			Double speed,
			Instant now,
			List<VehicleAlert> changes) {
		AlertKey key = new AlertKey(vehicleId, type);
		if (activeAlerts.containsKey(key)) {
			return;
		}
		VehicleAlert alert = new VehicleAlert(
				UUID.randomUUID(), vehicleId, type, description, now, now, speed, Status.ACTIVE);
		activeAlerts.put(key, alert);
		changes.add(alert);
	}

	private void resolve(String vehicleId, Type type, Instant now, List<VehicleAlert> changes) {
		VehicleAlert alert = activeAlerts.remove(new AlertKey(vehicleId, type));
		if (alert != null) {
			changes.add(new VehicleAlert(
					alert.alertId(),
					alert.vehicleId(),
					alert.type(),
					alert.description(),
					alert.detectedAt(),
					now,
					alert.speed(),
					Status.RESOLVED));
		}
	}

	private int compareEvents(TelemetryEvent left, TelemetryEvent right) {
		int timestampOrder = left.timestamp().compareTo(right.timestamp());
		return timestampOrder != 0
				? timestampOrder
				: left.eventId().toString().compareTo(right.eventId().toString());
	}

	private record AlertKey(String vehicleId, Type type) {
	}

	private static final class VehicleState {
		private TelemetryEvent latestEvent;
		private Instant stoppedSince;
		private int stoppedSamples;
		private int speedingSamples;

		private void resetSpeedCandidates() {
			stoppedSince = null;
			stoppedSamples = 0;
			speedingSamples = 0;
		}
	}
}
