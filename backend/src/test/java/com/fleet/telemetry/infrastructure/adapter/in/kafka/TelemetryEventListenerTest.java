package com.fleet.telemetry.infrastructure.adapter.in.kafka;

import com.fleet.telemetry.application.usecase.ProcessTelemetryEvent;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import com.fleet.telemetry.infrastructure.adapter.out.kafka.TelemetryEventMessage;
import com.fleet.telemetry.infrastructure.adapter.out.sse.TelemetrySseBroadcaster;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelemetryEventListenerTest {

	@Test
	void publishesSseOnlyAfterNewEventWasPersisted() {
		ProcessTelemetryEvent process = mock(ProcessTelemetryEvent.class);
		TelemetrySseBroadcaster broadcaster = mock(TelemetrySseBroadcaster.class);
		TelemetryEventListener listener = new TelemetryEventListener(process, broadcaster);
		TelemetryEventMessage message = message();
		when(process.execute(any(TelemetryEvent.class))).thenReturn(true);

		listener.consume(message);

		verify(process).execute(message.toEvent());
		verify(broadcaster).publish(message.toEvent());
	}

	@Test
	void doesNotPublishDuplicateEventToSseClients() {
		ProcessTelemetryEvent process = mock(ProcessTelemetryEvent.class);
		TelemetrySseBroadcaster broadcaster = mock(TelemetrySseBroadcaster.class);
		TelemetryEventListener listener = new TelemetryEventListener(process, broadcaster);
		TelemetryEventMessage message = message();
		when(process.execute(any(TelemetryEvent.class))).thenReturn(false);

		listener.consume(message);

		verify(broadcaster, never()).publish(any(TelemetryEvent.class));
	}

	@Test
	void doesNotPublishWhenPersistenceFailsAndPropagatesFailure() {
		ProcessTelemetryEvent process = mock(ProcessTelemetryEvent.class);
		TelemetrySseBroadcaster broadcaster = mock(TelemetrySseBroadcaster.class);
		TelemetryEventListener listener = new TelemetryEventListener(process, broadcaster);
		RuntimeException persistenceFailure = new RuntimeException("database unavailable");
		when(process.execute(any(TelemetryEvent.class))).thenThrow(persistenceFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> listener.consume(message()));

		org.junit.jupiter.api.Assertions.assertSame(persistenceFailure, thrown);
		verify(broadcaster, never()).publish(any(TelemetryEvent.class));
	}

	private TelemetryEventMessage message() {
		return new TelemetryEventMessage(
				UUID.randomUUID(),
				"truck-17",
				40.7128,
				-74.0060,
				Instant.parse("2026-10-09T15:00:00Z").toString());
	}
}
