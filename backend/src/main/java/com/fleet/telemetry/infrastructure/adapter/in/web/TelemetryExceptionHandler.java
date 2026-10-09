package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.infrastructure.adapter.out.kafka.TelemetryPublicationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TelemetryExceptionHandler {

	@ExceptionHandler(TelemetryPublicationException.class)
	public ResponseEntity<ProblemDetail> handlePublicationFailure(TelemetryPublicationException exception) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.SERVICE_UNAVAILABLE,
				"Telemetry event could not be confirmed by Kafka");
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
	}
}
