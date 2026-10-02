package com.seatreserve.reservation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record ReserveRequest(
        @NotEmpty List<@NotBlank String> seats,
        String idempotency_key
) {
}
