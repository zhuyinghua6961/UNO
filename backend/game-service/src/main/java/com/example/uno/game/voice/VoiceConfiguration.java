package com.example.uno.game.voice;

import com.example.uno.game.auth.HttpSessionVerifier;
import io.livekit.server.RoomServiceClient;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VoiceSettings.class)
class VoiceConfiguration {
    @Bean
    VoiceSessionStatus voiceSessionStatus(HttpSessionVerifier verifier) {
        return verifier::activeSessionIds;
    }

    @Bean
    VoiceMedia voiceMedia(VoiceSettings settings) {
        if (!settings.enabled()) return new VoiceMedia() {
            @Override public void ensureRoom(String roomName) { throw VoiceFailure.unavailable(); }
            @Override public void deleteRoom(String roomName) { }
            @Override public java.util.List<VoiceMedia.Participant> participants(String roomName) { return java.util.List.of(); }
        };
        RoomServiceClient client = RoomServiceClient.create(settings.serverUrl().toString(),
                settings.apiKey(), settings.apiSecret(), false,
                builder -> builder.callTimeout(2, TimeUnit.SECONDS));
        return new LiveKitVoiceMedia(client);
    }
}
