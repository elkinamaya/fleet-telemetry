package com.fleet.telemetry.application.port.out;

import com.fleet.telemetry.domain.model.TelemetryEvent;

import java.util.Optional;

public interface VehicleTelemetryQuery {

	Optional<TelemetryEvent> findLatestForVehicle(String vehicleId);
}
