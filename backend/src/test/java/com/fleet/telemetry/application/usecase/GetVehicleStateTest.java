package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.VehicleTelemetryQuery;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetVehicleStateTest {

	@Test
	void returnsLatestEventFromQueryPort() {
		VehicleTelemetryQuery query = mock(VehicleTelemetryQuery.class);
		TelemetryEvent latest = new TelemetryEvent(
				UUID.randomUUID(), "truck-17", 40.7128, -74.0060, Instant.parse("2026-10-09T15:00:00Z"));
		when(query.findLatestForVehicle("truck-17")).thenReturn(Optional.of(latest));
		GetVehicleState useCase = new GetVehicleState(query);

		assertEquals(latest, useCase.execute("truck-17"));
		verify(query).findLatestForVehicle("truck-17");
	}
}
