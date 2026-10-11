package com.fleet.telemetry.infrastructure.adapter.out.kafka;

import com.fleet.telemetry.domain.model.TelemetryEvent;
import com.fleet.telemetry.infrastructure.adapter.out.persistence.JpaTelemetryEventStore;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
		"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
		"app.kafka.telemetry-topic=telemetry.events",
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
		"spring.kafka.listener.auto-startup=false"
})
@EmbeddedKafka(partitions = 1, topics = "telemetry.events")
class KafkaTelemetryEventPublisherIntegrationTest {

	@Autowired
	private KafkaTelemetryEventPublisher publisher;

	@Autowired
	private EmbeddedKafkaBroker embeddedKafka;

	@MockitoBean
	private JpaTelemetryEventStore telemetryEventStore;

	@Test
	void publishesJsonEventWithVehicleIdAsKafkaKey() {
		TelemetryEvent event = new TelemetryEvent(
				UUID.randomUUID(), "truck-17", 40.7128, -74.0060,
				Instant.parse("2026-10-09T15:00:00Z"), 62.5);
		Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps("telemetry-test", "true", embeddedKafka);

		try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
				consumerProperties, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
			embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "telemetry.events");

			publisher.publish(event);

			ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(
					consumer, "telemetry.events", Duration.ofSeconds(10));
			assertEquals(event.vehicleId(), record.key());
			assertTrue(record.value().contains("\"vehicleId\":\"truck-17\""));
			assertTrue(record.value().contains("\"timestamp\":\"2026-10-09T15:00:00Z\""));
			assertTrue(record.value().contains("\"speed\":62.5"));
		}
	}
}
