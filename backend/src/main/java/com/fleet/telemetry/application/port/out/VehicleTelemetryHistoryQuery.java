package com.fleet.telemetry.application.port.out;

import com.fleet.telemetry.domain.model.TelemetryEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface VehicleTelemetryHistoryQuery {

	long countEvents(String vehicleId, Instant fromInclusive, Instant toExclusive);

	List<TelemetryEvent> findEvents(
			String vehicleId,
			Instant fromInclusive,
			Instant toExclusive,
			int limit,
			int offset,
			boolean newestFirst);

	List<TelemetryEvent> findLatestEvents(String vehicleId, int limit, int offset);

	Optional<TelemetryEvent> findFirstEvent(String vehicleId, Instant fromInclusive, Instant toExclusive);

	Optional<TelemetryEvent> findLastEvent(String vehicleId, Instant fromInclusive, Instant toExclusive);
}
