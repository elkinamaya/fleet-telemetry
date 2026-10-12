package com.fleet.telemetry.infrastructure.adapter.out.ai;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.Message;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.ToolCall;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.ToolDefinition;
import com.fleet.telemetry.application.usecase.AiProviderUnavailableException;
import com.fleet.telemetry.application.usecase.AiTelemetryQueryException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;

class OpenAiChatCompletionAdapterTest {

	@Test
	void sendsToolCallingRequestToConfiguredProvider() {
		RestClient.Builder builder = RestClient.builder();
		builder.baseUrl("https://provider.example/v1");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		OpenAiChatCompletionAdapter adapter = new OpenAiChatCompletionAdapter(
				builder.build(),
				"test-model",
				"secret-token");
		server.expect(requestTo("https://provider.example/v1/chat/completions"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer secret-token"))
				.andExpect(content().json("""
						{
						  "model": "test-model",
						  "tools": [{
						    "type": "function",
						    "function": {
						      "name": "get_vehicle_current_state",
						      "description": "Reads current state.",
						      "parameters": {
						        "type": "object",
						        "properties": {"vehicleId": {"type": "string"}},
						        "required": ["vehicleId"],
						        "additionalProperties": false
						      }
						    }
						  }],
						  "tool_choice": "auto"
						}
						"""))
				.andRespond(withSuccess("""
						{"choices":[{"message":{"content":null,"tool_calls":[{
						  "id":"call-1",
						  "type":"function",
						  "function":{"name":"get_vehicle_current_state","arguments":"{\\"vehicleId\\":\\"truck-22\\"}"}
						}]}}]}
						""", MediaType.APPLICATION_JSON));

		var result = adapter.complete(
				List.of(new Message("user", "¿Dónde está truck-22?", null, null)),
				List.of(new ToolDefinition(
						"get_vehicle_current_state",
						"Reads current state.",
						Map.of(
								"type", "object",
								"properties", Map.of("vehicleId", Map.of("type", "string")),
								"required", List.of("vehicleId"),
								"additionalProperties", false))));

		assertEquals("get_vehicle_current_state", result.toolCalls().getFirst().name());
		assertEquals("{\"vehicleId\":\"truck-22\"}", result.toolCalls().getFirst().arguments());
		server.verify();
	}

	@Test
	void opensAfterFiveTransientFailuresAndClosesAfterTwoHalfOpenSuccesses() throws Exception {
		RestClient.Builder builder = RestClient.builder();
		builder.baseUrl("https://provider.example/v1");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		CircuitBreaker circuitBreaker = testCircuitBreaker(Duration.ofMillis(25));
		OpenAiChatCompletionAdapter adapter = new OpenAiChatCompletionAdapter(
				builder.build(),
				"test-model",
				"fake-token",
				circuitBreaker);
		for (int i = 0; i < 5; i++) {
			server.expect(requestTo("https://provider.example/v1/chat/completions"))
					.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
		}
		server.expect(requestTo("https://provider.example/v1/chat/completions"))
				.andRespond(withSuccess("""
						{"choices":[{"message":{"content":"respuesta uno"}}]}
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://provider.example/v1/chat/completions"))
				.andRespond(withSuccess("""
						{"choices":[{"message":{"content":"respuesta dos"}}]}
						""", MediaType.APPLICATION_JSON));

		for (int i = 0; i < 5; i++) {
			assertThrows(
					AiProviderUnavailableException.class,
					() -> adapter.complete(List.of(new Message("user", "consulta", null, null)), List.of()));
		}
		assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
		assertEquals(5, circuitBreaker.getMetrics().getNumberOfFailedCalls());
		assertThrows(
				AiProviderUnavailableException.class,
				() -> adapter.complete(List.of(new Message("user", "consulta", null, null)), List.of()));

		Thread.sleep(50);

		adapter.complete(List.of(new Message("user", "consulta", null, null)), List.of());
		assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
		adapter.complete(List.of(new Message("user", "consulta", null, null)), List.of());
		assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
		server.verify();
	}

	@Test
	void successfulProviderResponsesKeepCircuitClosed() {
		RestClient.Builder builder = RestClient.builder();
		builder.baseUrl("https://provider.example/v1");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		CircuitBreaker circuitBreaker = testCircuitBreaker(Duration.ofSeconds(1));
		OpenAiChatCompletionAdapter adapter = new OpenAiChatCompletionAdapter(
				builder.build(),
				"test-model",
				"fake-token",
				circuitBreaker);
		for (int i = 0; i < 2; i++) {
			server.expect(requestTo("https://provider.example/v1/chat/completions"))
					.andRespond(withSuccess("""
							{"choices":[{"message":{"content":"respuesta"}}]}
							""", MediaType.APPLICATION_JSON));
		}

		adapter.complete(List.of(new Message("user", "consulta", null, null)), List.of());
		adapter.complete(List.of(new Message("user", "consulta", null, null)), List.of());

		assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
		assertEquals(2, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
		assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
		server.verify();
	}

	@Test
	void countsConnectionTimeoutAsTransientFailure() {
		RestClient.Builder builder = RestClient.builder();
		builder.baseUrl("https://provider.example/v1");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		CircuitBreaker circuitBreaker = testCircuitBreaker(Duration.ofSeconds(1));
		OpenAiChatCompletionAdapter adapter = new OpenAiChatCompletionAdapter(
				builder.build(),
				"test-model",
				"fake-token",
				circuitBreaker);
		server.expect(requestTo("https://provider.example/v1/chat/completions"))
				.andRespond(withException(new IOException("simulated timeout")));

		assertThrows(
				AiProviderUnavailableException.class,
				() -> adapter.complete(List.of(new Message("user", "consulta", null, null)), List.of()));

		assertEquals(1, circuitBreaker.getMetrics().getNumberOfFailedCalls());
		assertEquals(0, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
		server.verify();
	}

	@Test
	void ignoresProviderClientErrorsWithoutRecordingSuccessOrFailure() {
		RestClient.Builder builder = RestClient.builder();
		builder.baseUrl("https://provider.example/v1");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		CircuitBreaker circuitBreaker = testCircuitBreaker(Duration.ofSeconds(1));
		OpenAiChatCompletionAdapter adapter = new OpenAiChatCompletionAdapter(
				builder.build(),
				"test-model",
				"fake-token",
				circuitBreaker);
		for (HttpStatus status : List.of(
				HttpStatus.BAD_REQUEST,
				HttpStatus.UNAUTHORIZED,
				HttpStatus.FORBIDDEN,
				HttpStatus.TOO_MANY_REQUESTS)) {
			server.expect(requestTo("https://provider.example/v1/chat/completions"))
					.andRespond(withStatus(status));
		}

		for (int i = 0; i < 4; i++) {
			assertThrows(
					AiProviderUnavailableException.class,
					() -> adapter.complete(List.of(new Message("user", "consulta", null, null)), List.of()));
		}

		assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
		assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
		assertEquals(0, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
		assertEquals(0, circuitBreaker.getMetrics().getNumberOfBufferedCalls());
		server.verify();
	}

	@Test
	void ignoresMissingKeyLocalValidationAndTelemetryQueryFailures() {
		CircuitBreaker circuitBreaker = configuredCircuitBreaker(Duration.ofSeconds(1));
		OpenAiChatCompletionAdapter adapter = new OpenAiChatCompletionAdapter(
				RestClient.builder().baseUrl("https://provider.example/v1").build(),
				"test-model",
				"",
				circuitBreaker);

		assertThrows(
				AiProviderUnavailableException.class,
				() -> adapter.complete(List.of(new Message("user", "consulta", null, null)), List.of()));
		assertThrows(
				NullPointerException.class,
				() -> new OpenAiChatCompletionAdapter(
						RestClient.builder().build(),
						"test-model",
						"fake-token",
						circuitBreaker).complete(null, List.of()));
		assertThrows(
				AiTelemetryQueryException.class,
				() -> circuitBreaker.executeRunnable(() -> {
					throw new AiTelemetryQueryException("consulta fallida", new IllegalStateException());
				}));

		assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
		assertEquals(0, circuitBreaker.getMetrics().getNumberOfFailedCalls());
		assertEquals(0, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
		assertEquals(0, circuitBreaker.getMetrics().getNumberOfBufferedCalls());
	}

	@Test
	void countsMalformedProviderResponseAsTransientFailure() {
		RestClient.Builder builder = RestClient.builder();
		builder.baseUrl("https://provider.example/v1");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		CircuitBreaker circuitBreaker = testCircuitBreaker(Duration.ofSeconds(1));
		OpenAiChatCompletionAdapter adapter = new OpenAiChatCompletionAdapter(
				builder.build(),
				"test-model",
				"fake-token",
				circuitBreaker);
		server.expect(requestTo("https://provider.example/v1/chat/completions"))
				.andRespond(withSuccess("{invalid json", MediaType.APPLICATION_JSON));

		assertThrows(
				AiProviderUnavailableException.class,
				() -> adapter.complete(List.of(new Message("user", "consulta", null, null)), List.of()));

		assertEquals(1, circuitBreaker.getMetrics().getNumberOfFailedCalls());
		assertEquals(0, circuitBreaker.getMetrics().getNumberOfSuccessfulCalls());
		server.verify();
	}

	@Test
	void sendsToolCallAndToolResultMessagesWithMatchingToolCallId() {
		RestClient.Builder builder = RestClient.builder();
		builder.baseUrl("https://provider.example/v1");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		OpenAiChatCompletionAdapter adapter = new OpenAiChatCompletionAdapter(
				builder.build(),
				"test-model",
				"secret-token");
		server.expect(requestTo("https://provider.example/v1/chat/completions"))
				.andRespond(withSuccess("""
						{"choices":[{"message":{"content":null,"tool_calls":[{
						  "id":"call-1",
						  "type":"function",
						  "function":{"name":"get_vehicle_current_state","arguments":"{\\"vehicleId\\":\\"truck-17\\"}"}
						}]}}]}
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://provider.example/v1/chat/completions"))
				.andExpect(content().json("""
						{
						  "model": "test-model",
						  "messages": [
						    {"role":"user","content":"¿Dónde está truck-17?"},
						    {
						      "role":"assistant",
						      "tool_calls":[{
						        "id":"call-1",
						        "type":"function",
						        "function":{
						          "name":"get_vehicle_current_state",
						          "arguments":"{\\"vehicleId\\":\\"truck-17\\"}"
						        }
						      }]
						    },
						    {
						      "role":"tool",
						      "content":"{\\"vehicleId\\":\\"truck-17\\",\\"found\\":true}",
						      "tool_call_id":"call-1",
						      "name":"get_vehicle_current_state"
						    }
						  ]
						}
						"""))
				.andRespond(withSuccess("""
						{"choices":[{"message":{"content":"La última posición está disponible.","tool_calls":[]}}]}
						""", MediaType.APPLICATION_JSON));

		var initial = adapter.complete(
				List.of(new Message("user", "¿Dónde está truck-17?", null, null)),
				List.of(new ToolDefinition(
						"get_vehicle_current_state",
						"Reads current state.",
						Map.of(
								"type", "object",
								"properties", Map.of("vehicleId", Map.of("type", "string")),
								"required", List.of("vehicleId"),
								"additionalProperties", false))));
		var finalAnswer = adapter.complete(List.of(
				new Message("user", "¿Dónde está truck-17?", null, null),
				new Message("assistant", null, null, initial.toolCalls()),
				new Message(
						"tool",
						"{\"vehicleId\":\"truck-17\",\"found\":true}",
						"call-1",
						"get_vehicle_current_state",
						null)),
				List.of());

		assertEquals("La última posición está disponible.", finalAnswer.content());
		server.verify();
	}

	@Test
	void doesNotExposeApiKeyOrProviderBodyWhenProviderFails() {
		RestClient.Builder builder = RestClient.builder();
		builder.baseUrl("https://provider.example/v1");
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		OpenAiChatCompletionAdapter adapter = new OpenAiChatCompletionAdapter(
				builder.build(),
				"test-model",
				"secret-token");
		server.expect(requestTo("https://provider.example/v1/chat/completions"))
				.andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
						.contentType(MediaType.APPLICATION_JSON)
						.body("""
								{"error":{
								  "code":"invalid_request",
								  "type":"invalid_request_error",
								  "message":"Invalid tool schema; api_key=secret-token"
								}}
								"""));
		Logger logger = (Logger) LoggerFactory.getLogger(OpenAiChatCompletionAdapter.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		MDC.put("correlationId", "test-correlation-123");
		MDC.put("aiCallStage", "initial request");

		try {
			AiProviderUnavailableException exception = assertThrows(
					AiProviderUnavailableException.class,
					() -> adapter.complete(
							List.of(new Message("user", "consulta", null, null)),
							List.of()));

			assertFalse(exception.getMessage().contains("secret-token"));
			assertFalse(exception.getMessage().contains("provider detail"));
			assertTrue(exception.getCause() instanceof RestClientResponseException);
			assertEquals(400, ((RestClientResponseException) exception.getCause()).getStatusCode().value());
			assertTrue(appender.list.stream()
					.anyMatch(event -> event.getFormattedMessage().contains("AI provider request started")));
			assertTrue(appender.list.stream()
					.anyMatch(event -> event.getFormattedMessage().contains("AI provider request completed")
							&& event.getFormattedMessage().contains("httpStatus=400")));
			String loggedDiagnostic = appender.list.stream()
					.map(ILoggingEvent::getFormattedMessage)
					.reduce("", (left, right) -> left + right);
			assertTrue(loggedDiagnostic.contains("HTTP 400"));
			assertTrue(loggedDiagnostic.contains("test-correlation-123"));
			assertTrue(loggedDiagnostic.contains("initial request"));
			assertTrue(loggedDiagnostic.contains("durationMs="));
			assertTrue(loggedDiagnostic.contains("provider_http_error"));
			assertTrue(loggedDiagnostic.contains("invalid_request"));
			assertTrue(loggedDiagnostic.contains("Invalid tool schema"));
			assertFalse(loggedDiagnostic.contains("secret-token"));
			assertFalse(loggedDiagnostic.contains("Authorization"));
			server.verify();
		} finally {
			MDC.clear();
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void reportsMissingApiKeyWithoutMakingAProviderRequest() {
		OpenAiChatCompletionAdapter adapter = new OpenAiChatCompletionAdapter(
				"https://provider.example/v1",
				"test-model",
				"",
				CircuitBreaker.ofDefaults("groq-test"));

		AiProviderUnavailableException exception = assertThrows(
				AiProviderUnavailableException.class,
				() -> adapter.complete(
						List.of(new Message("user", "consulta", null, null)),
						List.of()));

		assertEquals(
				"La integración de IA no está configurada. Configure GROQ_API_KEY.",
				exception.getMessage());
	}

	private CircuitBreaker testCircuitBreaker(Duration openWait) {
		return configuredCircuitBreaker(openWait);
	}

	private CircuitBreaker configuredCircuitBreaker(Duration openWait) {
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(10)
				.minimumNumberOfCalls(5)
				.failureRateThreshold(50)
				.waitDurationInOpenState(openWait)
				.permittedNumberOfCallsInHalfOpenState(2)
				.automaticTransitionFromOpenToHalfOpenEnabled(false)
				.recordException(exception -> exception instanceof GroqTransientFailureException)
				.ignoreException(exception -> !(exception instanceof GroqTransientFailureException))
				.build();
		return CircuitBreaker.of("groq-test", config);
	}
}
