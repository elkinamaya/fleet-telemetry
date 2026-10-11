package com.fleet.telemetry.infrastructure.adapter.in.web;

import java.util.List;

public record AiChatResponse(String answer, List<String> toolsUsed) {
}
