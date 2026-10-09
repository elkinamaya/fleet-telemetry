package com.fleet.telemetry.infrastructure.adapter.out.kafka;

public class TelemetryPublicationException extends RuntimeException {

	public TelemetryPublicationException(String message, Throwable cause) {
		super(message, cause);
	}
}
