package com.example.uno.identity.internal;

import com.example.uno.identity.auth.ApiErrors;
import com.example.uno.identity.auth.AuthFailure;
import com.example.uno.identity.auth.AuthRateLimiter;
import com.example.uno.identity.auth.RequestBodies;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class InternalServiceFilter extends OncePerRequestFilter {
    private final InternalAuthSettings settings;
    private final AuthRateLimiter limiter;
    private final byte[] expected;

    public InternalServiceFilter(InternalAuthSettings settings, AuthRateLimiter limiter) {
        this.settings = settings;
        this.limiter = limiter;
        expected = ("Basic " + Base64.getEncoder().encodeToString(("game-service:" + settings.gameServiceKey())
                .getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Request-Id", ApiErrors.requestId(request));
        try {
            List<String> credentials = Collections.list(request.getHeaders("Authorization"));
            if (!settings.enabled() || credentials.size() != 1 || !MessageDigest.isEqual(expected,
                    credentials.get(0).getBytes(StandardCharsets.US_ASCII))) {
                throw new AuthFailure(401, "SERVICE_UNAUTHORIZED", "服务凭证无效");
            }
            if (request.getHeader("Origin") != null || request.getHeader("Cookie") != null
                    || request.getHeader("Sec-Fetch-Site") != null || (!request.isSecure() && !settings.allowInsecureHttp())) {
                throw new AuthFailure(403, "INTERNAL_REQUEST_REJECTED", "服务间请求不允许使用该传输方式");
            }
            limiter.acquire("internal", "game-service", settings.requestsPerWindow());
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken("game-service", null,
                    List.of(new SimpleGrantedAuthority("SESSION_INTROSPECT"))));
            SecurityContextHolder.setContext(context);
            chain.doFilter(RequestBodies.bounded(request, 8192), response);
        } catch (AuthFailure failure) {
            ApiErrors.send(request, response, failure);
        } catch (DataAccessException exception) {
            ApiErrors.send(request, response, AuthFailure.unavailable());
        }
    }
}
