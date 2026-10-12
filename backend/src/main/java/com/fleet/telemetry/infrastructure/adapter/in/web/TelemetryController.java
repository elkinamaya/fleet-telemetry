package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.SubmitTelemetryEvent;
import com.fleet.telemetry.application.usecase.SubmitTelemetryBatch;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

	private final SubmitTelemetryEvent submitTelemetryEvent;
	private final SubmitTelemetryBatch submitTelemetryBatch;

	public TelemetryController(
			SubmitTelemetryEvent submitTelemetryEvent,
			SubmitTelemetryBatch submitTelemetryBatch) {
		this.submitTelemetryEvent = submitTelemetryEvent;
		this.submitTelemetryBatch = submitTelemetryBatch;
	}

	@PostMapping
	public ResponseEntity<Void> submit(@Valid @RequestBody TelemetryRequest request) {
		submitTelemetryEvent.execute(request.toEvent());
		return ResponseEntity.accepted().build();
	}

	@PostMapping("/batch")
	public ResponseEntity<TelemetryBatchResponse> submitBatch(
			@Valid @RequestBody TelemetryBatchRequest request) {
		var publicationResults = submitTelemetryBatch.execute(request.events().stream()
				.map(TelemetryRequest::toEvent)
				.toList());
		TelemetryBatchResponse response = new TelemetryBatchResponse(publicationResults.stream()
				.map(result -> new TelemetryBatchItemResponse(
						result.eventId(),
						result.status().name(),
						result.status() == SubmitTelemetryBatch.Status.PUBLICATION_UNCONFIRMED
								? "TELEMETRY_PUBLICATION_UNAVAILABLE"
								: null))
				.toList());
		HttpStatus status = response.results().stream()
				.allMatch(result -> result.status().equals(SubmitTelemetryBatch.Status.PUBLISHED.name()))
						? HttpStatus.ACCEPTED
						: HttpStatus.MULTI_STATUS;
		return ResponseEntity.status(status).body(response);
	}
}
