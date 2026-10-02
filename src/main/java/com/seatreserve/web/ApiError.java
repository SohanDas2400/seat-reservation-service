package com.seatreserve.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Uniform JSON error body. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String code, String message, String request_id) {
}
