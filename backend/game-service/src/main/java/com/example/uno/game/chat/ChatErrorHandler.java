package com.example.uno.game.chat;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ChatController.class)
class ChatErrorHandler {
    @ExceptionHandler(ChatFailure.class)
    ResponseEntity<Map<String, Object>> chat(ChatFailure failure, HttpServletResponse response) {
        return result(failure.status(), failure.code(), failure.getMessage(), response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> input(HttpServletResponse response) {
        return result(400, "INVALID_CHAT_INPUT", "消息内容无效", response);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> database(HttpServletResponse response) {
        return result(503, "CHAT_UNAVAILABLE", "消息服务暂不可用，请稍后重试", response);
    }

    private ResponseEntity<Map<String, Object>> result(int status, String code, String message,
            HttpServletResponse response) {
        String requestId = response.getHeader("X-Request-Id");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            response.setHeader("X-Request-Id", requestId);
        }
        response.setHeader("Cache-Control", "no-store");
        return ResponseEntity.status(status).body(Map.of("code", code, "message", message, "requestId", requestId));
    }
}
