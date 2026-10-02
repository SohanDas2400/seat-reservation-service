package com.seatreserve.web;

import org.springframework.http.HttpStatus;

/**
 * A clean 409 decline: seat already taken, per-user limit reached, or idempotency-key reuse
 * with a different body. The {@code code} carries the exact reason.
 */
public class ConflictException extends ApiException {

    public static final String SEAT_TAKEN = "seat_taken";
    public static final String PER_USER_LIMIT = "per_user_limit";
    public static final String IDEMPOTENCY_CONFLICT = "idempotency_conflict";
    public static final String HOLD_EXPIRED = "hold_expired";
    public static final String NOT_CANCELLABLE = "not_cancellable";

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
