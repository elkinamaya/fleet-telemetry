package com.fleet.telemetry.infrastructure.adapter.out.persistence;

import com.fleet.telemetry.application.port.out.TelemetryEventStore;
import com.fleet.telemetry.application.port.out.VehicleTelemetryHistoryQuery;
import com.fleet.telemetry.application.port.out.VehicleTelemetryQuery;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class JpaTelemetryEventStore
		implements TelemetryEventStore, VehicleTelemetryQuery, VehicleTelemetryHistoryQuery {

	private final TelemetryEventJpaRepository repository;

	public JpaTelemetryEventStore(TelemetryEventJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public boolean saveIfAbsent(TelemetryEvent event) {
		return repository.insertIfAbsent(
				event.eventId(),
				event.vehicleId(),
				event.latitude(),
				event.longitude(),
				event.timestamp(),
				event.speed()) == 1;
	}

	@Override
	public Optional<TelemetryEvent> findLatestForVehicle(String vehicleId) {
		return repository.findFirstByVehicleIdOrderByEventTimestampDescEventIdDesc(vehicleId)
				.map(this::toEvent);
	}

	@Override
	public long countEvents(String vehicleId, Instant fromInclusive, Instant toExclusive) {
		return repository.countEventsInRange(vehicleId, fromInclusive, toExclusive);
	}

	@Override
	public List<TelemetryEvent> findEvents(
			String vehicleId,
			Instant fromInclusive,
			Instant toExclusive,
			int limit,
			int offset,
			boolean newestFirst) {
		var entities = newestFirst
				? repository.findEventsInRangeDescending(
						vehicleId, fromInclusive, toExclusive, limit, offset)
				: repository.findEventsInRangeAscending(
						vehicleId, fromInclusive, toExclusive, limit, offset);
		return entities.stream().map(this::toEvent).toList();
	}

	@Override
	public List<TelemetryEvent> findLatestEvents(String vehicleId, int limit, int offset) {
		return repository.findLatestEvents(vehicleId, limit, offset).stream()
				.map(this::toEvent)
				.toList();
	}

	@Override
	public Optional<TelemetryEvent> findFirstEvent(
			String vehicleId,
			Instant fromInclusive,
			Instant toExclusive) {
		return repository.findFirstEventInRange(vehicleId, fromInclusive, toExclusive)
				.map(this::toEvent);
	}

	@Override
	public Optional<TelemetryEvent> findLastEvent(
			String vehicleId,
			Instant fromInclusive,
			Instant toExclusive) {
		return repository.findLastEventInRange(vehicleId, fromInclusive, toExclusive)
				.map(this::toEvent);
	}

	private TelemetryEvent toEvent(TelemetryEventEntity entity) {
		return new TelemetryEvent(
				entity.getEventId(),
				entity.getVehicleId(),
				entity.getLatitude(),
				entity.getLongitude(),
				entity.getEventTimestamp(),
				entity.getSpeed());
	}
}
