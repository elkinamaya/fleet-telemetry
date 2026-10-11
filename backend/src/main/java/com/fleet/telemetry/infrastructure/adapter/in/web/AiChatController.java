package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.AskTelemetryAgent;
import com.fleet.telemetry.application.usecase.TelemetryAgentAnswer;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiChatController {

	private final AskTelemetryAgent askTelemetryAgent;

	public AiChatController(AskTelemetryAgent askTelemetryAgent) {
		this.askTelemetryAgent = askTelemetryAgent;
	}

	@PostMapping("/chat")
	public ResponseEntity<AiChatResponse> chat(@Valid @RequestBody AiChatRequest request) {
		TelemetryAgentAnswer answer = askTelemetryAgent.ask(request.question());
		return ResponseEntity.ok(new AiChatResponse(answer.answer(), answer.toolsUsed()));
	}
}
