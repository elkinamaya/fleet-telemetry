package com.fleet.telemetry.application.usecase;

import java.time.Instant;

public record TelemetryTimeRange(Instant fromInclusive, Instant toExclusive) {

	public TelemetryTimeRange {
		if (fromInclusive == null || toExclusive == null || !fromInclusive.isBefore(toExclusive)) {
			throw new IllegalArgumentException("El inicio del intervalo debe ser anterior al final.");
		}
	}
}
