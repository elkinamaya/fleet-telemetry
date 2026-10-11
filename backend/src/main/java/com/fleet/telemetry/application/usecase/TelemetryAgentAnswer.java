package com.fleet.telemetry.application.usecase;

import java.util.List;

public record TelemetryAgentAnswer(String answer, List<String> toolsUsed) {
}
