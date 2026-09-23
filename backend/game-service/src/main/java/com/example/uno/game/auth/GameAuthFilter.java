package com.example.uno.game.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class GameAuthFilter extends OncePerRequestFilter {
    private final GameAuthSettings settings;
    private final HttpSessionVerifier verifier;

    public GameAuthFilter(GameAuthSettings settings, HttpSessionVerifier verifier) {
        this.settings = settings;
        this.verifier = verifier;
    }

    public static boolean isNativeRequest(HttpServletRequest request) {
        return "APP".equals(request.getHeader("X-UNO-Client")) && request.getHeader("Origin") == null
                && request.getHeader("Sec-Fetch-Site") == null && request.getHeader("Cookie") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Request-Id", UUID.randomUUID().toString());
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || path.equals("/api/system/bootstrap")) { chain.doFilter(request, response); return; }
        response.setHeader("Cache-Control", "no-store");
        try {
            boolean nativeRequest = isNativeRequest(request);
            if (Collections.list(request.getHeaders("X-UNO-Client")).size() > 1
                    || (request.getHeader("X-UNO-Client") != null && !nativeRequest)) throw GameAuthFailure.forbidden();
            String origin = request.getHeader("Origin");
            boolean mutation = !List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
            if (!nativeRequest && ((origin != null && !settings.allowedOrigins().contains(origin)) || (mutation && origin == null))) throw GameAuthFailure.forbidden();
            String token;
            if (nativeRequest) {
                List<String> values = Collections.list(request.getHeaders("Authorization"));
                if (values.size() != 1 || !values.get(0).startsWith("Bearer ")) throw GameAuthFailure.unauthorized();
                token = values.get(0).substring(7);
            } else {
                if (request.getHeader("Authorization") != null) throw GameAuthFailure.forbidden();
                List<Cookie> cookies = request.getCookies() == null ? List.of() : Arrays.stream(request.getCookies())
                        .filter(cookie -> settings.sessionCookie().equals(cookie.getName())).toList();
                if (cookies.size() != 1) throw GameAuthFailure.unauthorized();
                token = cookies.get(0).getValue();
            }
            GameIdentity identity = verifier.verify(token, nativeRequest ? "APP" : "WEB").orElseThrow(GameAuthFailure::unauthorized);
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of(new SimpleGrantedAuthority("PLAYER"))));
            SecurityContextHolder.setContext(context);
            chain.doFilter(request, response);
        } catch (GameAuthFailure failure) {
            sendError(response, failure);
        }
    }

    public static void sendError(HttpServletResponse response, GameAuthFailure failure) throws IOException {
        String requestId = response.getHeader("X-Request-Id");
        if (requestId == null) { requestId = UUID.randomUUID().toString(); response.setHeader("X-Request-Id", requestId); }
        response.setStatus(failure.status());
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"code\":\"" + failure.code() + "\",\"message\":\"" + failure.getMessage()
                + "\",\"requestId\":\"" + requestId + "\"}");
    }
}
