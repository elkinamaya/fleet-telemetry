package com.fleet.telemetry.infrastructure.adapter.out.sse;

import com.fleet.telemetry.domain.model.TelemetryEvent;
import com.fleet.telemetry.infrastructure.adapter.in.web.VehicleStateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TelemetrySseBroadcaster {

	private static final Logger logger = LoggerFactory.getLogger(TelemetrySseBroadcaster.class);
	private static final long CONNECTION_TIMEOUT_MILLIS = 0L;

	private final Set<SseEmitter> emitters = ConcurrentHashMap.newKeySet();

	public SseEmitter subscribe() {
		SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT_MILLIS);
		register(emitter);
		return emitter;
	}

	void register(SseEmitter emitter) {
		emitters.add(emitter);
		emitter.onCompletion(() -> remove(emitter));
		emitter.onTimeout(() -> remove(emitter));
		emitter.onError(exception -> {
			if (!remove(emitter)) {
				return;
			}
			if (exception instanceof AsyncRequestNotUsableException) {
				logger.debug("Telemetry SSE client disconnected");
			} else {
				logger.warn("Telemetry SSE connection ended with an error", exception);
			}
		});
	}

	public void publish(TelemetryEvent event) {
		VehicleStateResponse response = VehicleStateResponse.from(event);
		emitters.forEach(emitter -> send(emitter, SseEmitter.event()
				.id(event.eventId().toString())
				.name("telemetry")
				.data(response)));
	}

	@Scheduled(fixedRateString = "${app.sse.heartbeat-interval-ms:15000}")
	public void sendHeartbeat() {
		emitters.forEach(emitter -> send(emitter, SseEmitter.event().comment("heartbeat")));
	}

	int connectionCount() {
		return emitters.size();
	}

	private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
		try {
			emitter.send(event);
		} catch (AsyncRequestNotUsableException exception) {
			if (remove(emitter)) {
				logger.debug("Removing telemetry SSE client with an unusable response");
			}
		} catch (IOException | IllegalStateException exception) {
			if (remove(emitter)) {
				logger.warn("Removing telemetry SSE client after a write failure", exception);
			}
		} catch (RuntimeException exception) {
			if (remove(emitter)) {
				logger.error("Unexpected failure writing to a telemetry SSE client", exception);
			}
		}
	}

	private boolean remove(SseEmitter emitter) {
		return emitters.remove(emitter);
	}
}
