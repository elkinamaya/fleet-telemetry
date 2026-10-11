package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.SubmitTelemetryEvent;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import com.fleet.telemetry.infrastructure.adapter.out.kafka.TelemetryPublicationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
