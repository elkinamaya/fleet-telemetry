package com.fleet.telemetry.application.port.out;

import java.util.List;
import java.util.Map;

public interface AiChatCompletionClient {

	Completion complete(List<Message> messages, List<ToolDefinition> tools);

	record Message(
			String role,
			String content,
			String toolCallId,
			String toolName,
			List<ToolCall> toolCalls) {

		public Message(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
			this(role, content, toolCallId, null, toolCalls);
		}
	}

	record ToolCall(String id, String name, String arguments) {
	}

	record ToolDefinition(String name, String description, Map<String, Object> parameters) {
	}

	record Completion(String content, List<ToolCall> toolCalls) {
	}
}
