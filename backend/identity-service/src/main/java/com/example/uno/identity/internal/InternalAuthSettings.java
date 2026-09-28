package com.example.uno.identity.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("uno.internal-auth")
public record InternalAuthSettings(boolean enabled, String gameServiceKey, boolean allowInsecureHttp, int invalidRequestsPerWindow) {
    public InternalAuthSettings {
        if (invalidRequestsPerWindow < 1) throw new IllegalArgumentException("Invalid internal request limit must be positive");
        if (enabled && (gameServiceKey == null || !gameServiceKey.matches("[0-9a-fA-F]{64}"))) {
            throw new IllegalArgumentException("IDENTITY_GAME_SERVICE_KEY must be a dedicated 32-byte hex key");
        }
    }

    @Override public String toString() { return "InternalAuthSettings[enabled=" + enabled + ", secrets=redacted]"; }
}
