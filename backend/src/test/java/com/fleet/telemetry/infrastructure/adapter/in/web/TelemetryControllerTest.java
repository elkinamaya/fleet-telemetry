package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.SubmitTelemetryEvent;
import com.fleet.telemetry.infrastructure.adapter.out.kafka.TelemetryPublicationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
								  "timestamp": "2026-10-09T15:00:00Z"
								}
								"""))
				.andExpect(status().isAccepted());

		verify(submitTelemetryEvent).execute(any());
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
				.andExpect(status().isBadRequest());

		verifyNoInteractions(submitTelemetryEvent);
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
				.andExpect(status().isServiceUnavailable());
	}
}
