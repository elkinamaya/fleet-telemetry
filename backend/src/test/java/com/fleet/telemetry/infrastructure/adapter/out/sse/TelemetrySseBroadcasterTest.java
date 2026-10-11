package com.fleet.telemetry.infrastructure.adapter.out.sse;

import com.fleet.telemetry.domain.model.TelemetryEvent;
import com.fleet.telemetry.domain.model.VehicleAlert;
import com.fleet.telemetry.infrastructure.adapter.in.web.VehicleAlertResponse;
import com.fleet.telemetry.infrastructure.adapter.in.web.VehicleStateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

class TelemetrySseBroadcasterTest {

	@Test
	void sendsMultipleEventsToSameConnection() throws IOException {
		TelemetrySseBroadcaster broadcaster = new TelemetrySseBroadcaster();
		SseEmitter emitter = mock(SseEmitter.class);
		broadcaster.register(emitter);
		var sentEvents = forClass(SseEmitter.SseEventBuilder.class);
		TelemetryEvent firstEvent = event("truck-17");
		TelemetryEvent secondEvent = event("truck-18");

		broadcaster.publish(firstEvent);
		broadcaster.publish(secondEvent);

		verify(emitter, times(2)).send(sentEvents.capture());
		assertEquals(1, broadcaster.connectionCount());
		assertEquals(2, sentEvents.getAllValues().size());
		assertEquals(firstEvent.eventId(), responseFrom(sentEvents.getAllValues().get(0)).eventId());
		assertEquals(42.5, responseFrom(sentEvents.getAllValues().get(0)).speed());
		assertEquals("truck-18", responseFrom(sentEvents.getAllValues().get(1)).vehicleId());
	}

	@Test
	void broadcastsEachEventToAllConnectedClients() throws IOException {
		TelemetrySseBroadcaster broadcaster = new TelemetrySseBroadcaster();
		SseEmitter firstClient = mock(SseEmitter.class);
		SseEmitter secondClient = mock(SseEmitter.class);
		broadcaster.register(firstClient);
		broadcaster.register(secondClient);

		broadcaster.publish(event("truck-17"));
		broadcaster.publish(event("truck-18"));

		verify(firstClient, times(2)).send(any(SseEmitter.SseEventBuilder.class));
		verify(secondClient, times(2)).send(any(SseEmitter.SseEventBuilder.class));
		assertEquals(2, broadcaster.connectionCount());
	}

	@Test
	void publishesOperationalAlertEventsToConnectedClients() throws IOException {
		TelemetrySseBroadcaster broadcaster = new TelemetrySseBroadcaster();
		SseEmitter emitter = mock(SseEmitter.class);
		broadcaster.register(emitter);
		var sentEvent = forClass(SseEmitter.SseEventBuilder.class);
		Instant now = Instant.parse("2026-10-11T12:30:00Z");
		VehicleAlert alert = new VehicleAlert(
				UUID.randomUUID(),
				"truck-17",
				VehicleAlert.Type.SPEEDING,
				"Velocidad excesiva.",
				now,
				now,
				85.0,
				VehicleAlert.Status.ACTIVE);

		broadcaster.publishAlert(alert);

		verify(emitter).send(sentEvent.capture());
		Set<ResponseBodyEmitter.DataWithMediaType> data = sentEvent.getValue().build();
		VehicleAlertResponse response = data.stream()
				.map(ResponseBodyEmitter.DataWithMediaType::getData)
				.filter(VehicleAlertResponse.class::isInstance)
				.map(VehicleAlertResponse.class::cast)
				.findFirst()
				.orElseThrow();
		assertEquals(alert.alertId(), response.alertId());
		assertEquals(VehicleAlert.Status.ACTIVE, response.status());
	}

	@Test
	void sendsHeartbeatsToActiveClients() throws IOException {
		TelemetrySseBroadcaster broadcaster = new TelemetrySseBroadcaster();
		SseEmitter emitter = mock(SseEmitter.class);
		broadcaster.register(emitter);

		broadcaster.sendHeartbeat();

		verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
		assertEquals(1, broadcaster.connectionCount());
	}

