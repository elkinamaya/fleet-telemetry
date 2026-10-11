package com.fleet.telemetry.domain.model;

import java.time.Instant;
import java.util.UUID;

public record TelemetryEvent(
		UUID eventId,
		String vehicleId,
		double latitude,
		double longitude,
		Instant timestamp,
		Double speed) {

	public TelemetryEvent(
			UUID eventId,
			String vehicleId,
			double latitude,
			double longitude,
			Instant timestamp) {
		this(eventId, vehicleId, latitude, longitude, timestamp, null);
	}
}
