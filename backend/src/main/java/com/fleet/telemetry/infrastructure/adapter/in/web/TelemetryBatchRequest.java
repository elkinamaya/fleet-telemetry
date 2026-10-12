package com.fleet.telemetry.infrastructure.adapter.in.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public record TelemetryBatchRequest(
		@NotEmpty @Size(max = MAX_EVENTS) List<@NotNull @Valid TelemetryRequest> events) {

	public static final int MAX_EVENTS = 100;

	@AssertTrue(message = "eventId debe ser único dentro del lote")
	public boolean hasUniqueEventIds() {
		if (events == null) {
			return true;
		}
		HashSet<UUID> eventIds = new HashSet<>();
		for (TelemetryRequest event : events) {
			if (event != null && event.eventId() != null && !eventIds.add(event.eventId())) {
				return false;
			}
		}
		return true;
	}
}
