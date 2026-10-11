package com.fleet.telemetry.application.port.out;

import com.fleet.telemetry.domain.model.TelemetryEvent;

public interface TelemetryEventStore {

	boolean saveIfAbsent(TelemetryEvent event);
}
