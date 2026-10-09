package com.fleet.telemetry.infrastructure.adapter.out.kafka;

import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaTelemetryEventPublisherTest {

	@Test
	void translatesSynchronousKafkaFailureIntoPublicationFailure() {
		@SuppressWarnings("unchecked")
		KafkaTemplate<String, TelemetryEventMessage> kafkaTemplate = mock(KafkaTemplate.class);
		TelemetryEvent event = new TelemetryEvent(
				UUID.randomUUID(), "truck-17", 40.7128, -74.0060, Instant.parse("2026-10-09T15:00:00Z"));
		when(kafkaTemplate.send(eq("telemetry.events"), eq(event.eventId().toString()),
				any(TelemetryEventMessage.class))).thenThrow(new KafkaException("broker unavailable"));
		KafkaTelemetryEventPublisher publisher = new KafkaTelemetryEventPublisher(kafkaTemplate, "telemetry.events");

		assertThrows(TelemetryPublicationException.class, () -> publisher.publish(event));
	}
}
