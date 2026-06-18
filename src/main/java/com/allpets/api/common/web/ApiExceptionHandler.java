package com.allpets.api.common.web;

import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
}
