package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.infrastructure.adapter.out.sse.TelemetrySseBroadcaster;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

@WebMvcTest(TelemetryStreamController.class)
class TelemetryStreamControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TelemetrySseBroadcaster broadcaster;

	@Test
	void opensPersistentEventStream() throws Exception {
		when(broadcaster.subscribe()).thenReturn(new SseEmitter(0L));

		mockMvc.perform(get("/api/telemetry/stream").accept(MediaType.TEXT_EVENT_STREAM))
				.andExpect(request().asyncStarted())
				.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));

		verify(broadcaster).subscribe();
	}
}
