package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.domain.model.VehicleAlert;

import java.time.Instant;
import java.util.UUID;

public record VehicleAlertResponse(
		UUID alertId,
		String vehicleId,
		VehicleAlert.Type type,
		String description,
		Instant detectedAt,
		Instant updatedAt,
		Double speed,
		VehicleAlert.Status status) {

	public static VehicleAlertResponse from(VehicleAlert alert) {
		return new VehicleAlertResponse(
				alert.alertId(),
				alert.vehicleId(),
				alert.type(),
				alert.description(),
				alert.detectedAt(),
				alert.updatedAt(),
				alert.speed(),
				alert.status());
	}
}
