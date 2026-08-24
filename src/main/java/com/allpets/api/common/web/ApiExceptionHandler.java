package com.allpets.api.common.web;

import jakarta.validation.ConstraintViolationException;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps framework exceptions to a small, stable JSON error shape. Returns field <em>names</em>
 * and violation messages only — never the submitted values (which may be PII — LLD §10).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new TreeMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fieldErrors.putIfAbsent(error.getField(),
                        error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage()));

        Map<String, Object> body = Map.of(
                "status", "invalid",
                "errors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Explicit (post-honeypot) validation in {@code ContactController} — same 400 shape as
     * the {@code @Valid} path: field names + violation messages, never submitted values.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolations(ConstraintViolationException ex) {
        Map<String, String> fieldErrors = new TreeMap<>();
        ex.getConstraintViolations().forEach(violation ->
                fieldErrors.putIfAbsent(violation.getPropertyPath().toString(),
                        violation.getMessage() == null ? "invalid" : violation.getMessage()));

        Map<String, Object> body = Map.of(
                "status", "invalid",
                "errors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /** Malformed/unreadable JSON body — same stable shape as a validation 400, no detail leaked. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "invalid",
                "errors", Map.of("body", "malformed")));
    }
}
