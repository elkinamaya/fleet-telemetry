package com.fleet.telemetry.infrastructure.adapter.in.kafka;

import com.fleet.telemetry.application.usecase.ProcessTelemetryEvent;
import com.fleet.telemetry.application.usecase.GetVehicleState;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import com.fleet.telemetry.infrastructure.adapter.out.persistence.TelemetryEventJpaRepository;
import com.fleet.telemetry.infrastructure.adapter.out.kafka.KafkaTelemetryEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
		"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
		"spring.kafka.consumer.auto-offset-reset=earliest",
		"app.kafka.telemetry-topic=telemetry.events"
})
@EmbeddedKafka(partitions = 1, topics = "telemetry.events")
@Testcontainers
class TelemetryEventPersistenceIntegrationTest {

	@Container
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
			.withDatabaseName("fleet")
			.withUsername("fleet")
			.withPassword("testpassword");

	@DynamicPropertySource
	static void configureDatabase(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
	}

	@Autowired
	private KafkaTelemetryEventPublisher publisher;

	@Autowired
	private TelemetryEventJpaRepository repository;

	@Autowired
	private ProcessTelemetryEvent processTelemetryEvent;

	@Autowired
	private GetVehicleState getVehicleState;

	@Test
	void persistsKafkaEventAndDoesNotDuplicateRepeatedEventId() {
		UUID eventId = UUID.randomUUID();
		TelemetryEvent event = new TelemetryEvent(
				eventId,
				"truck-17",
				40.7128,
				-74.0060,
				Instant.parse("2026-10-09T15:00:00Z"));

		publisher.publish(event);
		publisher.publish(event);

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			var persistedEvent = repository.findById(eventId);
			assertTrue(persistedEvent.isPresent());
			assertEquals("truck-17", persistedEvent.get().getVehicleId());
			assertEquals(event.timestamp(), persistedEvent.get().getEventTimestamp());
			assertEquals(1L, repository.count());
		});
	}

	@Test
	void propagatesDatabaseConstraintFailureInsteadOfTreatingEventAsProcessed() {
		UUID eventId = UUID.randomUUID();
		TelemetryEvent invalidEvent = new TelemetryEvent(
				eventId,
				"truck-17",
				91.0,
				-74.0060,
				Instant.parse("2026-10-09T15:00:00Z"));

		assertThrows(DataIntegrityViolationException.class, () -> processTelemetryEvent.execute(invalidEvent));
		assertTrue(repository.findById(eventId).isEmpty());
	}

	@Test
	void selectsLatestEventTimestampEvenWhenOlderEventIsInsertedLast() {
		TelemetryEvent newest = new TelemetryEvent(
				UUID.randomUUID(),
				"truck-order",
				41.0,
				-75.0,
				Instant.parse("2026-10-09T16:00:00Z"));
		TelemetryEvent insertedLaterButOlder = new TelemetryEvent(
				UUID.randomUUID(),
				"truck-order",
				39.0,
				-73.0,
				Instant.parse("2026-10-09T14:00:00Z"));

		processTelemetryEvent.execute(newest);
		processTelemetryEvent.execute(insertedLaterButOlder);

		var state = getVehicleState.execute("truck-order");

		assertEquals(newest.eventId(), state.eventId());
		assertEquals(newest.timestamp(), state.timestamp());
	}
}
