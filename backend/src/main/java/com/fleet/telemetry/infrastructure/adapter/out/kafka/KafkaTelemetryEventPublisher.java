package com.fleet.telemetry.infrastructure.adapter.out.kafka;

import com.fleet.telemetry.application.port.out.TelemetryEventPublisher;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;

@Component
public class KafkaTelemetryEventPublisher implements TelemetryEventPublisher {

	private final KafkaTemplate<String, TelemetryEventMessage> kafkaTemplate;
	private final String topic;

	public KafkaTelemetryEventPublisher(
			KafkaTemplate<String, TelemetryEventMessage> kafkaTemplate,
			@Value("${app.kafka.telemetry-topic}") String topic) {
		this.kafkaTemplate = kafkaTemplate;
		this.topic = topic;
	}

	@Override
	public void publish(TelemetryEvent event) {
		try {
			kafkaTemplate.send(topic, event.eventId().toString(), TelemetryEventMessage.from(event)).get();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new TelemetryPublicationException("Interrupted while publishing telemetry event", exception);
		} catch (ExecutionException exception) {
			throw new TelemetryPublicationException("Kafka did not confirm telemetry event", exception.getCause());
		} catch (KafkaException exception) {
			throw new TelemetryPublicationException("Kafka could not accept telemetry event", exception);
		}
	}
}
