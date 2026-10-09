package com.fleet.telemetry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "telemetry_events")
public class TelemetryEventEntity {

	@Id
	@Column(name = "event_id", nullable = false, updatable = false)
	private UUID eventId;

	@Column(name = "vehicle_id", nullable = false, length = 100)
	private String vehicleId;

	@Column(nullable = false)
	private double latitude;

	@Column(nullable = false)
	private double longitude;

	@Column(name = "event_timestamp", nullable = false, columnDefinition = "TIMESTAMPTZ")
	private Instant eventTimestamp;

	protected TelemetryEventEntity() {
	}

	public TelemetryEventEntity(
			UUID eventId,
			String vehicleId,
			double latitude,
			double longitude,
			Instant eventTimestamp) {
		this.eventId = eventId;
		this.vehicleId = vehicleId;
		this.latitude = latitude;
		this.longitude = longitude;
		this.eventTimestamp = eventTimestamp;
	}

	public UUID getEventId() {
		return eventId;
	}

	public String getVehicleId() {
		return vehicleId;
	}

	public double getLatitude() {
		return latitude;
	}

	public double getLongitude() {
		return longitude;
	}

	public Instant getEventTimestamp() {
		return eventTimestamp;
	}
}
