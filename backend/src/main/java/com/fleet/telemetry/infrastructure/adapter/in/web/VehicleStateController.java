package com.fleet.telemetry.infrastructure.adapter.in.web;

import com.fleet.telemetry.application.usecase.GetVehicleState;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vehicles")
@Validated
public class VehicleStateController {

	private final GetVehicleState getVehicleState;

	public VehicleStateController(GetVehicleState getVehicleState) {
		this.getVehicleState = getVehicleState;
	}

	@GetMapping("/{vehicleId}/state")
	public ResponseEntity<VehicleStateResponse> getState(
			@PathVariable @NotBlank @Size(max = 100) String vehicleId) {
		return ResponseEntity.ok(VehicleStateResponse.from(getVehicleState.execute(vehicleId)));
	}
}
