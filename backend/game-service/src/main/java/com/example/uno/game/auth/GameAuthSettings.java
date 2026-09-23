package com.example.uno.game.auth;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("uno.auth")
public record GameAuthSettings(boolean enabled, boolean secureCookies, List<String> allowedOrigins,
        URI identityBaseUrl, String serviceKey, boolean allowInsecureHttp, int timeoutMillis) {
    public GameAuthSettings {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().filter(value -> !value.isBlank()).toList();
        if (timeoutMillis < 100 || timeoutMillis > 10000) throw new IllegalArgumentException("Auth timeout must be between 100 and 10000 milliseconds");
        if (enabled) {
            if (serviceKey == null || !serviceKey.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("IDENTITY_GAME_SERVICE_KEY must be a dedicated 32-byte hex key");
            if (!isOrigin(identityBaseUrl)) throw new IllegalArgumentException("Identity URL must be an exact origin without credentials or parameters");
            boolean developmentHttp = "http".equals(identityBaseUrl.getScheme()) && allowInsecureHttp
                    && (loopback(identityBaseUrl) || "identity-service".equals(identityBaseUrl.getHost()));
            if (!"https".equals(identityBaseUrl.getScheme()) && !developmentHttp) throw new IllegalArgumentException("Identity requires HTTPS, except explicit local development");
            if (allowedOrigins.isEmpty()) throw new IllegalArgumentException("AUTH_ALLOWED_ORIGINS is required");
            for (String value : allowedOrigins) {
                URI origin = URI.create(value);
                if (!isOrigin(origin) || (!"https".equals(origin.getScheme()) && !("http".equals(origin.getScheme()) && loopback(origin)))
                        || (secureCookies && !"https".equals(origin.getScheme())) || (!secureCookies && !loopback(origin))) {
                    throw new IllegalArgumentException("Web origins require HTTPS or explicit loopback development mode");
                }
            }
        }
    }

    private static boolean isOrigin(URI uri) {
        return uri != null && uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && uri.getRawPath().isEmpty();
    }

    private static boolean loopback(URI uri) {
        return uri.getHost() != null && List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
    }

    public String sessionCookie() { return secureCookies ? "__Host-UNO-SESSION" : "UNO_SESSION_DEV"; }
    public String csrfCookie() { return secureCookies ? "__Host-UNO-CSRF" : "UNO_CSRF_DEV"; }
    @Override public String toString() { return "GameAuthSettings[enabled=" + enabled + ", secrets=redacted]"; }
}
