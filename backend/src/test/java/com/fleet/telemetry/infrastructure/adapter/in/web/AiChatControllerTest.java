package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.AiProviderUnavailableException;
import com.fleet.telemetry.application.usecase.AiTelemetryQueryException;
import com.fleet.telemetry.application.usecase.AskTelemetryAgent;
import com.fleet.telemetry.application.usecase.TelemetryAgentAnswer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiChatController.class)
class AiChatControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AskTelemetryAgent askTelemetryAgent;

	@Test
	void returnsAgentAnswerAndToolMetadata() throws Exception {
		when(askTelemetryAgent.ask("¿Dónde está truck-22?"))
				.thenReturn(new TelemetryAgentAnswer(
						"La última posición es 40.7128, -74.006.",
						List.of("get_vehicle_current_state")));

		mockMvc.perform(post("/api/ai/chat")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"question":"¿Dónde está truck-22?"}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.answer").value("La última posición es 40.7128, -74.006."))
				.andExpect(jsonPath("$.toolsUsed[0]").value("get_vehicle_current_state"));

		verify(askTelemetryAgent).ask("¿Dónde está truck-22?");
	}

	@Test
	void rejectsBlankAndOversizedQuestions() throws Exception {
		mockMvc.perform(post("/api/ai/chat")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"question":"  "}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

		mockMvc.perform(post("/api/ai/chat")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"question\":\"" + "q".repeat(2001) + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("REQUEST_VALIDATION_FAILED"));

		verifyNoInteractions(askTelemetryAgent);
	}

	@Test
	void mapsProviderFailureToSafeProblemDetail() throws Exception {
		doThrow(new AiProviderUnavailableException("sensitive provider detail"))
				.when(askTelemetryAgent).ask("consulta");

		mockMvc.perform(post("/api/ai/chat")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"question":"consulta"}
								"""))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("AI_PROVIDER_UNAVAILABLE"))
				.andExpect(jsonPath("$.detail")
						.value("No fue posible completar la consulta con el asistente de IA."))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("sensitive provider detail"))));
	}

	@Test
	void mapsTelemetryQueryFailureDifferentlyFromMissingVehicle() throws Exception {
		doThrow(new AiTelemetryQueryException(
				"No fue posible consultar la telemetría.",
				new IllegalStateException("database internals")))
				.when(askTelemetryAgent).ask("¿Dónde está truck-22?");

		mockMvc.perform(post("/api/ai/chat")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"question":"¿Dónde está truck-22?"}
								"""))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("AI_TELEMETRY_QUERY_UNAVAILABLE"))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("database internals"))));
	}
}
