package com.seatreserve.web;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Prometheus exposition at a clean /metrics path (Actuator also exposes it at
 * /actuator/prometheus). Kept as an explicit endpoint so the metrics path never depends on
 * Actuator base-path configuration.
 */
@RestController
public class MetricsController {

    private final PrometheusMeterRegistry registry;

    public MetricsController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    public String metrics() {
        return registry.scrape();
    }
}
