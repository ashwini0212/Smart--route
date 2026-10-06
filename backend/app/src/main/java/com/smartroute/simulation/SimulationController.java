package com.smartroute.simulation;

import com.smartroute.common.security.Access;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Says whether anything is being simulated. It exists so that nobody looking at a moving map has to guess,
 * and so a screenshot can be checked after the fact.
 */
@RestController
@RequestMapping("/api/simulation")
@Tag(name = "Simulation", description = "Whether driver movement or traffic is being simulated")
@EnableConfigurationProperties(SimulationProperties.class)
class SimulationController {

    private final SimulationProperties properties;
    private final ObjectProvider<DriverMovementSimulator> driverSimulator;
    private final ObjectProvider<TrafficSimulator> trafficSimulator;

    SimulationController(SimulationProperties properties, ObjectProvider<DriverMovementSimulator> driverSimulator,
                         ObjectProvider<TrafficSimulator> trafficSimulator) {
        this.properties = properties;
        this.driverSimulator = driverSimulator;
        this.trafficSimulator = trafficSimulator;
    }

    @GetMapping("/status")
    @PreAuthorize(Access.STAFF_OR_VIEWER)
    @Operation(summary = "Which simulators are running; positions from a running driver simulator are synthetic")
    Map<String, Object> status() {
        boolean driversRunning = driverSimulator.getIfAvailable() != null;
        boolean trafficRunning = trafficSimulator.getIfAvailable() != null;
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("driverMovement", driversRunning);
        status.put("traffic", trafficRunning);
        status.put("driverSpeedKph", driversRunning ? properties.speedKph() : null);
        status.put("tick", driversRunning ? properties.tick().toString() : null);
        status.put("trafficTick", trafficRunning ? properties.trafficTick().toString() : null);
        status.put("note", driversRunning || trafficRunning
                ? "[SIMULATION] some data in this system is generated, not observed"
                : "Nothing is being simulated");
        return status;
    }
}
