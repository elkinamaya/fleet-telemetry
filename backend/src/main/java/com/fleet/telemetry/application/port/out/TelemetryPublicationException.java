package com.fleet.telemetry.application.port.out;

public class TelemetryPublicationException extends RuntimeException {

	public TelemetryPublicationException(String message, Throwable cause) {
		super(message, cause);
	}
}
