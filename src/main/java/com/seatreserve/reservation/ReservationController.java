package com.seatreserve.reservation;

import com.seatreserve.security.UserContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /**
     * Reserve seats. Identity is taken from the JWT (never the body). The idempotency key may be an
     * Idempotency-Key header or an idempotency_key body field (header wins).
     */
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable("id") String showId,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            @Valid @RequestBody ReserveRequest body) {
        String userId = UserContext.requireUserId();
        String key = (headerKey != null && !headerKey.isBlank()) ? headerKey : body.idempotency_key();
        ReservationService.ReserveResult result = reservationService.reserve(userId, showId, body.seats(), key);
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.body());
    }

    @PostMapping("/reservations/{id}/confirm")
    public ReservationResponse confirm(@PathVariable("id") String reservationId) {
        return reservationService.confirm(UserContext.requireUserId(), reservationId);
    }

    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(@PathVariable("id") String reservationId) {
        return reservationService.cancel(UserContext.requireUserId(), reservationId);
    }
}
