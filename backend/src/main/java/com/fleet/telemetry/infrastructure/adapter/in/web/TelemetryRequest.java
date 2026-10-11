package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.domain.model.TelemetryEvent;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record TelemetryRequest(
		@NotNull UUID eventId,
		@NotBlank @Size(max = 100) String vehicleId,
		@NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
		@NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
		@NotNull Instant timestamp,
		@DecimalMin("0.0") Double speed) {

	public TelemetryEvent toEvent() {
		return new TelemetryEvent(eventId, vehicleId, latitude, longitude, timestamp, speed);
	}

	@AssertTrue(message = "speed debe ser un número finito")
	public boolean isSpeedFinite() {
		return speed == null || Double.isFinite(speed);
	}
}
