package com.fleet.telemetry.application.port.out;

import com.fleet.telemetry.domain.model.TelemetryEvent;

public interface TelemetryEventPublisher {

	void publish(TelemetryEvent event);
}
