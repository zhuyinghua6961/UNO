package com.example.uno.identity.auth;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("uno.auth")
public record AuthSettings(boolean enabled, boolean secureCookies, List<String> allowedOrigins,
        String mailKey, String mailFrom, boolean mailDispatchEnabled, int ipLimit, int accountLimit) {
    public AuthSettings {
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream().filter(value -> !value.isBlank()).toList();
        if (ipLimit < 1 || accountLimit < 1) throw new IllegalArgumentException("Auth limits must be positive");
        if (enabled) {
            if (mailKey == null || !mailKey.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("AUTH_MAIL_KEY must be a dedicated 32-byte hex key");
            }
            if (allowedOrigins.isEmpty()) throw new IllegalArgumentException("AUTH_ALLOWED_ORIGINS is required");
            for (String origin : allowedOrigins) {
                URI uri = URI.create(origin);
                boolean https = "https".equals(uri.getScheme());
                boolean loopback = uri.getHost() != null && List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
                if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()
                        || (!https && !("http".equals(uri.getScheme()) && loopback))
                        || (secureCookies && !https) || (!secureCookies && !loopback)) {
                    throw new IllegalArgumentException("Auth origins require exact HTTPS origins, or explicit loopback development mode");
                }
            }
        }
    }

    public String sessionCookie() {
        return secureCookies ? "__Host-UNO-SESSION" : "UNO_SESSION_DEV";
    }

    public String csrfCookie() {
        return secureCookies ? "__Host-UNO-CSRF" : "UNO_CSRF_DEV";
    }

    @Override
    public String toString() {
        return "AuthSettings[enabled=" + enabled + ", secrets=redacted]";
    }
}
