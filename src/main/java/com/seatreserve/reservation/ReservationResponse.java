package com.seatreserve.reservation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReservationResponse(
        String reservation_id,
        String show_id,
        String user_id,
        List<String> seats,
        long amount_paise,
        String status,
        Instant expires_at
) {
}
