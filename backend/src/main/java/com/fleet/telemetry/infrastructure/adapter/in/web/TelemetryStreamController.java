package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.infrastructure.adapter.out.sse.TelemetrySseBroadcaster;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryStreamController {

	private final TelemetrySseBroadcaster broadcaster;

	public TelemetryStreamController(TelemetrySseBroadcaster broadcaster) {
		this.broadcaster = broadcaster;
	}

	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public ResponseEntity<SseEmitter> stream() {
		return ResponseEntity.ok()
				.contentType(MediaType.TEXT_EVENT_STREAM)
				.body(broadcaster.subscribe());
	}
}
