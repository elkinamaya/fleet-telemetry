package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.VehicleTelemetryHistoryQuery;
import org.springframework.stereotype.Service;

@Service
public class GetVehicleTelemetryPeriodBoundaries {

	private final VehicleTelemetryHistoryQuery historyQuery;

	public GetVehicleTelemetryPeriodBoundaries(VehicleTelemetryHistoryQuery historyQuery) {
		this.historyQuery = historyQuery;
	}

	public VehicleTelemetryPeriodBoundaries execute(String vehicleId, TelemetryTimeRange range) {
		var first = historyQuery.findFirstEvent(
				vehicleId,
				range.fromInclusive(),
				range.toExclusive());
		var last = historyQuery.findLastEvent(
				vehicleId,
				range.fromInclusive(),
				range.toExclusive());
		return new VehicleTelemetryPeriodBoundaries(
				vehicleId,
				range.fromInclusive().toString(),
				range.toExclusive().toString(),
				first.isPresent(),
				first.orElse(null),
				last.orElse(null));
	}
}
