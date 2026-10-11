package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.VehicleTelemetryHistoryQuery;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GetVehicleTelemetryHistoryTest {

	@Test
	void capsPageSizeReportsMoreRecordsAndReturnsChronologicalOrder() {
		VehicleTelemetryHistoryQuery query = mock(VehicleTelemetryHistoryQuery.class);
		TelemetryEvent older = event("truck-17", "2026-10-09T10:00:00Z", "00000000-0000-0000-0000-000000000001");
		TelemetryEvent middle = event("truck-17", "2026-10-09T11:00:00Z", "00000000-0000-0000-0000-000000000002");
		TelemetryEvent newer = event("truck-17", "2026-10-09T12:00:00Z", "00000000-0000-0000-0000-000000000003");
		when(query.findEvents("truck-17", Instant.parse("2026-10-09T05:00:00Z"),
				Instant.parse("2026-10-10T05:00:00Z"), 3, 0, true))
				.thenReturn(List.of(newer, middle, older));
		GetVehicleTelemetryHistory history = new GetVehicleTelemetryHistory(query, 2);

		VehicleTelemetryHistory page = history.execute(
				"truck-17",
				new TelemetryTimeRange(
						Instant.parse("2026-10-09T05:00:00Z"),
						Instant.parse("2026-10-10T05:00:00Z")),
				20,
				0,
				true);

		assertEquals(2, page.limit());
		assertTrue(page.hasMore());
		assertEquals(List.of(middle.eventId(), newer.eventId()),
				page.events().stream().map(TelemetryEvent::eventId).toList());
		verify(query).findEvents("truck-17", Instant.parse("2026-10-09T05:00:00Z"),
				Instant.parse("2026-10-10T05:00:00Z"), 3, 0, true);
	}

	@Test
	void reversesImmutableHistoryPageWhenNoExtraRecordsExist() {
		VehicleTelemetryHistoryQuery query = mock(VehicleTelemetryHistoryQuery.class);
		TelemetryEvent newest = event(
				"truck-17",
				"2026-10-09T12:00:00Z",
				"00000000-0000-0000-0000-000000000003");
		TelemetryEvent older = event(
				"truck-17",
				"2026-10-09T11:00:00Z",
				"00000000-0000-0000-0000-000000000002");
		when(query.findEvents(
				"truck-17",
				Instant.parse("2026-10-09T05:00:00Z"),
				Instant.parse("2026-10-10T05:00:00Z"),
				6,
				0,
				true)).thenReturn(List.of(newest, older));
		GetVehicleTelemetryHistory history = new GetVehicleTelemetryHistory(query, 5);

		VehicleTelemetryHistory page = history.execute(
				"truck-17",
				new TelemetryTimeRange(
						Instant.parse("2026-10-09T05:00:00Z"),
						Instant.parse("2026-10-10T05:00:00Z")),
				5,
				0,
				true);

		assertFalse(page.hasMore());
		assertEquals(List.of(older.eventId(), newest.eventId()),
				page.events().stream().map(TelemetryEvent::eventId).toList());
	}

	@Test
	void returnsLatestResultsInChronologicalOrderFromImmutableQueryResult() {
		VehicleTelemetryHistoryQuery query = mock(VehicleTelemetryHistoryQuery.class);
		TelemetryEvent newest = event(
				"truck-17",
				"2026-10-09T12:00:00Z",
				"00000000-0000-0000-0000-000000000003");
		TelemetryEvent oldest = event(
				"truck-17",
				"2026-10-09T10:00:00Z",
				"00000000-0000-0000-0000-000000000001");
		when(query.findLatestEvents("truck-17", 3, 0)).thenReturn(List.of(newest, oldest));
		GetVehicleTelemetryHistory history = new GetVehicleTelemetryHistory(query, 2);

		VehicleTelemetryHistory page = history.latest("truck-17", 2, 0);

		assertFalse(page.hasMore());
		assertEquals(List.of(oldest.eventId(), newest.eventId()),
				page.events().stream().map(TelemetryEvent::eventId).toList());
	}

	private TelemetryEvent event(String vehicle, String time, String id) {
		return new TelemetryEvent(
				UUID.fromString(id),
				vehicle,
				40.0,
				-74.0,
				Instant.parse(time));
	}
}
