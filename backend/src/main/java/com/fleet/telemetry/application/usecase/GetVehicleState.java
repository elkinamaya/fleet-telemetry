package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.VehicleTelemetryQuery;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.springframework.stereotype.Service;

@Service
public class GetVehicleState {

	private final VehicleTelemetryQuery vehicleTelemetryQuery;

	public GetVehicleState(VehicleTelemetryQuery vehicleTelemetryQuery) {
		this.vehicleTelemetryQuery = vehicleTelemetryQuery;
	}

	public TelemetryEvent execute(String vehicleId) {
		return vehicleTelemetryQuery.findLatestForVehicle(vehicleId)
				.orElseThrow(() -> new VehicleTelemetryNotFoundException(vehicleId));
	}
}
