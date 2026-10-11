package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.VehicleAlertMonitor;
import com.fleet.telemetry.domain.model.VehicleAlert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VehicleAlertController.class)
class VehicleAlertControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private VehicleAlertMonitor vehicleAlertMonitor;

	@Test
	void returnsCurrentActiveAlertsAsApiDtos() throws Exception {
		Instant detectedAt = Instant.parse("2026-10-11T12:30:00Z");
		VehicleAlert alert = new VehicleAlert(
				UUID.fromString("35c5ac65-390f-45e8-8a8b-a3c6ab2add5f"),
				"truck-17",
				VehicleAlert.Type.SPEEDING,
				"Velocidad excesiva.",
				detectedAt,
				detectedAt,
				85.0,
				VehicleAlert.Status.ACTIVE);
		when(vehicleAlertMonitor.getActiveAlerts()).thenReturn(List.of(alert));

		mockMvc.perform(get("/api/alerts"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].alertId").value(alert.alertId().toString()))
				.andExpect(jsonPath("$[0].vehicleId").value("truck-17"))
				.andExpect(jsonPath("$[0].type").value("SPEEDING"))
				.andExpect(jsonPath("$[0].status").value("ACTIVE"))
				.andExpect(jsonPath("$[0].speed").value(85.0));

		verify(vehicleAlertMonitor).getActiveAlerts();
	}
}
