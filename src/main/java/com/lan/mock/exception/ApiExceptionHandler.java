package com.lan.mock.exception;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidRequest(MethodArgumentNotValidException exception) {
        var error = exception.getBindingResult().getFieldError();
        String param = error == null ? null : error.getField();
        String message = error == null ? "Invalid request" : param + ": " + error.getDefaultMessage();
        return badRequest(message, param);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> invalidSettings(ConstraintViolationException exception) {
        var violation = exception.getConstraintViolations().iterator().next();
        return badRequest(violation.getMessage(), violation.getPropertyPath().toString());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableRequest() {
        return badRequest("Request body must be valid JSON with the expected field types", null);
    }

    private ResponseEntity<Map<String, Object>> badRequest(String message, String param) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", "invalid_request_error");
        error.put("param", param);
        error.put("code", null);
        return ResponseEntity.badRequest().body(Map.of("error", error));
    }
}
