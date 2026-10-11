package com.fleet.telemetry.infrastructure.adapter.in.kafka;

import com.fleet.telemetry.application.usecase.ProcessTelemetryEvent;
import com.fleet.telemetry.application.usecase.GetVehicleState;
import com.fleet.telemetry.application.usecase.GetVehicleTelemetryCount;
import com.fleet.telemetry.application.usecase.GetVehicleTelemetryHistory;
import com.fleet.telemetry.application.usecase.GetVehicleTelemetryPeriodBoundaries;
import com.fleet.telemetry.application.usecase.TelemetryTimeRange;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import com.fleet.telemetry.infrastructure.adapter.out.kafka.KafkaTelemetryEventPublisher;
import com.fleet.telemetry.infrastructure.adapter.out.kafka.TelemetryEventMessage;
import com.fleet.telemetry.infrastructure.adapter.out.persistence.TelemetryEventJpaRepository;
import com.fleet.telemetry.infrastructure.adapter.out.sse.TelemetrySseBroadcaster;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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

	@Autowired
	private GetVehicleTelemetryCount getVehicleTelemetryCount;

	@Autowired
	private GetVehicleTelemetryHistory getVehicleTelemetryHistory;

	@Autowired
	private GetVehicleTelemetryPeriodBoundaries getVehicleTelemetryPeriodBoundaries;

	@Autowired
	private TelemetryEventListener telemetryEventListener;

	@MockitoSpyBean
	private TelemetrySseBroadcaster telemetrySseBroadcaster;

	@BeforeEach
	void clearTelemetryEvents() {
		repository.deleteAll();
	}

	@Test
	void persistsKafkaEventAndDoesNotDuplicateRepeatedEventId() {
		UUID eventId = UUID.randomUUID();
		TelemetryEvent event = new TelemetryEvent(
				eventId,
				"truck-17",
				40.7128,
				-74.0060,
				Instant.parse("2026-10-09T15:00:00Z"),
				56.5);

		publisher.publish(event);

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
			var persistedEvent = repository.findById(eventId);
			assertTrue(persistedEvent.isPresent());
			assertEquals("truck-17", persistedEvent.get().getVehicleId());
			assertEquals(event.timestamp(), persistedEvent.get().getEventTimestamp());
			assertEquals(event.speed(), persistedEvent.get().getSpeed());
			assertEquals(1L, repository.count());
		});

		assertFalse(processTelemetryEvent.execute(event));
		assertEquals(1L, repository.count());
		assertEquals(56.5, getVehicleState.execute("truck-17").speed());
	}

	@Test
	void publishesSseOnlyAfterCommitAndDoesNotRepublishDuplicateEvent() {
		TelemetryEvent event = new TelemetryEvent(
				UUID.randomUUID(),
				"truck-sse-order",
				40.7128,
				-74.0060,
				Instant.parse("2026-10-09T15:00:00Z"));
		TelemetryEventMessage message = TelemetryEventMessage.from(event);

		doAnswer(invocation -> {
			assertTrue(repository.findById(event.eventId()).isPresent(),
					"the event must be committed before the broadcaster is called");
			return invocation.callRealMethod();
		}).when(telemetrySseBroadcaster).publish(event);

		telemetryEventListener.consume(message);
		telemetryEventListener.consume(message);

		verify(telemetrySseBroadcaster, times(1)).publish(event);
	}

	@Test
	void conflictingPayloadForExistingEventIdIsIgnoredAndFirstPayloadRemainsStored() {
		UUID eventId = UUID.randomUUID();
		TelemetryEvent original = new TelemetryEvent(
				eventId,
				"truck-original",
				40.7128,
				-74.0060,
				Instant.parse("2026-10-09T15:00:00Z"));
		TelemetryEvent conflicting = new TelemetryEvent(
				eventId,
				"truck-conflicting",
				41.0,
				-75.0,
				Instant.parse("2026-10-09T16:00:00Z"));

		assertTrue(processTelemetryEvent.execute(original));
		assertFalse(processTelemetryEvent.execute(conflicting));

		var persisted = repository.findById(eventId).orElseThrow();
		assertEquals(original.vehicleId(), persisted.getVehicleId());
		assertEquals(original.latitude(), persisted.getLatitude());
		assertEquals(original.longitude(), persisted.getLongitude());
		assertEquals(original.timestamp(), persisted.getEventTimestamp());
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
	void rejectsNonFinitePersistedSpeed() {
		UUID eventId = UUID.randomUUID();
		TelemetryEvent invalidEvent = new TelemetryEvent(
				eventId,
				"truck-17",
				40.7128,
				-74.0060,
				Instant.parse("2026-10-09T15:00:00Z"),
				Double.POSITIVE_INFINITY);

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

	@Test
	void countsOnlyVehicleEventsInsideHalfOpenIntervalAndIncludesStartOnly() {
		TelemetryTimeRange range = new TelemetryTimeRange(
				Instant.parse("2026-10-09T10:00:00Z"),
				Instant.parse("2026-10-09T13:00:00Z"));
		save(event("truck-count", "2026-10-09T09:59:59Z"));
		save(event("truck-count", "2026-10-09T10:00:00Z"));
		save(event("truck-count", "2026-10-09T11:00:00Z"));
		save(event("truck-count", "2026-10-09T12:59:59Z"));
		save(event("truck-count", "2026-10-09T13:00:00Z"));
		save(event("other-truck", "2026-10-09T11:00:00Z"));

		assertEquals(3, getVehicleTelemetryCount.execute("truck-count", range).total());
		assertEquals(0, getVehicleTelemetryCount.execute("empty-truck", range).total());
	}

	@Test
	void returnsHistoryInDeterministicChronologicalOrderWithPagination() {
		TelemetryTimeRange range = new TelemetryTimeRange(
				Instant.parse("2026-10-09T10:00:00Z"),
				Instant.parse("2026-10-09T13:00:00Z"));
		TelemetryEvent tieEarlierId = event(
				"truck-history", "2026-10-09T11:00:00Z",
				UUID.fromString("00000000-0000-0000-0000-000000000001"));
		TelemetryEvent tieLaterId = event(
				"truck-history", "2026-10-09T11:00:00Z",
				UUID.fromString("00000000-0000-0000-0000-000000000002"));
		TelemetryEvent newest = event("truck-history", "2026-10-09T12:00:00Z");
		save(newest);
		save(tieLaterId);
		save(tieEarlierId);

		var firstPage = getVehicleTelemetryHistory.execute("truck-history", range, 2, 0, false);
		var secondPage = getVehicleTelemetryHistory.execute("truck-history", range, 2, 2, false);

		assertEquals(
				List.of(tieEarlierId.eventId(), tieLaterId.eventId()),
				firstPage.events().stream().map(TelemetryEvent::eventId).toList());
		assertTrue(firstPage.hasMore());
		assertEquals(List.of(newest.eventId()),
				secondPage.events().stream().map(TelemetryEvent::eventId).toList());
		assertFalse(secondPage.hasMore());
	}

	@Test
	void findsPeriodBoundariesAndReportsEmptyIntervals() {
		TelemetryTimeRange range = new TelemetryTimeRange(
				Instant.parse("2026-10-09T10:00:00Z"),
				Instant.parse("2026-10-09T13:00:00Z"));
		TelemetryEvent first = event("truck-boundary", "2026-10-09T10:00:00Z");
		TelemetryEvent last = event("truck-boundary", "2026-10-09T12:00:00Z");
		save(first);
		save(last);
		save(event("truck-boundary", "2026-10-09T13:00:00Z"));

		var result = getVehicleTelemetryPeriodBoundaries.execute("truck-boundary", range);
		var empty = getVehicleTelemetryPeriodBoundaries.execute("empty-truck", range);

		assertTrue(result.hasEvents());
		assertEquals(first.eventId(), result.first().eventId());
		assertEquals(last.eventId(), result.last().eventId());
		assertFalse(empty.hasEvents());
		assertNull(empty.first());
		assertNull(empty.last());
	}

	private TelemetryEvent event(String vehicleId, String timestamp) {
		return event(vehicleId, timestamp, UUID.randomUUID());
	}

	private TelemetryEvent event(String vehicleId, String timestamp, UUID eventId) {
		return new TelemetryEvent(
				eventId,
				vehicleId,
				40.7128,
				-74.0060,
				Instant.parse(timestamp));
	}

	private void save(TelemetryEvent event) {
		assertTrue(processTelemetryEvent.execute(event));
	}
}
