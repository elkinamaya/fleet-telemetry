package com.fleet.telemetry.domain.model;

import java.time.Instant;
import java.util.UUID;

public record VehicleAlert(
		UUID alertId,
		String vehicleId,
		Type type,
		String description,
		Instant detectedAt,
		Instant updatedAt,
		Double speed,
		Status status) {

	public enum Type {
		STOPPED,
		SPEEDING,
		NO_RECENT_REPORTS
	}

	public enum Status {
		ACTIVE,
		RESOLVED
	}
}
