package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.VehicleTelemetryHistoryQuery;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class GetVehicleTelemetryHistory {

	private static final int MAX_OFFSET = 100_000;

	private final VehicleTelemetryHistoryQuery historyQuery;
	private final int maximumResults;

	public GetVehicleTelemetryHistory(
			VehicleTelemetryHistoryQuery historyQuery,
			int telemetryHistoryMaximumResults) {
		if (telemetryHistoryMaximumResults < 1 || telemetryHistoryMaximumResults > 1000) {
			throw new IllegalArgumentException("app.ai.history.max-results debe estar entre 1 y 1000.");
		}
		this.historyQuery = historyQuery;
		this.maximumResults = telemetryHistoryMaximumResults;
	}

	public VehicleTelemetryHistory execute(
			String vehicleId,
			TelemetryTimeRange range,
			int requestedLimit,
			int offset,
			boolean latestFirst) {
		if (requestedLimit < 1 || offset < 0 || offset > MAX_OFFSET) {
			throw new IllegalArgumentException("La página solicitada no es válida.");
		}
		int limit = Math.min(requestedLimit, maximumResults);
		List<TelemetryEvent> events = new ArrayList<>(historyQuery.findEvents(
				vehicleId,
				range.fromInclusive(),
				range.toExclusive(),
				limit + 1,
				offset,
				latestFirst));
		boolean hasMore = events.size() > limit;
		if (hasMore) {
			events = new ArrayList<>(events.subList(0, limit));
		}
		if (latestFirst) {
			Collections.reverse(events);
		}
		return new VehicleTelemetryHistory(
				vehicleId,
				range.fromInclusive().toString(),
				range.toExclusive().toString(),
				limit,
				offset,
				hasMore,
				List.copyOf(events));
	}

	public VehicleTelemetryHistory latest(
			String vehicleId,
			int requestedLimit,
			int offset) {
		if (requestedLimit < 1 || offset < 0 || offset > MAX_OFFSET) {
			throw new IllegalArgumentException("La página solicitada no es válida.");
		}
		int limit = Math.min(requestedLimit, maximumResults);
		List<TelemetryEvent> events = new ArrayList<>(historyQuery.findLatestEvents(
				vehicleId,
				limit + 1,
				offset));
		boolean hasMore = events.size() > limit;
		if (hasMore) {
			events = new ArrayList<>(events.subList(0, limit));
		}
		Collections.reverse(events);
		return new VehicleTelemetryHistory(
				vehicleId,
				null,
				null,
				limit,
				offset,
				hasMore,
				List.copyOf(events));
	}
}
