package com.fleet.telemetry.infrastructure.adapter.in.kafka;

import com.fleet.telemetry.application.usecase.ProcessTelemetryEvent;
import com.fleet.telemetry.application.usecase.VehicleAlertMonitor;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import com.fleet.telemetry.domain.model.VehicleAlert;
import com.fleet.telemetry.infrastructure.adapter.out.kafka.TelemetryEventMessage;
import com.fleet.telemetry.infrastructure.adapter.out.sse.TelemetrySseBroadcaster;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
		VehicleAlertMonitor alertMonitor = mock(VehicleAlertMonitor.class);
		TelemetrySseBroadcaster broadcaster = mock(TelemetrySseBroadcaster.class);
		TelemetryEventListener listener = new TelemetryEventListener(process, alertMonitor, broadcaster);
		TelemetryEventMessage message = message();
		when(process.execute(any(TelemetryEvent.class))).thenReturn(true);

		listener.consume(message);

		verify(process).execute(message.toEvent());
		verify(broadcaster).publish(message.toEvent());
		verify(alertMonitor).onTelemetry(message.toEvent());
	}

	@Test
	void doesNotPublishDuplicateEventToSseClients() {
		ProcessTelemetryEvent process = mock(ProcessTelemetryEvent.class);
		VehicleAlertMonitor alertMonitor = mock(VehicleAlertMonitor.class);
		TelemetrySseBroadcaster broadcaster = mock(TelemetrySseBroadcaster.class);
		TelemetryEventListener listener = new TelemetryEventListener(process, alertMonitor, broadcaster);
		TelemetryEventMessage message = message();
		when(process.execute(any(TelemetryEvent.class))).thenReturn(false);

		listener.consume(message);

		verify(broadcaster, never()).publish(any(TelemetryEvent.class));
		verify(alertMonitor, never()).onTelemetry(any(TelemetryEvent.class));
	}

	@Test
	void publishesAlertUpdatesOnlyAfterANewTelemetryWasPersisted() {
		ProcessTelemetryEvent process = mock(ProcessTelemetryEvent.class);
		VehicleAlertMonitor alertMonitor = mock(VehicleAlertMonitor.class);
		TelemetrySseBroadcaster broadcaster = mock(TelemetrySseBroadcaster.class);
		TelemetryEventListener listener = new TelemetryEventListener(process, alertMonitor, broadcaster);
		TelemetryEventMessage message = message();
		VehicleAlert alert = new VehicleAlert(
				UUID.randomUUID(),
				"truck-17",
				VehicleAlert.Type.SPEEDING,
				"Velocidad excesiva.",
				Instant.parse("2026-10-09T15:00:00Z"),
				Instant.parse("2026-10-09T15:00:00Z"),
				85.0,
				VehicleAlert.Status.ACTIVE);
		when(process.execute(any(TelemetryEvent.class))).thenReturn(true);
		when(alertMonitor.onTelemetry(any(TelemetryEvent.class))).thenReturn(List.of(alert));

		listener.consume(message);

		verify(broadcaster).publish(message.toEvent());
		verify(broadcaster).publishAlert(alert);
	}

	@Test
	void doesNotPublishWhenPersistenceFailsAndPropagatesFailure() {
		ProcessTelemetryEvent process = mock(ProcessTelemetryEvent.class);
		VehicleAlertMonitor alertMonitor = mock(VehicleAlertMonitor.class);
		TelemetrySseBroadcaster broadcaster = mock(TelemetrySseBroadcaster.class);
		TelemetryEventListener listener = new TelemetryEventListener(process, alertMonitor, broadcaster);
		RuntimeException persistenceFailure = new RuntimeException("database unavailable");
		when(process.execute(any(TelemetryEvent.class))).thenThrow(persistenceFailure);

		RuntimeException thrown = assertThrows(RuntimeException.class, () -> listener.consume(message()));

		org.junit.jupiter.api.Assertions.assertSame(persistenceFailure, thrown);
		verify(broadcaster, never()).publish(any(TelemetryEvent.class));
		verify(alertMonitor, never()).onTelemetry(any(TelemetryEvent.class));
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
