package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.SubmitTelemetryEvent;
import com.fleet.telemetry.application.usecase.SubmitTelemetryBatch;
import com.fleet.telemetry.application.port.out.TelemetryPublicationException;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TelemetryController.class)
class TelemetryControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SubmitTelemetryEvent submitTelemetryEvent;

	@MockitoBean
	private SubmitTelemetryBatch submitTelemetryBatch;

	@Test
	void acceptsAndSubmitsValidTelemetry() throws Exception {
		mockMvc.perform(post("/api/telemetry")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "eventId": "a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50",
								  "vehicleId": "truck-17",
								  "latitude": 40.7128,
								  "longitude": -74.0060,
								  "timestamp": "2026-10-09T15:00:00Z",
								  "speed": 54.5
								}
								"""))
				.andExpect(status().isAccepted())
				.andExpect(content().string(""));

		var event = forClass(TelemetryEvent.class);
		verify(submitTelemetryEvent).execute(event.capture());
		assertEquals(54.5, event.getValue().speed());
	}

	@Test
	void acceptsBatchAndReturnsPublicationResultForEachOriginalEventId() throws Exception {
		UUID firstId = UUID.fromString("a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50");
		UUID secondId = UUID.fromString("b3ac3a4a-c992-4d8e-9987-0a2f43d7ad51");
		when(submitTelemetryBatch.execute(anyList())).thenAnswer(invocation -> {
			List<TelemetryEvent> events = invocation.getArgument(0);
			assertEquals(List.of(firstId, secondId), events.stream().map(TelemetryEvent::eventId).toList());
			assertEquals(40.7128, events.getFirst().latitude());
			return events.stream()
					.map(event -> new SubmitTelemetryBatch.Result(
							event.eventId(),
							SubmitTelemetryBatch.Status.PUBLISHED))
					.toList();
		});

		mockMvc.perform(post("/api/telemetry/batch")
						.contentType(APPLICATION_JSON)
						.content("""
								{"events":[
								  {"eventId":"a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50","vehicleId":"truck-17","latitude":40.7128,"longitude":-74.006,"timestamp":"2026-10-09T15:00:00Z","speed":54.5},
								  {"eventId":"b3ac3a4a-c992-4d8e-9987-0a2f43d7ad51","vehicleId":"truck-18","latitude":41.0,"longitude":-75.0,"timestamp":"2026-10-09T15:01:00Z"}
								]}
								"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.results.length()").value(2))
				.andExpect(jsonPath("$.results[0].eventId").value(firstId.toString()))
				.andExpect(jsonPath("$.results[0].status").value("PUBLISHED"))
				.andExpect(jsonPath("$.results[0].errorCode").doesNotExist())
				.andExpect(jsonPath("$.results[1].eventId").value(secondId.toString()))
				.andExpect(jsonPath("$.results[1].status").value("PUBLISHED"));
	}

	@Test
	void returnsMultiStatusWithPerEventPublicationFailure() throws Exception {
		UUID publishedId = UUID.fromString("a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50");
		UUID failedId = UUID.fromString("b3ac3a4a-c992-4d8e-9987-0a2f43d7ad51");
		when(submitTelemetryBatch.execute(anyList())).thenReturn(List.of(
				new SubmitTelemetryBatch.Result(publishedId, SubmitTelemetryBatch.Status.PUBLISHED),
				new SubmitTelemetryBatch.Result(failedId, SubmitTelemetryBatch.Status.PUBLICATION_UNCONFIRMED)));

		mockMvc.perform(post("/api/telemetry/batch")
						.contentType(APPLICATION_JSON)
						.content("""
								{"events":[
								  {"eventId":"a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50","vehicleId":"truck-17","latitude":40.7128,"longitude":-74.006,"timestamp":"2026-10-09T15:00:00Z"},
								  {"eventId":"b3ac3a4a-c992-4d8e-9987-0a2f43d7ad51","vehicleId":"truck-18","latitude":41.0,"longitude":-75.0,"timestamp":"2026-10-09T15:01:00Z"}
								]}
								"""))
				.andExpect(status().isMultiStatus())
				.andExpect(jsonPath("$.results[0].eventId").value(publishedId.toString()))
				.andExpect(jsonPath("$.results[0].status").value("PUBLISHED"))
				.andExpect(jsonPath("$.results[1].eventId").value(failedId.toString()))
				.andExpect(jsonPath("$.results[1].status").value("PUBLICATION_UNCONFIRMED"))
				.andExpect(jsonPath("$.results[1].errorCode").value("TELEMETRY_PUBLICATION_UNAVAILABLE"))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("Kafka unavailable"))));
	}

	@Test
	void acceptsBatchAtMaximumSize() throws Exception {
		List<String> eventIds = java.util.stream.IntStream.range(0, TelemetryBatchRequest.MAX_EVENTS)
				.mapToObj(index -> UUID.nameUUIDFromBytes(
						("batch-event-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				.map(UUID::toString)
				.toList();
		when(submitTelemetryBatch.execute(anyList())).thenAnswer(invocation -> {
			List<TelemetryEvent> events = invocation.getArgument(0);
			assertEquals(eventIds, events.stream().map(event -> event.eventId().toString()).toList());
			return events.stream()
					.map(event -> new SubmitTelemetryBatch.Result(
							event.eventId(),
							SubmitTelemetryBatch.Status.PUBLISHED))
					.toList();
		});
		String requestBody = "{\"events\":[" + String.join(",", eventIds.stream()
				.map(eventId -> """
						{"eventId":"%s","vehicleId":"truck-boundary","latitude":40,"longitude":-74,"timestamp":"2026-10-09T15:00:00Z"}
						""".formatted(eventId).trim())
				.toList()) + "]}";

		mockMvc.perform(post("/api/telemetry/batch")
						.contentType(APPLICATION_JSON)
						.content(requestBody))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.results.length()").value(TelemetryBatchRequest.MAX_EVENTS));
	}

	@Test
	void rejectsEmptyOversizedInvalidAndDuplicateIdBatchesBeforePublishing() throws Exception {
		mockMvc.perform(post("/api/telemetry/batch")
						.contentType(APPLICATION_JSON)
						.content("{\"events\":[]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

		List<String> oversizedEvents = java.util.stream.IntStream.rangeClosed(1, 101)
				.mapToObj(index -> """
						{"eventId":"%08d-0000-0000-0000-000000000000","vehicleId":"truck-%s","latitude":40,"longitude":-74,"timestamp":"2026-10-09T15:00:00Z"}
						""".formatted(index, index).trim())
				.toList();
		mockMvc.perform(post("/api/telemetry/batch")
						.contentType(APPLICATION_JSON)
						.content("{\"events\":[" + String.join(",", oversizedEvents) + "]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

		mockMvc.perform(post("/api/telemetry/batch")
						.contentType(APPLICATION_JSON)
						.content("""
								{"events":[
								  {"eventId":"a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50","vehicleId":"truck-17","latitude":91,"longitude":-74,"timestamp":"2026-10-09T15:00:00Z"}
								]}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

		mockMvc.perform(post("/api/telemetry/batch")
						.contentType(APPLICATION_JSON)
						.content("""
								{"events":[
								  {"eventId":"a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50","vehicleId":"truck-17","latitude":40,"longitude":-74,"timestamp":"2026-10-09T15:00:00Z"},
								  {"eventId":"a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50","vehicleId":"truck-18","latitude":41,"longitude":-75,"timestamp":"2026-10-09T15:01:00Z"}
								]}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

		verifyNoInteractions(submitTelemetryBatch);
	}

	@Test
	void acceptsLegacyTelemetryWithoutSpeedAsUnknown() throws Exception {
		mockMvc.perform(post("/api/telemetry")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "eventId": "a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50",
								  "vehicleId": "truck-17",
								  "latitude": 40.7128,
								  "longitude": -74.0060,
								  "timestamp": "2026-10-09T15:00:00Z"
								}
								"""))
				.andExpect(status().isAccepted());

		var event = forClass(TelemetryEvent.class);
		verify(submitTelemetryEvent).execute(event.capture());
		assertEquals(null, event.getValue().speed());
	}

	@Test
	void rejectsInvalidCoordinatesWithoutPublishing() throws Exception {
		mockMvc.perform(post("/api/telemetry")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "eventId": "a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50",
								  "vehicleId": "truck-17",
								  "latitude": 91.0,
								  "longitude": -74.0060,
								  "timestamp": "2026-10-09T15:00:00Z"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

		verifyNoInteractions(submitTelemetryEvent);
	}

	@Test
	void rejectsNegativeSpeedWithoutPublishing() throws Exception {
		mockMvc.perform(post("/api/telemetry")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "eventId": "a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50",
								  "vehicleId": "truck-17",
								  "latitude": 40.7128,
								  "longitude": -74.0060,
								  "timestamp": "2026-10-09T15:00:00Z",
								  "speed": -1.0
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

		verifyNoInteractions(submitTelemetryEvent);
	}

	@Test
	void rejectsInfiniteSpeedWithoutPublishing() throws Exception {
		mockMvc.perform(post("/api/telemetry")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "eventId": "a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50",
								  "vehicleId": "truck-17",
								  "latitude": 40.7128,
								  "longitude": -74.0060,
								  "timestamp": "2026-10-09T15:00:00Z",
								  "speed": 1e309
								}
								"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(submitTelemetryEvent);
	}

	@Test
	void rejectsNanSpeedAtTheRequestValidationBoundary() {
		TelemetryRequest request = new TelemetryRequest(
				java.util.UUID.randomUUID(),
				"truck-17",
				40.0,
				-74.0,
				java.time.Instant.parse("2026-10-09T15:00:00Z"),
				Double.NaN);

		try (var validatorFactory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
			assertFalse(validatorFactory.getValidator().validate(request).isEmpty());
		}
	}

	@Test
	void returnsProblemDetailForMalformedJson() throws Exception {
		mockMvc.perform(post("/api/telemetry")
						.contentType(APPLICATION_JSON)
						.content("{\"eventId\":"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.title").value("Solicitud inválida"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").value(
						"El cuerpo de la solicitud está vacío o contiene JSON inválido."))
				.andExpect(jsonPath("$.instance").value("/api/telemetry"))
				.andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));

		verifyNoInteractions(submitTelemetryEvent);
	}

	@Test
	void returnsProblemDetailForEmptyBody() throws Exception {
		mockMvc.perform(post("/api/telemetry")
						.contentType(APPLICATION_JSON)
						.content(""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.code").value("REQUEST_BODY_INVALID"));
	}

	@Test
	void returnsProblemDetailForMissingFields() throws Exception {
		assertBadRequest("""
				{"eventId":"a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50","vehicleId":"truck-17"}
				""");
	}

	@Test
	void returnsProblemDetailForInvalidUuidAndTimestamp() throws Exception {
		assertBadRequest("""
				{
				  "eventId": "not-a-uuid",
				  "vehicleId": "truck-17",
				  "latitude": 40.7128,
				  "longitude": -74.006,
				  "timestamp": "not-a-timestamp"
				}
				""", "REQUEST_BODY_INVALID");
	}

	@Test
	void returnsProblemDetailForIncorrectFieldTypes() throws Exception {
		assertBadRequest("""
				{
				  "eventId": "a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50",
				  "vehicleId": "truck-17",
				  "latitude": "north",
				  "longitude": -74.006,
				  "timestamp": "2026-10-09T15:00:00Z"
				}
				""", "REQUEST_BODY_INVALID");
	}

	@Test
	void rejectsLongitudeOutsideValidRangeWithProblemDetail() throws Exception {
		assertBadRequest("""
				{
				  "eventId": "a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50",
				  "vehicleId": "truck-17",
				  "latitude": 40.7128,
				  "longitude": 181,
				  "timestamp": "2026-10-09T15:00:00Z"
				}
				""");
	}

	@Test
	void rejectsInvalidVehicleIdWithProblemDetail() throws Exception {
		assertBadRequest("""
				{
				  "eventId": "a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50",
				  "vehicleId": " ",
				  "latitude": 40.7128,
				  "longitude": -74.006,
				  "timestamp": "2026-10-09T15:00:00Z"
				}
				""");
	}

	@Test
	void returnsServiceUnavailableWhenKafkaDoesNotConfirmEvent() throws Exception {
		doThrow(new TelemetryPublicationException("Kafka unavailable", new RuntimeException()))
				.when(submitTelemetryEvent).execute(any());

		mockMvc.perform(post("/api/telemetry")
						.contentType(APPLICATION_JSON)
						.content("""
								{
								  "eventId": "a3ac3a4a-c992-4d8e-9987-0a2f43d7ad50",
								  "vehicleId": "truck-17",
								  "latitude": 40.7128,
								  "longitude": -74.0060,
								  "timestamp": "2026-10-09T15:00:00Z"
								}
								"""))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.title").value("Servicio temporalmente no disponible"))
				.andExpect(jsonPath("$.code").value("TELEMETRY_PUBLICATION_UNAVAILABLE"))
				.andExpect(jsonPath("$.detail").value(
						"No fue posible confirmar la publicación de la telemetría. Intente nuevamente."))
				.andExpect(jsonPath("$.instance").value("/api/telemetry"));
	}

	@Test
	void returnsMethodNotAllowedAsProblemDetail() throws Exception {
		mockMvc.perform(get("/api/telemetry"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(405))
				.andExpect(jsonPath("$.code").value("HTTP_METHOD_NOT_ALLOWED"))
				.andExpect(jsonPath("$.instance").value("/api/telemetry"));
	}

	@Test
	void returnsNotFoundForUnknownRouteAsProblemDetail() throws Exception {
		mockMvc.perform(get("/api/not-a-route"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
				.andExpect(jsonPath("$.instance").value("/api/not-a-route"));
	}

	private void assertBadRequest(String body) throws Exception {
		assertBadRequest(body, "REQUEST_VALIDATION_FAILED");
	}

	private void assertBadRequest(String body, String errorCode) throws Exception {
		mockMvc.perform(post("/api/telemetry")
						.contentType(APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.title").value("Solicitud inválida"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").isNotEmpty())
				.andExpect(jsonPath("$.instance").value("/api/telemetry"))
				.andExpect(jsonPath("$.code").value(errorCode));
	}
}
