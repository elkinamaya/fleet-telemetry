package com.fleet.telemetry.infrastructure.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TelemetryEventJpaRepository extends JpaRepository<TelemetryEventEntity, UUID> {

	Optional<TelemetryEventEntity> findFirstByVehicleIdOrderByEventTimestampDescEventIdDesc(String vehicleId);

	@Query(value = """
			SELECT count(*)
			FROM telemetry_events
			WHERE vehicle_id = :vehicleId
			  AND event_timestamp >= :fromInclusive
			  AND event_timestamp < :toExclusive
			""", nativeQuery = true)
	long countEventsInRange(
			@Param("vehicleId") String vehicleId,
			@Param("fromInclusive") Instant fromInclusive,
			@Param("toExclusive") Instant toExclusive);

	@Query(value = """
			SELECT *
			FROM telemetry_events
			WHERE vehicle_id = :vehicleId
			  AND event_timestamp >= :fromInclusive
			  AND event_timestamp < :toExclusive
			ORDER BY event_timestamp ASC, event_id ASC
			LIMIT :limit OFFSET :offset
			""", nativeQuery = true)
	List<TelemetryEventEntity> findEventsInRangeAscending(
			@Param("vehicleId") String vehicleId,
			@Param("fromInclusive") Instant fromInclusive,
			@Param("toExclusive") Instant toExclusive,
			@Param("limit") int limit,
			@Param("offset") int offset);

	@Query(value = """
			SELECT *
			FROM telemetry_events
			WHERE vehicle_id = :vehicleId
			  AND event_timestamp >= :fromInclusive
			  AND event_timestamp < :toExclusive
			ORDER BY event_timestamp DESC, event_id DESC
			LIMIT :limit OFFSET :offset
			""", nativeQuery = true)
	List<TelemetryEventEntity> findEventsInRangeDescending(
			@Param("vehicleId") String vehicleId,
			@Param("fromInclusive") Instant fromInclusive,
			@Param("toExclusive") Instant toExclusive,
			@Param("limit") int limit,
			@Param("offset") int offset);

	@Query(value = """
			SELECT *
			FROM telemetry_events
			WHERE vehicle_id = :vehicleId
			ORDER BY event_timestamp DESC, event_id DESC
			LIMIT :limit OFFSET :offset
			""", nativeQuery = true)
	List<TelemetryEventEntity> findLatestEvents(
			@Param("vehicleId") String vehicleId,
			@Param("limit") int limit,
			@Param("offset") int offset);

	@Query(value = """
			SELECT *
			FROM telemetry_events
			WHERE vehicle_id = :vehicleId
			  AND event_timestamp >= :fromInclusive
			  AND event_timestamp < :toExclusive
			ORDER BY event_timestamp ASC, event_id ASC
			LIMIT 1
			""", nativeQuery = true)
	Optional<TelemetryEventEntity> findFirstEventInRange(
			@Param("vehicleId") String vehicleId,
			@Param("fromInclusive") Instant fromInclusive,
			@Param("toExclusive") Instant toExclusive);

	@Query(value = """
			SELECT *
			FROM telemetry_events
			WHERE vehicle_id = :vehicleId
			  AND event_timestamp >= :fromInclusive
			  AND event_timestamp < :toExclusive
			ORDER BY event_timestamp DESC, event_id DESC
			LIMIT 1
			""", nativeQuery = true)
	Optional<TelemetryEventEntity> findLastEventInRange(
			@Param("vehicleId") String vehicleId,
			@Param("fromInclusive") Instant fromInclusive,
			@Param("toExclusive") Instant toExclusive);

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
