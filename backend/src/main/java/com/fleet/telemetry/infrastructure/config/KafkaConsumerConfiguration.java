package com.fleet.telemetry.infrastructure.config;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfiguration {

	@Bean
	CommonErrorHandler telemetryConsumerErrorHandler() {
		DefaultErrorHandler errorHandler = new DefaultErrorHandler(
				(ConsumerRecord<?, ?> record, Exception exception) -> {
					throw new KafkaException(
							"Could not persist telemetry event; record will be retried", exception);
				},
				new FixedBackOff(1000L, 2L));
		errorHandler.setAckAfterHandle(false);
		return errorHandler;
	}
}
