package com.fleet.telemetry.infrastructure.adapter.out.persistence;

import com.fleet.telemetry.application.port.out.TelemetryEventStore;
import com.fleet.telemetry.application.port.out.VehicleTelemetryQuery;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaTelemetryEventStore implements TelemetryEventStore, VehicleTelemetryQuery {

	private final TelemetryEventJpaRepository repository;

	public JpaTelemetryEventStore(TelemetryEventJpaRepository repository) {
		this.repository = repository;
	}

	@Override
	public void saveIfAbsent(TelemetryEvent event) {
		repository.insertIfAbsent(
				event.eventId(),
				event.vehicleId(),
				event.latitude(),
				event.longitude(),
				event.timestamp());
	}

	@Override
	public Optional<TelemetryEvent> findLatestForVehicle(String vehicleId) {
		return repository.findFirstByVehicleIdOrderByEventTimestampDesc(vehicleId)
				.map(entity -> new TelemetryEvent(
						entity.getEventId(),
						entity.getVehicleId(),
						entity.getLatitude(),
						entity.getLongitude(),
						entity.getEventTimestamp()));
	}
}
