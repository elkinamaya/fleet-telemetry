package com.fleet.telemetry.application.usecase;

public class VehicleTelemetryNotFoundException extends RuntimeException {

	private final String vehicleId;

	public VehicleTelemetryNotFoundException(String vehicleId) {
		super("No telemetry found for vehicle: " + vehicleId);
		this.vehicleId = vehicleId;
	}

	public String getVehicleId() {
		return vehicleId;
	}
}
