package com.fleet.telemetry.application.usecase;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.Completion;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.Message;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.ToolCall;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.ToolDefinition;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AskTelemetryAgentTest {

	private final GetVehicleState getVehicleState = mock(GetVehicleState.class);
	private final GetVehicleTelemetryCount count = mock(GetVehicleTelemetryCount.class);
	private final GetVehicleTelemetryHistory history = mock(GetVehicleTelemetryHistory.class);
	private final GetVehicleTelemetryPeriodBoundaries boundaries =
			mock(GetVehicleTelemetryPeriodBoundaries.class);
	private final AiChatCompletionClient client = mock(AiChatCompletionClient.class);
	private final AskTelemetryAgent agent = new AskTelemetryAgent(
			getVehicleState,
			count,
			history,
			boundaries,
			new TelemetryPeriodResolver(Clock.fixed(
					Instant.parse("2026-10-09T18:00:00Z"),
					ZoneOffset.UTC)),
			client);

	@Test
	void usesPersistedVehicleStateForCurrentStateQuestion() {
		TelemetryEvent state = event("truck-22", "2026-10-09T15:00:00Z");
		when(client.complete(anyList(), anyList()))
				.thenReturn(tool("get_vehicle_current_state", """
						{"vehicleId":"truck-22"}
						"""))
				.thenReturn(finalAnswer("La última posición es 40.7128, -74.006."));
		when(getVehicleState.execute("truck-22")).thenReturn(state);

		TelemetryAgentAnswer answer = agent.ask("¿Dónde está truck-22?");

		assertEquals(List.of("get_vehicle_current_state"), answer.toolsUsed());
		verify(getVehicleState).execute("truck-22");
		assertToolDefinitionsContainAllTools();
	}

	@Test
	void countQuestionExecutesCountToolWithBogotaDayRange() {
		when(client.complete(anyList(), anyList()))
				.thenReturn(tool("count_vehicle_reports", """
						{"vehicleId":"truck-17","period":"today","from":null,"to":null}
						"""))
				.thenReturn(finalAnswer("Se reportó 3 veces hoy."));

		TelemetryAgentAnswer answer = agent.ask("¿Cuántas veces se reportó truck-17 hoy?");

		assertEquals(List.of("count_vehicle_reports"), answer.toolsUsed());
		org.mockito.ArgumentCaptor<TelemetryTimeRange> range =
				org.mockito.ArgumentCaptor.forClass(TelemetryTimeRange.class);
		verify(count).execute(eq("truck-17"), range.capture());
		assertEquals(Instant.parse("2026-10-09T05:00:00Z"), range.getValue().fromInclusive());
		assertEquals(Instant.parse("2026-10-10T05:00:00Z"), range.getValue().toExclusive());
	}

	@Test
	void historyQuestionExecutesHistoryToolAndReturnsUsedMetadata() {
		when(client.complete(anyList(), anyList()))
				.thenReturn(tool("get_vehicle_telemetry_history", """
						{"vehicleId":"truck-17","period":"last_hour","from":null,"to":null,
						 "limit":10,"offset":0,"latest":false}
						"""))
				.thenReturn(finalAnswer("Encontré 2 posiciones."));
		when(history.execute(
				eq("truck-17"),
				org.mockito.ArgumentMatchers.any(),
				eq(10),
				eq(0),
				eq(false))).thenReturn(new VehicleTelemetryHistory(
						"truck-17",
						"2026-10-09T17:00:00Z",
						"2026-10-09T18:00:00Z",
						10,
						0,
						false,
						List.of()));

		TelemetryAgentAnswer answer = agent.ask("Muéstrame los reportes de truck-17 de la última hora.");

		assertEquals(List.of("get_vehicle_telemetry_history"), answer.toolsUsed());
		verify(history).execute(eq("truck-17"), eq(new TelemetryTimeRange(
				Instant.parse("2026-10-09T17:00:00Z"),
				Instant.parse("2026-10-09T18:00:00Z"))), eq(10), eq(0), eq(false));
	}

	@Test
	void latestHistoryQuestionAllowsOmittedOptionalFromAndToArguments() {
		TelemetryEvent oldest = event("truck-17", "2026-10-09T10:00:00Z");
		TelemetryEvent middle = event("truck-17", "2026-10-09T11:00:00Z");
		TelemetryEvent newest = event("truck-17", "2026-10-09T12:00:00Z");
		when(client.complete(anyList(), anyList()))
		.thenReturn(tool("get_vehicle_telemetry_history", """
				{"vehicleId":"truck-17","period":"all","limit":5,"offset":0,"latest":true}
				"""))
		.thenReturn(finalAnswer("Hay tres telemetrías."));
		when(history.latest("truck-17", 5, 0)).thenReturn(new VehicleTelemetryHistory(
		"truck-17", null, null, 5, 0, false, List.of(oldest, middle, newest)));

		TelemetryAgentAnswer answer = agent.ask("Muestra las últimas 5 telemetrías de truck-17.");

		assertEquals(List.of("get_vehicle_telemetry_history"), answer.toolsUsed());
		verify(history).latest("truck-17", 5, 0);
		org.mockito.ArgumentCaptor<List<Message>> messages =
		org.mockito.ArgumentCaptor.forClass(List.class);
		verify(client, org.mockito.Mockito.times(2)).complete(messages.capture(), anyList());
		String toolResult = messages.getAllValues().getLast().getLast().content();
		assertTrue(toolResult.contains("\"timestamp\":\"2026-10-09T10:00:00Z\""));
		assertTrue(toolResult.contains("\"timestamp\":\"2026-10-09T11:00:00Z\""));
		assertTrue(toolResult.contains("\"timestamp\":\"2026-10-09T12:00:00Z\""));
		assertTrue(toolResult.indexOf("2026-10-09T10:00:00Z")
		< toolResult.indexOf("2026-10-09T12:00:00Z"));
	}

	@Test
	void toolLogsContainSafeNameValidationAndOutcomeWithoutVehicleArguments() {
		when(client.complete(anyList(), anyList()))
				.thenReturn(tool("get_vehicle_telemetry_history", """
						{"vehicleId":"truck-17","period":"all","limit":5,"offset":0,"latest":true}
						"""))
				.thenReturn(finalAnswer("Hay tres telemetrías."));
		when(history.latest("truck-17", 5, 0)).thenReturn(new VehicleTelemetryHistory(
				"truck-17", null, null, 5, 0, false, List.of()));
		Logger logger = (Logger) LoggerFactory.getLogger(AskTelemetryAgent.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		MDC.put("correlationId", "test-correlation-123");

		try {
			agent.ask("Muestra las últimas 5 telemetrías de truck-17.");

			String toolLogs = appender.list.stream()
					.map(ILoggingEvent::getFormattedMessage)
					.reduce("", (left, right) -> left + "\n" + right);
			assertTrue(toolLogs.contains("test-correlation-123"));
			assertTrue(toolLogs.contains("get_vehicle_telemetry_history"));
			assertTrue(toolLogs.contains("argumentsValidated=true"));
			assertTrue(toolLogs.contains("result=success"));
			assertFalse(toolLogs.contains("truck-17"));
		} finally {
			MDC.clear();
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void boundariesQuestionExecutesPeriodBoundaryTool() {
		when(client.complete(anyList(), anyList()))
				.thenReturn(tool("get_vehicle_period_boundaries", """
						{"vehicleId":"truck-18","period":"yesterday","from":null,"to":null}
						"""))
				.thenReturn(finalAnswer("La primera y última posición fueron..."));
		when(boundaries.execute(eq("truck-18"), org.mockito.ArgumentMatchers.any()))
				.thenReturn(new VehicleTelemetryPeriodBoundaries(
						"truck-18",
						"2026-10-08T05:00:00Z",
						"2026-10-09T05:00:00Z",
						false,
						null,
						null));

		TelemetryAgentAnswer answer = agent.ask("¿Dónde comenzó y terminó truck-18 ayer?");

		assertEquals(List.of("get_vehicle_period_boundaries"), answer.toolsUsed());
		verify(boundaries).execute(eq("truck-18"), eq(new TelemetryTimeRange(
				Instant.parse("2026-10-08T05:00:00Z"),
				Instant.parse("2026-10-09T05:00:00Z"))));
	}

	@Test
	void explicitDateBoundariesSerializeTelemetryInstantsAsIsoStrings() {
		TelemetryEvent first = event("truck-17", "2026-10-02T12:00:00Z");
		TelemetryEvent last = event("truck-17", "2026-10-09T20:00:00Z");
		when(client.complete(anyList(), anyList()))
				.thenReturn(tool("get_vehicle_period_boundaries", """
						{"vehicleId":"truck-17","period":"explicit",
						 "from":"2026-10-01","to":"2026-10-09"}
						"""))
				.thenReturn(finalAnswer("Encontré los extremos del periodo."));
		when(boundaries.execute(eq("truck-17"), org.mockito.ArgumentMatchers.any()))
				.thenReturn(new VehicleTelemetryPeriodBoundaries(
						"truck-17",
						"2026-10-01T05:00:00Z",
						"2026-10-10T05:00:00Z",
						true,
						first,
						last));

		TelemetryAgentAnswer answer = agent.ask(
				"¿Cuál fue la primera y la última posición de truck-17 entre 2026-10-01 y 2026-10-09?");

		assertEquals(List.of("get_vehicle_period_boundaries"), answer.toolsUsed());
		org.mockito.ArgumentCaptor<List<Message>> messages =
				org.mockito.ArgumentCaptor.forClass(List.class);
		verify(client, org.mockito.Mockito.times(2)).complete(messages.capture(), anyList());
		String toolResult = messages.getAllValues().getLast().getLast().content();
		assertTrue(toolResult.contains("\"fromInclusive\":\"2026-10-01T05:00:00Z\""));
		assertTrue(toolResult.contains("\"toExclusive\":\"2026-10-10T05:00:00Z\""));
		assertTrue(toolResult.contains("\"timestamp\":\"2026-10-02T12:00:00Z\""));
		assertTrue(toolResult.contains("\"timestamp\":\"2026-10-09T20:00:00Z\""));
	}

	@Test
	void asksForMissingPeriodWithoutExecutingTool() {
		when(client.complete(anyList(), anyList())).thenReturn(tool("count_vehicle_reports", """
				{"vehicleId":"truck-17","period":"today","from":null,"to":null}
				"""));

		TelemetryAgentAnswer answer = agent.ask("¿Cuántas veces se reportó truck-17?");

		assertTrue(answer.answer().contains("Qué periodo"));
		assertTrue(answer.toolsUsed().isEmpty());
		verify(count, never()).execute(eq("truck-17"), org.mockito.ArgumentMatchers.any());
		verify(client, never()).complete(anyList(), eq(List.of()));
	}

	@Test
	void reportsNoTelemetryWithoutInventingState() {
		when(client.complete(anyList(), anyList()))
				.thenReturn(tool("get_vehicle_current_state", """
						{"vehicleId":"missing-truck"}
						"""))
				.thenReturn(finalAnswer("No hay telemetría."));
		when(getVehicleState.execute("missing-truck"))
				.thenThrow(new VehicleTelemetryNotFoundException("missing-truck"));

		TelemetryAgentAnswer answer = agent.ask("¿Dónde está missing-truck?");

		assertEquals(List.of("get_vehicle_current_state"), answer.toolsUsed());
		org.mockito.ArgumentCaptor<List<Message>> messages =
				org.mockito.ArgumentCaptor.forClass(List.class);
		verify(client).complete(messages.capture(), eq(List.of()));
		assertTrue(messages.getValue().getLast().content().contains("\"found\":false"));
	}

	@Test
	void distinguishesPersistenceFailureFromNoResults() {
		when(client.complete(anyList(), anyList()))
				.thenReturn(tool("count_vehicle_reports", """
						{"vehicleId":"truck-22","period":"today","from":null,"to":null}
						"""));
		when(count.execute(eq("truck-22"), org.mockito.ArgumentMatchers.any()))
				.thenThrow(new IllegalStateException("database details"));

		AiTelemetryQueryException exception = assertThrows(
				AiTelemetryQueryException.class,
				() -> agent.ask("¿Cuántos reportes tuvo truck-22 hoy?"));

		assertFalse(exception.getMessage().contains("database details"));
	}

	@Test
	void rejectsPathLikeVehicleIdsBeforeQuerying() {
		when(client.complete(anyList(), anyList())).thenReturn(tool("get_vehicle_current_state", """
				{"vehicleId":"../secrets"}
				"""));

		assertThrows(AiProviderUnavailableException.class, () -> agent.ask("consulta"));
		verify(getVehicleState, never()).execute("../secrets");
	}

	@Test
	void identifiesProviderFailureDuringFollowUpAfterToolExecution() {
		when(client.complete(anyList(), anyList()))
				.thenReturn(tool("get_vehicle_current_state", """
						{"vehicleId":"truck-17"}
						"""))
				.thenThrow(new AiProviderUnavailableException("provider failure"));
		when(getVehicleState.execute("truck-17")).thenReturn(event("truck-17", "2026-10-09T15:00:00Z"));
		Logger logger = (Logger) LoggerFactory.getLogger(AskTelemetryAgent.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			assertThrows(AiProviderUnavailableException.class,
					() -> agent.ask("¿Cuál es la posición de truck-17?"));

			assertTrue(appender.list.stream()
					.map(ILoggingEvent::getFormattedMessage)
					.anyMatch(message -> message.contains("after tool execution")));
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	private void assertToolDefinitionsContainAllTools() {
		org.mockito.ArgumentCaptor<List<ToolDefinition>> tools =
				org.mockito.ArgumentCaptor.forClass(List.class);
		verify(client, org.mockito.Mockito.atLeastOnce()).complete(anyList(), tools.capture());
		List<ToolDefinition> definitions = tools.getAllValues().getFirst();
		assertEquals(List.of(
				"get_vehicle_current_state",
				"count_vehicle_reports",
				"get_vehicle_telemetry_history",
				"get_vehicle_period_boundaries"),
				definitions.stream().map(ToolDefinition::name).toList());
		for (ToolDefinition definition : definitions) {
			Map<?, ?> properties = (Map<?, ?>) definition.parameters().get("properties");
			assertEquals("object", definition.parameters().get("type"));
			assertEquals(false, definition.parameters().get("additionalProperties"));
			Set<?> required = Set.copyOf((List<?>) definition.parameters().get("required"));
			assertTrue(properties.keySet().containsAll(required));
			if (!definition.name().equals("get_vehicle_current_state")) {
				assertFalse(required.contains("from"));
				assertFalse(required.contains("to"));
			}
		}
	}

	private Completion tool(String name, String arguments) {
		return new Completion(null, List.of(new ToolCall("call-1", name, arguments)));
	}

	private Completion finalAnswer(String content) {
		return new Completion(content, List.of());
	}

	private TelemetryEvent event(String vehicleId, String timestamp) {
		return new TelemetryEvent(
				UUID.randomUUID(),
				vehicleId,
				40.7128,
				-74.0060,
				Instant.parse(timestamp));
	}
}
