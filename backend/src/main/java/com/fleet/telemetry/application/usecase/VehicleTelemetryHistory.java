package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.domain.model.TelemetryEvent;

import java.util.List;

public record VehicleTelemetryHistory(
		String vehicleId,
		String fromInclusive,
		String toExclusive,
		int limit,
		int offset,
		boolean hasMore,
		List<TelemetryEvent> events) {
}
