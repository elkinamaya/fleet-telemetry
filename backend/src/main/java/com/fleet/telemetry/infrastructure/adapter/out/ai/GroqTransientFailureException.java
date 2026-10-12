package com.fleet.telemetry.infrastructure.adapter.out.ai;

final class GroqTransientFailureException extends RuntimeException {

	GroqTransientFailureException(String message, Throwable cause) {
		super(message, cause);
	}
}
