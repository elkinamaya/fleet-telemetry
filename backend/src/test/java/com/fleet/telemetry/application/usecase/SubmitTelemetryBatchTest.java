package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.TelemetryPublicationException;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SubmitTelemetryBatchTest {

	@Test
	void preservesEventIdsAndContinuesAfterIndividualPublicationFailure() {
		SubmitTelemetryEvent submitTelemetryEvent = mock(SubmitTelemetryEvent.class);
		UUID failedId = UUID.fromString("a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50");
		UUID publishedId = UUID.fromString("b3ac3a4a-c992-4d8e-9987-0a2f43d7ad51");
		TelemetryEvent failedEvent = event(failedId, "truck-17");
		TelemetryEvent publishedEvent = event(publishedId, "truck-18");
		doThrow(new TelemetryPublicationException("Kafka did not confirm", new RuntimeException()))
				.when(submitTelemetryEvent).execute(failedEvent);

		List<SubmitTelemetryBatch.Result> results = new SubmitTelemetryBatch(submitTelemetryEvent)
				.execute(List.of(failedEvent, publishedEvent));

		assertEquals(List.of(
				new SubmitTelemetryBatch.Result(failedId, SubmitTelemetryBatch.Status.PUBLICATION_UNCONFIRMED),
				new SubmitTelemetryBatch.Result(publishedId, SubmitTelemetryBatch.Status.PUBLISHED)), results);
		verify(submitTelemetryEvent).execute(failedEvent);
		verify(submitTelemetryEvent).execute(publishedEvent);
	}

	private TelemetryEvent event(UUID eventId, String vehicleId) {
		return new TelemetryEvent(
				eventId,
				vehicleId,
				40.0,
				-74.0,
				Instant.parse("2026-10-09T15:00:00Z"),
				45.0);
	}
}
