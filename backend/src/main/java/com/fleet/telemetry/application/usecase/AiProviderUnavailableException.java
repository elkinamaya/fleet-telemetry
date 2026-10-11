package com.fleet.telemetry.application.usecase;

public class AiProviderUnavailableException extends RuntimeException {

	public AiProviderUnavailableException(String message) {
		super(message);
	}

	public AiProviderUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
