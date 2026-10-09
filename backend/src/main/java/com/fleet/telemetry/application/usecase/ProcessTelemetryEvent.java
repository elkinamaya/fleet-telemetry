package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.TelemetryEventStore;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProcessTelemetryEvent {

	private final TelemetryEventStore eventStore;

	public ProcessTelemetryEvent(TelemetryEventStore eventStore) {
		this.eventStore = eventStore;
	}

	@Transactional
	public void execute(TelemetryEvent event) {
		eventStore.saveIfAbsent(event);
	}
}
