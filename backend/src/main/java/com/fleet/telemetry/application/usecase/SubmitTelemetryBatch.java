package com.fleet.telemetry.application.usecase;

import com.fleet.telemetry.application.port.out.TelemetryPublicationException;
import com.fleet.telemetry.domain.model.TelemetryEvent;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SubmitTelemetryBatch {

	private final SubmitTelemetryEvent submitTelemetryEvent;

	public SubmitTelemetryBatch(SubmitTelemetryEvent submitTelemetryEvent) {
		this.submitTelemetryEvent = submitTelemetryEvent;
	}

	public List<Result> execute(List<TelemetryEvent> events) {
		List<Result> results = new ArrayList<>(events.size());
		for (TelemetryEvent event : events) {
			try {
				submitTelemetryEvent.execute(event);
				results.add(new Result(event.eventId(), Status.PUBLISHED));
			} catch (TelemetryPublicationException exception) {
				results.add(new Result(event.eventId(), Status.PUBLICATION_UNCONFIRMED));
			}
		}
		return List.copyOf(results);
	}

	public record Result(UUID eventId, Status status) {
	}

	public enum Status {
		PUBLISHED,
		PUBLICATION_UNCONFIRMED
	}
}
