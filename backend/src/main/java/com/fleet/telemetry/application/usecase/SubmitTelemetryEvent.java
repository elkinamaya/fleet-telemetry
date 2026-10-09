package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.TelemetryEventPublisher;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.springframework.stereotype.Service;

@Service
public class SubmitTelemetryEvent {

	private final TelemetryEventPublisher publisher;

	public SubmitTelemetryEvent(TelemetryEventPublisher publisher) {
		this.publisher = publisher;
	}

	public void execute(TelemetryEvent event) {
		publisher.publish(event);
	}
}
