package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.domain.model.TelemetryEvent;

public record VehicleTelemetryPeriodBoundaries(
		String vehicleId,
		String fromInclusive,
		String toExclusive,
		boolean hasEvents,
		TelemetryEvent first,
		TelemetryEvent last) {
}
