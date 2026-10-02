package com.seatreserve.model;

import java.time.Instant;
import java.util.List;

public record Reservation(
        String id,
        String showId,
        String userId,
        ReservationStatus status,
        long amountPaise,
        String seatsCsv,
        String idempotencyKey,
        String requestFingerprint,
        Instant createdAt,
        Instant expiresAt
) {
    public List<String> seats() {
        if (seatsCsv == null || seatsCsv.isBlank()) {
            return List.of();
        }
        return List.of(seatsCsv.split(","));
    }
}
