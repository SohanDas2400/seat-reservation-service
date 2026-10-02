package com.seatreserve.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business metrics exposed at /metrics (Prometheus):
 *   reservations_confirmed_total                         - seats successfully secured (201)
 *   reservations_declined_total{reason="..."}            - clean declines by reason
 *   seats_available{show_id="..."}                       - gauge, registered per show (see ShowService)
 */
@Component
public class ReservationMetrics {

    private final MeterRegistry registry;
    private final Counter confirmed;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations_confirmed")
                .description("Reservations successfully secured")
                .register(registry);
    }

    public void confirmed() {
        confirmed.increment();
    }

    public void declined(String reason) {
        registry.counter("reservations_declined", "reason", reason).increment();
    }
}
