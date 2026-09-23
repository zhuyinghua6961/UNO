package com.example.uno.game.realtime;

import com.example.uno.game.auth.GameAuthSettings;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class GameWebSocketConfiguration implements WebSocketConfigurer {
    private final GameWebSocketHandler handler;
    private final GameWebSocketHandshake handshake;
    private final GameAuthSettings settings;

    GameWebSocketConfiguration(GameWebSocketHandler handler, GameWebSocketHandshake handshake,
            GameAuthSettings settings) {
        this.handler = handler;
        this.handshake = handshake;
        this.settings = settings;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/game").addInterceptors(handshake)
                .setAllowedOrigins(settings.allowedOrigins().toArray(String[]::new));
    }
}
