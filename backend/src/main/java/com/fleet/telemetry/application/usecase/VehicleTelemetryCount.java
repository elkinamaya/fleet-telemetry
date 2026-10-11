package com.fleet.telemetry.application.usecase;

public record VehicleTelemetryCount(
		String vehicleId,
		long total,
		String fromInclusive,
		String toExclusive) {
}
