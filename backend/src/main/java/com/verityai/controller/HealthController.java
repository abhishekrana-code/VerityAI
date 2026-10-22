package com.verityai.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/actuator/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthEndpoint healthEndpoint;

    @GetMapping("/liveness")
    public ResponseEntity<Map<String, Object>> liveness() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        var health = healthEndpoint.health();
        boolean ready = health.getStatus().equals(Status.UP);
        return ResponseEntity.status(ready ? 200 : 503).body(Map.of(
            "status", ready ? "UP" : "DOWN"
        ));
    }
}