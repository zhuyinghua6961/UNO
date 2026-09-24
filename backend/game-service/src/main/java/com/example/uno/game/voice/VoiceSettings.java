package com.example.uno.game.voice;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("uno.voice")
public record VoiceSettings(boolean enabled, URI serverUrl, URI publicUrl, String apiKey,
        String apiSecret, boolean allowInsecureHttp) {
    public VoiceSettings {
        if (enabled) {
            if (apiKey == null || apiKey.isBlank() || apiSecret == null || apiSecret.length() < 32)
                throw new IllegalArgumentException("LiveKit API credentials are required");
            if (!origin(serverUrl) || !("https".equals(serverUrl.getScheme())
                    || (allowInsecureHttp && "http".equals(serverUrl.getScheme()))))
                throw new IllegalArgumentException("LiveKit server URL must be an HTTPS origin or explicit HTTP development origin");
            if (!origin(publicUrl) || !("wss".equals(publicUrl.getScheme())
                    || (allowInsecureHttp && "ws".equals(publicUrl.getScheme()))))
                throw new IllegalArgumentException("LiveKit public URL must be a WSS origin or explicit WS development origin");
        }
    }

    private static boolean origin(URI uri) {
        return uri != null && uri.getHost() != null && uri.getRawUserInfo() == null
                && uri.getRawPath().isEmpty() && uri.getRawQuery() == null && uri.getRawFragment() == null;
    }

    @Override public String toString() { return "VoiceSettings[enabled=" + enabled + ", secrets=redacted]"; }
}
