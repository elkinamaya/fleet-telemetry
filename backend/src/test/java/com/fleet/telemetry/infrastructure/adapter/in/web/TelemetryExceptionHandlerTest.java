package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.AiProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryExceptionHandlerTest {

	@Test
	void doesNotWriteProblemDetailWhenAsyncResponseIsUnusable() throws Exception {
		MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TestSseController())
				.setControllerAdvice(new TelemetryExceptionHandler())
				.build();

		mockMvc.perform(get("/test/stream").accept(MediaType.TEXT_EVENT_STREAM))
				.andExpect(status().isOk())
				.andExpect(content().string(""));
	}

	@Test
	void keepsProviderDetailsOutOfProblemResponse() {
		TelemetryExceptionHandler handler = new TelemetryExceptionHandler();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/ai/chat");

		var response = handler.handleAiProviderUnavailable(
				new AiProviderUnavailableException("private provider response", new IllegalStateException("secret")),
				request);

		assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
		assertTrue(response.getBody().getDetail().contains("No fue posible"));
		assertFalse(response.getBody().getDetail().contains("private provider response"));
		assertFalse(response.getBody().getDetail().contains("secret"));
	}

	@RestController
	static class TestSseController {

		@GetMapping(value = "/test/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
		void stream() throws AsyncRequestNotUsableException {
			throw new AsyncRequestNotUsableException("response already closed");
		}
	}
}
