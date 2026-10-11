package com.fleet.telemetry.infrastructure.adapter.out.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.Completion;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.Message;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.ToolCall;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.ToolDefinition;
import com.fleet.telemetry.application.usecase.AiProviderUnavailableException;
import com.fleet.telemetry.infrastructure.adapter.in.web.AiRequestCorrelationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiChatCompletionAdapter implements AiChatCompletionClient {

	private static final Logger logger = LoggerFactory.getLogger(OpenAiChatCompletionAdapter.class);
	private final RestClient restClient;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final String model;
	private final String apiKey;
	private final String providerHost;

	@Autowired
	public OpenAiChatCompletionAdapter(
			@Value("${app.ai.base-url:https://api.groq.com/openai/v1}") String baseUrl,
			@Value("${app.ai.model:openai/gpt-oss-20b}") String model,
			@Value("${GROQ_API_KEY:${OPENAI_API_KEY:}}") String apiKey) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(3));
		requestFactory.setReadTimeout(Duration.ofSeconds(20));
		this.restClient = RestClient.builder()
				.baseUrl(baseUrl)
				.requestFactory(requestFactory)
				.build();
		this.model = model;
		this.apiKey = apiKey;
		this.providerHost = URI.create(baseUrl).getHost();
		logger.info(
				"AI provider configured: host={}, model={}, apiKeyConfigured={}",
				providerHost,
				model,
				!apiKey.isBlank());
	}

	OpenAiChatCompletionAdapter(
			RestClient restClient,
			String model,
			String apiKey) {
		this.restClient = restClient;
		this.model = model;
		this.apiKey = apiKey;
		this.providerHost = "configured-provider";
	}

	@Override
	public Completion complete(List<Message> messages, List<ToolDefinition> tools) {
		if (apiKey.isBlank()) {
			throw new AiProviderUnavailableException(
					"La integración de IA no está configurada. Configure GROQ_API_KEY.");
		}

		long startedAt = System.nanoTime();
		String correlationId = MDC.get(AiRequestCorrelationFilter.CORRELATION_ID_MDC_KEY);
		String callStage = MDC.get("aiCallStage");
		String httpStatus = "unavailable";
		String outcome = "failed";
		logger.info(
				"AI provider request started: correlationId={}, provider={}, model={}, stage={}",
				correlationId,
				providerHost,
				model,
				callStage);
		try {
			String responseBody = restClient.post()
					.uri("/chat/completions")
					.contentType(MediaType.APPLICATION_JSON)
					.headers(headers -> headers.setBearerAuth(apiKey))
					.body(requestBody(messages, tools))
					.retrieve()
					.body(String.class);
			httpStatus = "200";
			JsonNode response = objectMapper.readTree(responseBody);
			Completion completion = parseCompletion(response);
			outcome = "success";
			return completion;
		} catch (RestClientResponseException exception) {
			outcome = "provider_http_error";
			httpStatus = Integer.toString(exception.getStatusCode().value());
			logProviderResponseFailure(exception, correlationId, callStage);
			throw new AiProviderUnavailableException(
					"No fue posible completar la solicitud al proveedor de IA.", exception);
		} catch (RestClientException exception) {
			outcome = "transport_error";
			logger.warn(
					"AI provider request failed: correlationId={}, provider={}, model={}, stage={}, exception={}",
					correlationId,
					providerHost,
					model,
					callStage,
					exception.getClass().getSimpleName());
			throw new AiProviderUnavailableException(
					"No fue posible completar la solicitud al proveedor de IA.", exception);
		} catch (JsonProcessingException exception) {
			outcome = "invalid_response";
			logger.warn(
					"AI provider returned an invalid response: correlationId={}, provider={}, model={}, stage={}, exception={}",
					correlationId,
					providerHost,
					model,
					callStage,
					exception.getClass().getSimpleName());
			throw new AiProviderUnavailableException(
					"El proveedor de IA devolvió una respuesta inválida.", exception);
		} finally {
			logger.info(
					"AI provider request completed: correlationId={}, provider={}, model={}, stage={}, httpStatus={}, durationMs={}, outcome={}",
					correlationId,
					providerHost,
					model,
					callStage,
					httpStatus,
					Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
					outcome);
		}
	}

	private void logProviderResponseFailure(
			RestClientResponseException exception,
			String correlationId,
			String callStage) {
		String responseBody = exception.getResponseBodyAsString();
		String providerCode = "unavailable";
		String providerType = "unavailable";
		String providerMessage = "unavailable";
		try {
			JsonNode error = objectMapper.readTree(responseBody).path("error");
			providerCode = error.path("code").asText("unavailable");
			providerType = error.path("type").asText("unavailable");
			providerMessage = error.path("message").asText("unavailable");
		} catch (JsonProcessingException ignored) {
			providerMessage = "non-JSON error response";
		}

		logger.warn(
				"AI provider returned HTTP {}: correlationId={}, provider={}, model={}, stage={}, code={}, type={}, message={}",
				exception.getStatusCode().value(),
				correlationId,
				providerHost,
				model,
				callStage,
				safeDiagnostic(providerCode),
				safeDiagnostic(providerType),
				safeDiagnostic(providerMessage));
	}

	private String safeDiagnostic(String value) {
		String sanitized = value.replaceAll("[\\r\\n\\t]", " ")
				.replace(apiKey, "[REDACTED]")
				.replaceAll("(?i)Bearer\\s+[^\\s,;]+", "Bearer [REDACTED]")
				.replaceAll(
						"(?i)(api[_-]?key|authorization|token|secret|password)(\\s*[:=]\\s*)[^\\s,;]+",
						"$1$2[REDACTED]");
		return sanitized.length() <= 500 ? sanitized : sanitized.substring(0, 500);
	}

	private Map<String, Object> requestBody(List<Message> messages, List<ToolDefinition> tools) {
		List<Map<String, Object>> serializedMessages = new ArrayList<>();
		for (Message message : messages) {
			Map<String, Object> serialized = new LinkedHashMap<>();
			serialized.put("role", message.role());
			if (message.content() != null) {
				serialized.put("content", message.content());
			}
			if (message.toolCallId() != null) {
				serialized.put("tool_call_id", message.toolCallId());
			}
			if (message.toolName() != null) {
				serialized.put("name", message.toolName());
			}
			if (message.toolCalls() != null) {
				serialized.put("tool_calls", message.toolCalls().stream()
						.map(this::serializeToolCall)
						.toList());
			}
			serializedMessages.add(serialized);
		}

		Map<String, Object> request = new LinkedHashMap<>();
		request.put("model", model);
		request.put("messages", serializedMessages);
		if (!tools.isEmpty()) {
			request.put("tools", tools.stream().map(this::serializeToolDefinition).toList());
			request.put("tool_choice", "auto");
		}
		return request;
	}

	private Map<String, Object> serializeToolDefinition(ToolDefinition tool) {
		return Map.of(
				"type", "function",
				"function", Map.of(
						"name", tool.name(),
						"description", tool.description(),
						"parameters", tool.parameters()));
	}

	private Map<String, Object> serializeToolCall(ToolCall toolCall) {
		return Map.of(
				"id", toolCall.id(),
				"type", "function",
				"function", Map.of(
						"name", toolCall.name(),
						"arguments", toolCall.arguments()));
	}

	private Completion parseCompletion(JsonNode response) throws JsonProcessingException {
		JsonNode choices = response == null ? null : response.get("choices");
		if (choices == null || !choices.isArray() || choices.isEmpty()) {
			throw new JsonProcessingException("Missing choices in provider response") {
			};
		}

		JsonNode message = choices.get(0).path("message");
		String content = message.path("content").isTextual()
				? message.path("content").textValue()
				: null;
		List<ToolCall> toolCalls = new ArrayList<>();
		JsonNode calls = message.path("tool_calls");
		if (calls.isArray()) {
			for (JsonNode call : calls) {
				toolCalls.add(new ToolCall(
						call.path("id").asText(""),
						call.path("function").path("name").asText(""),
						call.path("function").path("arguments").asText("")));
			}
		}
		return new Completion(content, List.copyOf(toolCalls));
	}
}
