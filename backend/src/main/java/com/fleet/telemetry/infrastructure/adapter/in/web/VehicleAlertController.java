package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.VehicleAlertMonitor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
public class VehicleAlertController {

	private final VehicleAlertMonitor vehicleAlertMonitor;

	public VehicleAlertController(VehicleAlertMonitor vehicleAlertMonitor) {
		this.vehicleAlertMonitor = vehicleAlertMonitor;
	}

	@GetMapping
	public List<VehicleAlertResponse> getActiveAlerts() {
		return vehicleAlertMonitor.getActiveAlerts().stream()
				.map(VehicleAlertResponse::from)
				.toList();
	}
}
