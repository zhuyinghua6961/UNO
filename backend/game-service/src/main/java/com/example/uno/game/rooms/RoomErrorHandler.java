package com.example.uno.game.rooms;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RoomController.class)
class RoomErrorHandler {
    @ExceptionHandler(RoomFailure.class)
    ResponseEntity<Map<String, Object>> room(RoomFailure failure, HttpServletResponse response) {
        return result(failure.status(), failure.code(), failure.getMessage(), response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> input(HttpServletResponse response) {
        return result(400, "INVALID_ROOM_INPUT", "房间请求内容无效", response);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, Object>> conflict(HttpServletResponse response) {
        return result(409, "ROOM_CONFLICT", "房间状态已变化，请刷新后重试", response);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> database(HttpServletResponse response) {
        return result(503, "ROOM_UNAVAILABLE", "房间服务暂不可用，请稍后重试", response);
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
