package com.example.uno.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthErrorHandler {
    @ExceptionHandler(AuthFailure.class)
    ResponseEntity<ErrorBody> auth(AuthFailure failure, HttpServletRequest request) {
        var response = ResponseEntity.status(failure.status()).header("Cache-Control", "no-store");
        if (failure.status() == 429) response.header("Retry-After", "900");
        return response.body(new ErrorBody(failure.code(), failure.getMessage(), ApiErrors.requestId(request)));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErrorBody> input(Exception exception, HttpServletRequest request) {
        return auth(new AuthFailure(400, "INVALID_INPUT", "请求内容格式或字段无效"), request);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ErrorBody> database(DataAccessException exception, HttpServletRequest request) {
        return auth(AuthFailure.unavailable(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorBody> unexpected(Exception exception, HttpServletRequest request) {
        return auth(new AuthFailure(500, "INTERNAL_ERROR", "请求暂时无法完成"), request);
    }

    public record ErrorBody(String code, String message, String requestId) { }
}
