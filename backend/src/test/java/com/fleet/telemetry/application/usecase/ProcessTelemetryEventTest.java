package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.TelemetryEventStore;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class ProcessTelemetryEventTest {

	@Test
	void propagatesPersistenceFailureSoKafkaCannotTreatItAsSuccessful() {
		TelemetryEventStore eventStore = mock(TelemetryEventStore.class);
		TelemetryEvent event = new TelemetryEvent(
				UUID.randomUUID(), "truck-17", 40.7128, -74.0060, Instant.parse("2026-10-09T15:00:00Z"));
		RuntimeException persistenceFailure = new RuntimeException("database unavailable");
		doThrow(persistenceFailure).when(eventStore).saveIfAbsent(event);
		ProcessTelemetryEvent useCase = new ProcessTelemetryEvent(eventStore);

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> useCase.execute(event));

		org.junit.jupiter.api.Assertions.assertSame(persistenceFailure, thrown);
		verify(eventStore).saveIfAbsent(event);
	}
}
