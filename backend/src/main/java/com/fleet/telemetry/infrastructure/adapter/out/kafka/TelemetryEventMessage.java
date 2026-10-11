package com.fleet.telemetry.infrastructure.adapter.out.kafka;

import com.fleet.telemetry.domain.model.TelemetryEvent;

import java.time.Instant;
import java.util.UUID;

public record TelemetryEventMessage(
		UUID eventId,
		String vehicleId,
		double latitude,
		double longitude,
		String timestamp) {

	public static TelemetryEventMessage from(TelemetryEvent event) {
		return new TelemetryEventMessage(
				event.eventId(),
				event.vehicleId(),
				event.latitude(),
				event.longitude(),
				event.timestamp().toString());
	}

	public TelemetryEvent toEvent() {
		return new TelemetryEvent(eventId, vehicleId, latitude, longitude, Instant.parse(timestamp));
	}
}
