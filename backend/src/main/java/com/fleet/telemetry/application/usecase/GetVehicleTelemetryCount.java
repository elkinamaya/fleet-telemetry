package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.VehicleTelemetryHistoryQuery;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class GetVehicleTelemetryCount {

	private final VehicleTelemetryHistoryQuery historyQuery;

	public GetVehicleTelemetryCount(VehicleTelemetryHistoryQuery historyQuery) {
		this.historyQuery = historyQuery;
	}

	public VehicleTelemetryCount execute(String vehicleId, TelemetryTimeRange range) {
		long total = historyQuery.countEvents(
				vehicleId,
				range.fromInclusive(),
				range.toExclusive());
		return new VehicleTelemetryCount(
				vehicleId,
				total,
				range.fromInclusive().toString(),
				range.toExclusive().toString());
	}
}
