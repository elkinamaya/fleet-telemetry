package com.fleet.telemetry.infrastructure.adapter.in.web;

import java.util.List;

public record TelemetryBatchResponse(List<TelemetryBatchItemResponse> results) {
}
