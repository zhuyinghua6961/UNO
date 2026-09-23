package com.example.uno.game.realtime;

import com.example.uno.game.auth.GameAuthFailure;
import com.example.uno.game.auth.GameAuthSettings;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.auth.HttpSessionVerifier;
import jakarta.servlet.http.Cookie;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/** Web uses an allowed Origin and session cookie; native App uses its Bearer token. */
@Component
public final class GameWebSocketHandshake implements HandshakeInterceptor {
    public static final String AUTH_ATTRIBUTE = "uno.websocket.auth";
    private final GameAuthSettings settings;
    private final HttpSessionVerifier verifier;

    public GameWebSocketHandshake(GameAuthSettings settings, HttpSessionVerifier verifier) {
        this.settings = settings;
        this.verifier = verifier;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Map<String, Object> attributes) {
        if (!settings.enabled()) return reject(response, HttpStatus.SERVICE_UNAVAILABLE);
        if (request.getURI().getRawQuery() != null || !(request instanceof ServletServerHttpRequest servlet))
            return reject(response, HttpStatus.FORBIDDEN);
        HttpHeaders headers = request.getHeaders();
        List<String> clientValues = headers.getOrEmpty("X-UNO-Client");
        List<String> originValues = headers.getOrEmpty(HttpHeaders.ORIGIN);
        List<String> authValues = headers.getOrEmpty(HttpHeaders.AUTHORIZATION);
        List<String> cookieValues = headers.getOrEmpty(HttpHeaders.COOKIE);
        String token;
        String clientType;
        if (clientValues.size() == 1 && "APP".equals(clientValues.get(0))) {
            if (!originValues.isEmpty() || !cookieValues.isEmpty()
                    || !headers.getOrEmpty("Sec-Fetch-Site").isEmpty() || authValues.size() != 1
                    || !authValues.get(0).startsWith("Bearer ")) return reject(response, HttpStatus.FORBIDDEN);
            token = authValues.get(0).substring(7);
            clientType = "APP";
        } else {
            if (!clientValues.isEmpty() || !authValues.isEmpty() || originValues.size() != 1
                    || !settings.allowedOrigins().contains(originValues.get(0)) || cookieValues.size() != 1)
                return reject(response, HttpStatus.FORBIDDEN);
            Cookie[] cookies = servlet.getServletRequest().getCookies();
            List<Cookie> sessions = cookies == null ? List.of() : Arrays.stream(cookies)
                    .filter(cookie -> settings.sessionCookie().equals(cookie.getName())).toList();
            if (sessions.size() != 1) return reject(response, HttpStatus.UNAUTHORIZED);
            token = sessions.get(0).getValue();
            clientType = "WEB";
        }
        try {
            Optional<GameIdentity> verified = verifier.verify(token, clientType);
            if (verified.isEmpty()) return reject(response, HttpStatus.UNAUTHORIZED);
            attributes.put(AUTH_ATTRIBUTE, new SessionAuth(token, clientType, verified.get()));
            return true;
        } catch (GameAuthFailure failure) {
            return reject(response, HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) { }

    private boolean reject(ServerHttpResponse response, HttpStatus status) {
        response.setStatusCode(status);
        response.getHeaders().setCacheControl("no-store");
        return false;
    }

    public record SessionAuth(String token, String clientType, GameIdentity identity) {
        @Override public String toString() { return "SessionAuth[clientType=" + clientType + ", token=redacted]"; }
    }
}
