package com.fleet.telemetry.infrastructure.adapter.in.scheduler;

import com.fleet.telemetry.application.usecase.VehicleAlertMonitor;
import com.fleet.telemetry.infrastructure.adapter.out.sse.TelemetrySseBroadcaster;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VehicleInactivityAlertScheduler {

	private final VehicleAlertMonitor vehicleAlertMonitor;
	private final TelemetrySseBroadcaster telemetrySseBroadcaster;

	public VehicleInactivityAlertScheduler(
			VehicleAlertMonitor vehicleAlertMonitor,
			TelemetrySseBroadcaster telemetrySseBroadcaster) {
		this.vehicleAlertMonitor = vehicleAlertMonitor;
		this.telemetrySseBroadcaster = telemetrySseBroadcaster;
	}

	@Scheduled(fixedRateString = "${app.alerts.evaluation-interval-ms:30000}")
	public void evaluateNoRecentReports() {
		synchronized (vehicleAlertMonitor) {
			vehicleAlertMonitor.evaluateNoRecentReports()
					.forEach(telemetrySseBroadcaster::publishAlert);
		}
	}
}
