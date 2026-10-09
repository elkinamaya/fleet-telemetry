package com.fleet.telemetry.infrastructure.adapter.in.kafka;

import com.fleet.telemetry.application.usecase.ProcessTelemetryEvent;
import com.fleet.telemetry.infrastructure.adapter.out.kafka.TelemetryEventMessage;
import com.fleet.telemetry.infrastructure.adapter.out.sse.TelemetrySseBroadcaster;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TelemetryEventListener {

	private final ProcessTelemetryEvent processTelemetryEvent;
	private final TelemetrySseBroadcaster telemetrySseBroadcaster;

	public TelemetryEventListener(
			ProcessTelemetryEvent processTelemetryEvent,
			TelemetrySseBroadcaster telemetrySseBroadcaster) {
		this.processTelemetryEvent = processTelemetryEvent;
		this.telemetrySseBroadcaster = telemetrySseBroadcaster;
	}

	@KafkaListener(topics = "${app.kafka.telemetry-topic}")
	public void consume(TelemetryEventMessage message) {
		var event = message.toEvent();
		if (processTelemetryEvent.execute(event)) {
			telemetrySseBroadcaster.publish(event);
		}
	}
}
