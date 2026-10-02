package com.seatreserve.web;

import org.springframework.http.HttpStatus;

/**
 * Base class for domain errors that map to a clean HTTP status + machine-readable code.
 * These are expected outcomes (declines), never 5xx.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
