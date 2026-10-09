package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.TelemetryEventPublisher;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SubmitTelemetryEventTest {

	@Test
	void publishesSubmittedEvent() {
		TelemetryEventPublisher publisher = mock(TelemetryEventPublisher.class);
		SubmitTelemetryEvent useCase = new SubmitTelemetryEvent(publisher);
		TelemetryEvent event = new TelemetryEvent(
				UUID.randomUUID(), "truck-17", 40.7128, -74.0060, Instant.parse("2026-10-09T15:00:00Z"));

		useCase.execute(event);

		verify(publisher).publish(event);
	}
}
