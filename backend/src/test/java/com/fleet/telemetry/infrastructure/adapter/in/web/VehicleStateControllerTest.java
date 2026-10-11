package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.GetVehicleState;
import com.fleet.telemetry.application.usecase.VehicleTelemetryNotFoundException;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VehicleStateController.class)
class VehicleStateControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private GetVehicleState getVehicleState;

	@Test
	void returnsLatestVehicleState() throws Exception {
		UUID eventId = UUID.randomUUID();
		when(getVehicleState.execute("truck-17")).thenReturn(new TelemetryEvent(
				eventId,
				"truck-17",
				40.7128,
				-74.0060,
				Instant.parse("2026-10-09T15:00:00Z"),
				63.5));

		mockMvc.perform(get("/api/vehicles/truck-17/state"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.eventId").value(eventId.toString()))
				.andExpect(jsonPath("$.vehicleId").value("truck-17"))
				.andExpect(jsonPath("$.latitude").value(40.7128))
				.andExpect(jsonPath("$.longitude").value(-74.0060))
				.andExpect(jsonPath("$.timestamp").value("2026-10-09T15:00:00Z"))
				.andExpect(jsonPath("$.speed").value(63.5));

		verify(getVehicleState).execute("truck-17");
	}

	@Test
	void returnsNotFoundWhenVehicleHasNoTelemetry() throws Exception {
		when(getVehicleState.execute("unknown"))
				.thenThrow(new VehicleTelemetryNotFoundException("unknown"));

		mockMvc.perform(get("/api/vehicles/unknown/state"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.title").value("Recurso no encontrado"))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value("No hay telemetría registrada para el vehículo 'unknown'."))
				.andExpect(jsonPath("$.instance").value("/api/vehicles/unknown/state"))
				.andExpect(jsonPath("$.code").value("VEHICLE_TELEMETRY_NOT_FOUND"));

		verify(getVehicleState).execute("unknown");
	}

	@Test
	void returnsControlledInternalErrorWithoutExposingExceptionDetails() throws Exception {
		when(getVehicleState.execute("truck-17"))
				.thenThrow(new IllegalStateException("INTERNAL_DETAIL_MARKER"));

		mockMvc.perform(get("/api/vehicles/truck-17/state"))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.type").value("about:blank"))
				.andExpect(jsonPath("$.title").value("Error interno del servidor"))
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.detail").value("Ocurrió un error interno al procesar la solicitud."))
				.andExpect(jsonPath("$.instance").value("/api/vehicles/truck-17/state"))
				.andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("INTERNAL_DETAIL_MARKER"))));
	}

	@Test
	void rejectsBlankVehicleIdWithProblemDetail() throws Exception {
		String tooLongVehicleId = "v".repeat(101);
		mockMvc.perform(get("/api/vehicles/{vehicleId}/state", tooLongVehicleId))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));
	}
}
