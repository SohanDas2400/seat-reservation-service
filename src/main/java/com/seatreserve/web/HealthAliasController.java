package com.seatreserve.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Friendly aliases alongside the Actuator probes.
 *   /healthz - liveness: the process is serving requests.
 *   /readyz  - readiness: actually checks the DB dependency and FAILS CLOSED (503) when it's down.
 * (Actuator also exposes /health/liveness and /health/readiness with the same semantics.)
 */
@RestController
public class HealthAliasController {

    private final JdbcTemplate jdbc;

    public HealthAliasController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/healthz")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping("/readyz")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "error", "database unreachable"));
        }
    }
}
