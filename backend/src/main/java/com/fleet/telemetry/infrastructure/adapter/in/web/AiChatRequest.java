package com.fleet.telemetry.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiChatRequest(
		@NotBlank @Size(max = 2000) String question) {
}
