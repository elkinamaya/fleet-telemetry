package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.domain.model.TelemetryEvent;

import java.time.Instant;
import java.util.UUID;

public record VehicleStateResponse(
		UUID eventId,
		String vehicleId,
		double latitude,
		double longitude,
		Instant timestamp,
		Double speed) {

	public static VehicleStateResponse from(TelemetryEvent event) {
		return new VehicleStateResponse(
				event.eventId(),
				event.vehicleId(),
				event.latitude(),
				event.longitude(),
				event.timestamp(),
				event.speed());
	}
}
