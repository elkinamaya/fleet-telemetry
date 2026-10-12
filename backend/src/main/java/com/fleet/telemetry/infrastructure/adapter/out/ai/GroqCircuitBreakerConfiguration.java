package com.fleet.telemetry.infrastructure.adapter.out.ai;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class GroqCircuitBreakerConfiguration {

	@Bean
	CircuitBreaker groqCircuitBreaker(
			@Value("${app.ai.groq.circuit-breaker.sliding-window-size:10}") int slidingWindowSize,
			@Value("${app.ai.groq.circuit-breaker.minimum-number-of-calls:5}") int minimumNumberOfCalls,
			@Value("${app.ai.groq.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
			@Value("${app.ai.groq.circuit-breaker.wait-duration-in-open-state-seconds:15}") long waitDurationInOpenStateSeconds,
			@Value("${app.ai.groq.circuit-breaker.permitted-number-of-calls-in-half-open-state:2}") int permittedNumberOfCallsInHalfOpenState,
			@Value("${app.ai.groq.circuit-breaker.automatic-transition-from-open-to-half-open-enabled:false}") boolean automaticTransitionFromOpenToHalfOpenEnabled) {
		CircuitBreakerConfig config = CircuitBreakerConfig.custom()
				.slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(slidingWindowSize)
				.minimumNumberOfCalls(minimumNumberOfCalls)
				.failureRateThreshold(failureRateThreshold)
				.waitDurationInOpenState(Duration.ofSeconds(waitDurationInOpenStateSeconds))
				.permittedNumberOfCallsInHalfOpenState(permittedNumberOfCallsInHalfOpenState)
				.automaticTransitionFromOpenToHalfOpenEnabled(automaticTransitionFromOpenToHalfOpenEnabled)
				.recordException(exception -> exception instanceof GroqTransientFailureException)
				.ignoreException(exception -> !(exception instanceof GroqTransientFailureException))
				.build();
		return CircuitBreaker.of("groq", config);
	}
}
