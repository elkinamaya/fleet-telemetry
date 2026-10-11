package com.fleet.telemetry.infrastructure.adapter.in.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiRequestCorrelationFilterTest {

	private final AiRequestCorrelationFilter filter = new AiRequestCorrelationFilter();

	@Test
	void assignsCorrelationIdForAiRequestAndClearsThreadContextAfterward() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = (servletRequest, servletResponse) -> assertNotNull(MDC.get("correlationId"));

		filter.doFilter(request, response, chain);

		String correlationId = response.getHeader(AiRequestCorrelationFilter.CORRELATION_ID_HEADER);
		assertNotNull(correlationId);
		assertEquals(36, correlationId.length());
		assertNull(MDC.get("correlationId"));
	}
}
