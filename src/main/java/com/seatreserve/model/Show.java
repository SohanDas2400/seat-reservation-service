package com.seatreserve.model;

import java.time.Instant;

public record Show(
        String id,
        String name,
        long pricePaise,
        int perUserLimit,
        Instant createdAt
) {
}
