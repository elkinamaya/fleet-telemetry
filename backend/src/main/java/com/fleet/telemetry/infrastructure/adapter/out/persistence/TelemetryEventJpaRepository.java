package com.fleet.telemetry.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TelemetryEventJpaRepository extends JpaRepository<TelemetryEventEntity, UUID> {

	Optional<TelemetryEventEntity> findFirstByVehicleIdOrderByEventTimestampDesc(String vehicleId);

	@Modifying
	@Query(value = """
			INSERT INTO telemetry_events (event_id, vehicle_id, latitude, longitude, event_timestamp)
			VALUES (:eventId, :vehicleId, :latitude, :longitude, :eventTimestamp)
			ON CONFLICT (event_id) DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(
			@Param("eventId") UUID eventId,
			@Param("vehicleId") String vehicleId,
			@Param("latitude") double latitude,
			@Param("longitude") double longitude,
			@Param("eventTimestamp") Instant eventTimestamp);
}
