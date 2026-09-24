package com.example.uno.game.voice;

import io.livekit.server.RoomServiceClient;
import java.io.IOException;
import retrofit2.Response;

final class LiveKitVoiceMedia implements VoiceMedia {
    private final RoomServiceClient client;

    LiveKitVoiceMedia(RoomServiceClient client) { this.client = client; }

    @Override public void ensureRoom(String roomName) {
        try {
            Response<?> response = client.createRoom(roomName, 300, 2).execute();
            if (!response.isSuccessful()) {
                if (response.errorBody() != null) response.errorBody().close();
                if (response.code() != 409) throw VoiceFailure.unavailable();
            }
        } catch (IOException failure) {
            throw VoiceFailure.unavailable();
        }
    }

    @Override public void deleteRoom(String roomName) {
        try {
            Response<?> response = client.deleteRoom(roomName).execute();
            if (!response.isSuccessful()) {
                if (response.errorBody() != null) response.errorBody().close();
                if (response.code() != 404) throw VoiceFailure.unavailable();
            }
        } catch (IOException failure) {
            throw VoiceFailure.unavailable();
        }
    }
}
