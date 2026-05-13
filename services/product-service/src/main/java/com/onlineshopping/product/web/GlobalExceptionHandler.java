package com.onlineshopping.product.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    /** Per-field validation error. */
    public record FieldErrorDto(String field, String message) {}

    /** Unified API error envelope. */
    public record ApiError(
            Instant timestamp,
            int status,
            String error,
            String message,           // null for validation errors (use 'errors' instead)
            List<FieldErrorDto> errors, // null for non-validation errors
            String path
    ) {}

    /** Bean Validation (@Valid) failures — clean up Spring's verbose default. */
    // SuppressWarnings safe: JSON API response (Content-Type: application/json),
    // browsers do not interpret JSON as HTML. Frontends must escape when rendering.
    @SuppressWarnings("CWE-79")
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest req) {

        List<FieldErrorDto> mapped = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> new FieldErrorDto(fieldError.getField(), fieldError.getDefaultMessage()))
                .toList();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(Instant.now(), HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        null, mapped, req.getRequestURI()));
    }

    /** ResponseStatusException thrown from services (e.g. 409 Conflict). */
    @SuppressWarnings("CWE-79")
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(
            ResponseStatusException ex,
            HttpServletRequest req) {

        return ResponseEntity.status(ex.getStatusCode())
                .body(new ApiError(Instant.now(), ex.getStatusCode().value(),
                        HttpStatus.valueOf(ex.getStatusCode().value()).getReasonPhrase(), ex.getReason(),
                        null, req.getRequestURI()));
    }
}
