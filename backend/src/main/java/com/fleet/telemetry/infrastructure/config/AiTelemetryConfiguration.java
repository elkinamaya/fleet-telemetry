package com.fleet.telemetry.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AiTelemetryConfiguration {

	@Bean
	Clock telemetryClock() {
		return Clock.systemUTC();
	}

	@Bean
	int telemetryHistoryMaximumResults(
			@Value("${app.ai.history.max-results:100}") int configuredMaximum) {
		return configuredMaximum;
	}
}
