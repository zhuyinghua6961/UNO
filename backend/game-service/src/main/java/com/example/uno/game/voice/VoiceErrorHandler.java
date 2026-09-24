package com.example.uno.game.voice;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = VoiceController.class)
class VoiceErrorHandler {
    @ExceptionHandler(VoiceFailure.class)
    ResponseEntity<Map<String, Object>> voice(VoiceFailure failure, HttpServletResponse response) {
        return result(failure.status(), failure.code(), failure.getMessage(), response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> input(HttpServletResponse response) {
        VoiceFailure failure = VoiceFailure.invalid();
        return result(failure.status(), failure.code(), failure.getMessage(), response);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> database(HttpServletResponse response) {
        VoiceFailure failure = VoiceFailure.unavailable();
        return result(failure.status(), failure.code(), failure.getMessage(), response);
    }

    private ResponseEntity<Map<String, Object>> result(int status, String code, String message, HttpServletResponse response) {
        String requestId = response.getHeader("X-Request-Id");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            response.setHeader("X-Request-Id", requestId);
        }
        response.setHeader("Cache-Control", "no-store");
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "requestId", requestId));
    }
}
