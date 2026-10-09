package com.fleet.telemetry.infrastructure.adapter.in.kafka;

import com.fleet.telemetry.application.usecase.ProcessTelemetryEvent;
import com.fleet.telemetry.infrastructure.adapter.out.kafka.TelemetryEventMessage;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TelemetryEventListener {

	private final ProcessTelemetryEvent processTelemetryEvent;

	public TelemetryEventListener(ProcessTelemetryEvent processTelemetryEvent) {
		this.processTelemetryEvent = processTelemetryEvent;
	}

	@KafkaListener(topics = "${app.kafka.telemetry-topic}")
	public void consume(TelemetryEventMessage message) {
		processTelemetryEvent.execute(message.toEvent());
	}
}
