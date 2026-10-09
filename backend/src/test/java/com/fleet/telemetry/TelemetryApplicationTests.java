package com.fleet.telemetry;

import com.fleet.telemetry.application.port.out.TelemetryEventStore;
import com.fleet.telemetry.application.port.out.VehicleTelemetryQuery;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
		"spring.kafka.listener.auto-startup=false"
})
class TelemetryApplicationTests {

	@MockitoBean
	private TelemetryEventStore telemetryEventStore;

	@MockitoBean
	private VehicleTelemetryQuery vehicleTelemetryQuery;

	@Test
	void contextLoads() {
	}

}
