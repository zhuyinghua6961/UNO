package com.example.uno.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

public final class ApiErrors {
    private ApiErrors() { }

    public static String requestId(HttpServletRequest request) {
        Object current = request.getAttribute("uno.requestId");
        if (current == null) {
            current = UUID.randomUUID().toString();
            request.setAttribute("uno.requestId", current);
        }
        return current.toString();
    }

    public static void send(HttpServletRequest request, HttpServletResponse response, AuthFailure failure) throws IOException {
        response.setStatus(failure.status());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Request-Id", requestId(request));
        if (failure.status() == 429) response.setHeader("Retry-After", "900");
        response.getWriter().write("{\"code\":\"" + failure.code() + "\",\"message\":\"" + failure.getMessage()
                + "\",\"requestId\":\"" + requestId(request) + "\"}");
    }
}
