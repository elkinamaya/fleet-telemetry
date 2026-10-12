package com.fleet.telemetry.infrastructure.adapter.in.web;

import java.util.UUID;

public record TelemetryBatchItemResponse(
		UUID eventId,
		String status,
		String errorCode) {
}