	@Test
	void removesClientWhenConnectionLifecycleCompletes() {
		TelemetrySseBroadcaster broadcaster = new TelemetrySseBroadcaster();
		SseEmitter emitter = mock(SseEmitter.class);
		AtomicReference<Runnable> completion = new AtomicReference<>();
		AtomicReference<Runnable> timeout = new AtomicReference<>();
		AtomicReference<Consumer<Throwable>> error = new AtomicReference<>();
		doAnswer(invocation -> {
			completion.set(invocation.getArgument(0));
			return null;
		}).when(emitter).onCompletion(any());
		doAnswer(invocation -> {
			timeout.set(invocation.getArgument(0));
			return null;
		}).when(emitter).onTimeout(any());
		doAnswer(invocation -> {
			error.set(invocation.getArgument(0));
			return null;
		}).when(emitter).onError(any());

		broadcaster.register(emitter);
		assertEquals(1, broadcaster.connectionCount());

		completion.get().run();
		assertEquals(0, broadcaster.connectionCount());

		broadcaster.register(emitter);
		timeout.get().run();
		assertEquals(0, broadcaster.connectionCount());

		broadcaster.register(emitter);
		error.get().accept(new IOException("client disconnected"));
		assertEquals(0, broadcaster.connectionCount());
	}

	@Test
	void removesClientWhenWritingFails() throws IOException {
		TelemetrySseBroadcaster broadcaster = new TelemetrySseBroadcaster();
		SseEmitter emitter = mock(SseEmitter.class);
		doThrow(new IOException("client disconnected"))
				.when(emitter).send(any(SseEmitter.SseEventBuilder.class));
		broadcaster.register(emitter);

		broadcaster.publish(event("truck-17"));

		assertEquals(0, broadcaster.connectionCount());
		verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
		verify(emitter, never()).complete();
	}

	@Test
	void removesUnusableClientAfterHeartbeatWithoutRetrying() throws IOException {
		TelemetrySseBroadcaster broadcaster = new TelemetrySseBroadcaster();
		SseEmitter emitter = mock(SseEmitter.class);
		doThrow(new AsyncRequestNotUsableException("response already closed"))
				.when(emitter).send(any(SseEmitter.SseEventBuilder.class));
		broadcaster.register(emitter);

		broadcaster.sendHeartbeat();
		broadcaster.sendHeartbeat();

		assertEquals(0, broadcaster.connectionCount());
		verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
		verify(emitter, never()).complete();
	}

	@Test
	void brokenClientDoesNotPreventBroadcastToOtherClients() throws IOException {
		TelemetrySseBroadcaster broadcaster = new TelemetrySseBroadcaster();
		SseEmitter brokenClient = mock(SseEmitter.class);
		SseEmitter activeClient = mock(SseEmitter.class);
		doThrow(new AsyncRequestNotUsableException("response already closed"))
				.when(brokenClient).send(any(SseEmitter.SseEventBuilder.class));
		broadcaster.register(brokenClient);
		broadcaster.register(activeClient);

		broadcaster.publish(event("truck-17"));

		verify(brokenClient).send(any(SseEmitter.SseEventBuilder.class));
		verify(activeClient).send(any(SseEmitter.SseEventBuilder.class));
		assertEquals(1, broadcaster.connectionCount());
	}

	private TelemetryEvent event(String vehicleId) {
		return new TelemetryEvent(
				UUID.randomUUID(),
				vehicleId,
				40.7128,
				-74.0060,
				Instant.parse("2026-10-09T15:00:00Z"),
				42.5);
	}

	private VehicleStateResponse responseFrom(SseEmitter.SseEventBuilder event) {
		Set<ResponseBodyEmitter.DataWithMediaType> data = event.build();
		return data.stream()
				.map(ResponseBodyEmitter.DataWithMediaType::getData)
				.filter(VehicleStateResponse.class::isInstance)
				.map(VehicleStateResponse.class::cast)
				.findFirst()
				.orElseThrow();
	}
}
