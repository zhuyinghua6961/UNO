package com.example.uno.identity.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthHttpFilter extends OncePerRequestFilter {
    private final AuthSettings settings;
    private final AuthService auth;
    private final AuthRateLimiter limiter;

    public AuthHttpFilter(AuthSettings settings, AuthService auth, AuthRateLimiter limiter) {
        this.settings = settings;
        this.auth = auth;
        this.limiter = limiter;
    }

    public static boolean isNativeRequest(HttpServletRequest request) {
        return "APP".equals(request.getHeader("X-UNO-Client")) && request.getHeader("Origin") == null
                && request.getHeader("Sec-Fetch-Site") == null && request.getHeader("Cookie") == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Request-Id", ApiErrors.requestId(request));
        response.setHeader("Cache-Control", "no-store");
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || path.equals("/api/auth/status")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            String marker = request.getHeader("X-UNO-Client");
            boolean nativeRequest = isNativeRequest(request);
            boolean mutation = !List.of("GET", "HEAD", "OPTIONS").contains(request.getMethod());
            if (marker != null && !nativeRequest) throw forbidden();
            String origin = request.getHeader("Origin");
            if (!nativeRequest && ((origin != null && !settings.allowedOrigins().contains(origin)) || (mutation && origin == null))) throw forbidden();
            boolean protectedPath = path.startsWith("/api/users/") || path.equals("/api/auth/logout");
            if (!settings.enabled()) {
                if (protectedPath) throw AuthFailure.invalidCredentials();
                throw AuthFailure.unavailable();
            }
            // Gameplay fetches a fresh CSRF token for each Web command. Keep the
            // shared IP abuse budget for authentication operations only.
            if (path.startsWith("/api/auth/") && !path.equals("/api/auth/csrf")) {
                limiter.acquire("ip", request.getRemoteAddr(), settings.ipLimit());
            }
            HttpServletRequest effective = request;
            if (mutation) {
                effective = RequestBodies.bounded(request, 16384);
            }
            if (protectedPath) {
                String token;
                if (nativeRequest) {
                    String header = request.getHeader("Authorization");
                    if (header == null || !header.startsWith("Bearer ")) throw AuthFailure.invalidCredentials();
                    token = header.substring(7);
                } else {
                    if (request.getHeader("Authorization") != null) throw forbidden();
                    List<Cookie> cookies = request.getCookies() == null ? List.of() : Arrays.stream(request.getCookies())
                            .filter(cookie -> settings.sessionCookie().equals(cookie.getName())).toList();
                    if (cookies.size() != 1) throw AuthFailure.invalidCredentials();
                    token = cookies.get(0).getValue();
                }
                SessionIdentity identity = auth.authenticate(token, nativeRequest ? "APP" : "WEB")
                        .orElseThrow(AuthFailure::invalidCredentials);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(identity, null, List.of(new SimpleGrantedAuthority("USER"))));
                SecurityContextHolder.setContext(context);
            }
            chain.doFilter(effective, response);
        } catch (AuthFailure failure) {
            ApiErrors.send(request, response, failure);
        } catch (DataAccessException exception) {
            ApiErrors.send(request, response, AuthFailure.unavailable());
        }
    }

    private static AuthFailure forbidden() { return new AuthFailure(403, "REQUEST_NOT_ALLOWED", "请求来源或客户端凭证方式不允许"); }
}
