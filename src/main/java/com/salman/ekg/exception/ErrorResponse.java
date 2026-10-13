package com.salman.ekg.exception;

import java.time.Instant;
import java.util.List;

/** The single error shape returned by every endpoint. */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {}
}
