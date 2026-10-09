package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.SubmitTelemetryEvent;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {

	private final SubmitTelemetryEvent submitTelemetryEvent;

	public TelemetryController(SubmitTelemetryEvent submitTelemetryEvent) {
		this.submitTelemetryEvent = submitTelemetryEvent;
	}

	@PostMapping
	public ResponseEntity<Void> submit(@Valid @RequestBody TelemetryRequest request) {
		submitTelemetryEvent.execute(request.toEvent());
		return ResponseEntity.accepted().build();
	}
}
