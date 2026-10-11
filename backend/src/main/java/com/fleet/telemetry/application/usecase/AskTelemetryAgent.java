package com.fleet.telemetry.application.usecase;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.Completion;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.Message;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.ToolCall;
import com.fleet.telemetry.application.port.out.AiChatCompletionClient.ToolDefinition;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AskTelemetryAgent {

	private static final Logger logger = LoggerFactory.getLogger(AskTelemetryAgent.class);
	private static final String VEHICLE_STATE_TOOL = "get_vehicle_current_state";
	private static final String COUNT_TOOL = "count_vehicle_reports";
	private static final String HISTORY_TOOL = "get_vehicle_telemetry_history";
	private static final String BOUNDARIES_TOOL = "get_vehicle_period_boundaries";
	private static final int MAX_TOOL_CALLS = 4;
	private static final ObjectMapper objectMapper = new ObjectMapper();
	private static final String SYSTEM_PROMPT = """
			Eres un asistente de Fleet Telemetry. Responde en español y utiliza exclusivamente
			la información que devuelvan las herramientas autorizadas. Nunca inventes cantidades,
			registros, coordenadas, identificadores, fechas ni estados.
			Usa get_vehicle_current_state para el último estado conocido; count_vehicle_reports
			para cantidades; get_vehicle_telemetry_history para listas de reportes; y
			get_vehicle_period_boundaries para la primera y última posición de un periodo.
			Para hoy y ayer usa period=today o period=yesterday. También se admiten
			last_hour, last_24_hours, last_7_days y explicit con fechas solicitadas por el usuario.
			Para una lista de las últimas posiciones, usa get_vehicle_telemetry_history con
			period=all, latest=true y un límite. Si falta el vehículo o el periodo requerido,
			pide una aclaración en vez de adivinarlo. La zona de calendario es America/Bogota;
			los timestamps devueltos son UTC. Los intervalos incluyen el inicio y excluyen el fin.
			""";
	private static final List<ToolDefinition> TOOL_DEFINITIONS = List.of(
			tool(VEHICLE_STATE_TOOL,
					"Obtiene la telemetría persistida más reciente de un vehículo.",
					properties(Map.of(
							"vehicleId", stringProperty("Identificador exacto del vehículo.")),
							List.of("vehicleId"))),
			tool(COUNT_TOOL,
					"Cuenta los eventos persistidos de un vehículo en un periodo temporal.",
					periodProperties(List.of("vehicleId", "period", "from", "to"))),
			tool(HISTORY_TOOL,
					"Devuelve una página acotada de eventos de telemetría de un vehículo.",
					properties(Map.of(
							"vehicleId", stringProperty("Identificador exacto del vehículo."),
							"period", periodProperty(),
							"from", nullableStringProperty("Inicio ISO-8601 si period=explicit."),
							"to", nullableStringProperty("Fin ISO-8601 si period=explicit."),
							"limit", Map.of("type", "integer", "minimum", 1, "maximum", 1000),
							"offset", Map.of("type", "integer", "minimum", 0, "maximum", 100000),
							"latest", Map.of("type", "boolean")),
							List.of("vehicleId", "period"))),
			tool(BOUNDARIES_TOOL,
					"Devuelve la primera y última telemetría de un vehículo dentro de un periodo.",
					periodProperties(List.of("vehicleId", "period", "from", "to"))));

	private final GetVehicleState getVehicleState;
	private final GetVehicleTelemetryCount getVehicleTelemetryCount;
	private final GetVehicleTelemetryHistory getVehicleTelemetryHistory;
	private final GetVehicleTelemetryPeriodBoundaries getVehicleTelemetryPeriodBoundaries;
	private final TelemetryPeriodResolver periodResolver;
	private final AiChatCompletionClient chatCompletionClient;

	public AskTelemetryAgent(
			GetVehicleState getVehicleState,
			GetVehicleTelemetryCount getVehicleTelemetryCount,
			GetVehicleTelemetryHistory getVehicleTelemetryHistory,
			GetVehicleTelemetryPeriodBoundaries getVehicleTelemetryPeriodBoundaries,
			TelemetryPeriodResolver periodResolver,
			AiChatCompletionClient chatCompletionClient) {
		this.getVehicleState = getVehicleState;
		this.getVehicleTelemetryCount = getVehicleTelemetryCount;
		this.getVehicleTelemetryHistory = getVehicleTelemetryHistory;
		this.getVehicleTelemetryPeriodBoundaries = getVehicleTelemetryPeriodBoundaries;
		this.periodResolver = periodResolver;
		this.chatCompletionClient = chatCompletionClient;
	}

	private String safeToolName(String value) {
		return value != null && value.matches("[A-Za-z0-9_]{1,80}") ? value : "invalid";
	}

	public TelemetryAgentAnswer ask(String question) {
		List<Message> messages = new ArrayList<>(List.of(
				new Message("system", SYSTEM_PROMPT, null, null),
				new Message("user", question, null, null)));
		Completion firstCompletion = completeAtStage(messages, TOOL_DEFINITIONS, "initial request");

		if (firstCompletion.toolCalls().isEmpty()) {
			String clarification = firstCompletion.content() == null || firstCompletion.content().isBlank()
					? "Indica el identificador del vehículo y el periodo que quieres consultar."
					: firstCompletion.content();
			return new TelemetryAgentAnswer(clarification, List.of());
		}
		if (firstCompletion.toolCalls().size() > MAX_TOOL_CALLS) {
			throw new AiProviderUnavailableException(
					"El proveedor solicitó más herramientas de las permitidas.");
		}

		List<String> toolsUsed = new ArrayList<>();
		messages.add(new Message("assistant", null, null, firstCompletion.toolCalls()));
		for (ToolCall toolCall : firstCompletion.toolCalls()) {
			if (toolCall.id().isBlank()) {
				throw new AiProviderUnavailableException("El proveedor devolvió una llamada de herramienta inválida.");
			}
			String result;
			try {
				result = executeTool(question, toolCall);
			} catch (MissingTelemetryPeriodException exception) {
				logger.warn(
						"AI tool requires clarification: correlationId={}, tool={}, exception={}",
						MDC.get("correlationId"),
						safeToolName(toolCall.name()),
						exception.getClass().getSimpleName());
				return new TelemetryAgentAnswer(
						"¿Qué periodo quieres consultar? Puedes indicar hoy, ayer, última hora, "
								+ "últimas 24 horas, últimos 7 días o un intervalo con fechas ISO-8601.",
						List.copyOf(toolsUsed));
			}
			messages.add(new Message("tool", result, toolCall.id(), toolCall.name(), null));
			toolsUsed.add(toolCall.name());
		}

		Completion finalCompletion = completeAtStage(messages, List.of(), "after tool execution");
		if (!finalCompletion.toolCalls().isEmpty() || finalCompletion.content() == null
				|| finalCompletion.content().isBlank()) {
			throw new AiProviderUnavailableException(
					"El proveedor no devolvió una respuesta final válida.");
		}
		return new TelemetryAgentAnswer(finalCompletion.content(), List.copyOf(toolsUsed));
	}

	private Completion completeAtStage(
			List<Message> messages,
			List<ToolDefinition> tools,
			String stage) {
		try {
			MDC.put("aiCallStage", stage);
			return chatCompletionClient.complete(messages, tools);
		} catch (AiProviderUnavailableException exception) {
			logger.warn(
					"AI provider call failed: correlationId={}, stage={}, exception={}",
					MDC.get("correlationId"),
					stage,
					exception.getClass().getSimpleName());
			throw exception;
		} finally {
			MDC.remove("aiCallStage");
		}
	}

	private String executeTool(String question, ToolCall toolCall) {
		String correlationId = MDC.get("correlationId");
		String toolName = safeToolName(toolCall.name());
		logger.info("AI tool selected: correlationId={}, tool={}", correlationId, toolName);
		try {
			JsonNode args = objectMapper.readTree(toolCall.arguments());
			String vehicleId = requiredVehicleId(args);
			Object result = switch (toolCall.name()) {
				case VEHICLE_STATE_TOOL -> state(vehicleId);
				case COUNT_TOOL -> count(question, vehicleId, args);
				case HISTORY_TOOL -> history(question, vehicleId, args);
				case BOUNDARIES_TOOL -> boundaries(question, vehicleId, args);
				default -> throw new AiProviderUnavailableException(
						"El proveedor solicitó una herramienta no autorizada.");
			};
			String serializedResult = objectMapper.writeValueAsString(result);
			logger.info(
					"AI tool completed: correlationId={}, tool={}, argumentsValidated=true, result=success",
					correlationId,
					toolName);
			return serializedResult;
		} catch (VehicleTelemetryNotFoundException exception) {
			logger.info(
					"AI tool completed: correlationId={}, tool={}, argumentsValidated=true, result=no_data",
					correlationId,
					toolName);
			return json(Map.of(
					"vehicleId", exception.getVehicleId(),
					"found", false,
					"message", "No hay telemetría registrada para el vehículo consultado."));
		} catch (AiProviderUnavailableException | AiTelemetryQueryException
				| MissingTelemetryPeriodException exception) {
			boolean argumentsValidated = exception instanceof AiTelemetryQueryException;
			logger.warn(
					"AI tool failed: correlationId={}, tool={}, argumentsValidated={}, outcome={}, exception={}",
					correlationId,
					toolName,
					argumentsValidated,
					exception instanceof MissingTelemetryPeriodException
							? "clarification_required"
							: exception instanceof AiTelemetryQueryException ? "query_failed" : "validation_failed",
					exception.getClass().getSimpleName());
			throw exception;
		} catch (JsonProcessingException | IllegalArgumentException exception) {
			logger.warn(
					"AI tool argument validation failed: correlationId={}, tool={}, argumentsValidated=false, exception={}",
					correlationId,
					toolName,
					exception.getClass().getSimpleName());
			throw new AiProviderUnavailableException(
					"Los argumentos de consulta del proveedor son inválidos o ambiguos.", exception);
		} catch (RuntimeException exception) {
			logger.error(
					"AI tool execution failed: correlationId={}, tool={}, argumentsValidated=true, exception={}",
					correlationId,
					toolName,
					exception.getClass().getSimpleName());
			throw new AiTelemetryQueryException(
					"No fue posible consultar la telemetría solicitada.", exception);
		}
	}

	private Map<String, Object> state(String vehicleId) {
		return eventData(getVehicleState.execute(vehicleId));
	}

	private Object count(String question, String vehicleId, JsonNode args) {
		TelemetryPeriod period = requestedPeriod(question, args, false);
		TelemetryTimeRange range = periodResolver.resolve(period, optionalText(args, "from"), optionalText(args, "to"));
		return getVehicleTelemetryCount.execute(vehicleId, range);
	}

	private Object history(String question, String vehicleId, JsonNode args) {
		TelemetryPeriod period = requestedPeriod(question, args, true);
		int limit = integerArgument(args, "limit", 10);
		int offset = integerArgument(args, "offset", 0);
		boolean latest = args.path("latest").asBoolean(false);
		if (period == TelemetryPeriod.ALL) {
			if (!latest) {
				throw new MissingTelemetryPeriodException();
			}
			return historyData(getVehicleTelemetryHistory.latest(vehicleId, limit, offset));
		}
		TelemetryTimeRange range = periodResolver.resolve(period, optionalText(args, "from"), optionalText(args, "to"));
		return historyData(getVehicleTelemetryHistory.execute(vehicleId, range, limit, offset, latest));
	}

	private Object boundaries(String question, String vehicleId, JsonNode args) {
		TelemetryPeriod period = requestedPeriod(question, args, false);
		TelemetryTimeRange range = periodResolver.resolve(period, optionalText(args, "from"), optionalText(args, "to"));
		return boundariesData(getVehicleTelemetryPeriodBoundaries.execute(vehicleId, range));
	}

	private Map<String, Object> historyData(VehicleTelemetryHistory history) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("vehicleId", history.vehicleId());
		result.put("fromInclusive", history.fromInclusive());
		result.put("toExclusive", history.toExclusive());
		result.put("limit", history.limit());
		result.put("offset", history.offset());
		result.put("hasMore", history.hasMore());
		result.put("events", history.events().stream().map(this::eventData).toList());
		return result;
	}

	private Map<String, Object> boundariesData(VehicleTelemetryPeriodBoundaries boundaries) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("vehicleId", boundaries.vehicleId());
		result.put("fromInclusive", boundaries.fromInclusive());
		result.put("toExclusive", boundaries.toExclusive());
		result.put("hasEvents", boundaries.hasEvents());
		result.put("first", boundaries.first() == null ? null : eventData(boundaries.first()));
		result.put("last", boundaries.last() == null ? null : eventData(boundaries.last()));
		return result;
	}

	private TelemetryPeriod requestedPeriod(String question, JsonNode args, boolean history) {
		TelemetryPeriod period = periodResolver.period(requiredText(args, "period"));
		String normalizedQuestion = question.toLowerCase(Locale.ROOT);
		boolean expressed = switch (period) {
			case TODAY -> normalizedQuestion.contains("hoy");
			case YESTERDAY -> normalizedQuestion.contains("ayer");
			case LAST_HOUR -> normalizedQuestion.contains("última hora")
					|| normalizedQuestion.contains("ultima hora");
			case LAST_24_HOURS -> normalizedQuestion.contains("24 horas");
			case LAST_7_DAYS -> normalizedQuestion.contains("7 días")
					|| normalizedQuestion.contains("7 dias")
					|| normalizedQuestion.contains("siete días")
					|| normalizedQuestion.contains("siete dias");
			case EXPLICIT -> normalizedQuestion.matches("(?s).*\\d{4}-\\d{2}-\\d{2}.*");
			case ALL -> history && args.path("latest").asBoolean(false)
					&& mentionsLatest(normalizedQuestion);
		};
		if (!expressed) {
			throw new MissingTelemetryPeriodException();
		}
		return period;
	}

	private boolean mentionsLatest(String question) {
		return question.contains("últim") || question.contains("ultim") || question.contains("last");
	}

	private String requiredVehicleId(JsonNode args) {
		JsonNode node = args == null ? null : args.get("vehicleId");
		if (args == null || !args.isObject() || node == null || !node.isTextual()) {
			throw new AiProviderUnavailableException(
					"El proveedor devolvió un identificador de vehículo inválido.");
		}
		String vehicleId = node.textValue();
		if (vehicleId.isBlank() || vehicleId.length() > 100
				|| vehicleId.indexOf('/') >= 0 || vehicleId.indexOf('\\') >= 0
				|| vehicleId.chars().anyMatch(Character::isISOControl)) {
			throw new AiProviderUnavailableException(
					"El proveedor devolvió un identificador de vehículo inválido.");
		}
		return vehicleId;
	}

	private String requiredText(JsonNode args, String field) {
		JsonNode node = args.get(field);
		if (node == null || !node.isTextual() || node.textValue().isBlank()) {
			throw new AiProviderUnavailableException("Falta un argumento requerido para la herramienta.");
		}
		return node.textValue();
	}

	private String optionalText(JsonNode args, String field) {
		JsonNode node = args.get(field);
		return node == null || node.isNull() ? null : node.isTextual() ? node.textValue()
				: invalidArgument(field);
	}

	private String invalidArgument(String field) {
		throw new AiProviderUnavailableException("El argumento " + field + " no es válido.");
	}

	private int integerArgument(JsonNode args, String field, int defaultValue) {
		JsonNode node = args.get(field);
		if (node == null || node.isNull()) {
			return defaultValue;
		}
		if (!node.canConvertToInt()) {
			throw new AiProviderUnavailableException("El argumento " + field + " no es válido.");
		}
		return node.intValue();
	}

	private Map<String, Object> eventData(TelemetryEvent event) {
		return Map.of(
				"eventId", event.eventId(),
				"vehicleId", event.vehicleId(),
				"latitude", event.latitude(),
				"longitude", event.longitude(),
				"timestamp", event.timestamp().toString());
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new AiProviderUnavailableException("No fue posible preparar el resultado de la herramienta.", exception);
		}
	}

	private static ToolDefinition tool(String name, String description, Map<String, Object> parameters) {
		return new ToolDefinition(name, description, parameters);
	}

	private static Map<String, Object> periodProperties(List<String> required) {
		return properties(Map.of(
				"vehicleId", stringProperty("Identificador exacto del vehículo."),
				"period", periodProperty(),
				"from", nullableStringProperty("Inicio ISO-8601 si period=explicit."),
				"to", nullableStringProperty("Fin ISO-8601 si period=explicit.")),
				required.stream().filter(field -> !field.equals("from") && !field.equals("to")).toList());
	}

	private static Map<String, Object> properties(
			Map<String, Object> properties,
			List<String> required) {
		return Map.of(
				"type", "object",
				"properties", properties,
				"required", required,
				"additionalProperties", false);
	}

	private static Map<String, Object> stringProperty(String description) {
		return Map.of("type", "string", "description", description);
	}

	private static Map<String, Object> nullableStringProperty(String description) {
		return Map.of("type", List.of("string", "null"), "description", description);
	}

	private static Map<String, Object> periodProperty() {
		return Map.of(
				"type", "string",
				"enum", List.of("today", "yesterday", "last_hour", "last_24_hours", "last_7_days", "explicit", "all"));
	}

	private static class MissingTelemetryPeriodException extends RuntimeException {
	}
}
