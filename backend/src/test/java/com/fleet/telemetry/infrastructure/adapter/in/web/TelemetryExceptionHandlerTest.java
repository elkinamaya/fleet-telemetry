package com.fleet.telemetry.infrastructure.adapter.in.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

	@RestController
	static class TestSseController {

		@GetMapping(value = "/test/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
		void stream() throws AsyncRequestNotUsableException {
			throw new AsyncRequestNotUsableException("response already closed");
		}
	}
}
