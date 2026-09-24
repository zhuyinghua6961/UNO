package com.example.uno.game.voice;

import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels.ParticipantInfo;
import java.io.IOException;
import java.util.List;
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

    @Override public List<Participant> participants(String roomName) {
        try {
            Response<List<ParticipantInfo>> response = client.listParticipants(roomName).execute();
            if (!response.isSuccessful()) {
                if (response.errorBody() != null) response.errorBody().close();
                if (response.code() == 404) return List.of();
                throw VoiceFailure.unavailable();
            }
            if (response.body() == null) throw VoiceFailure.unavailable();
            return response.body().stream().map(item -> new Participant(item.getIdentity(), item.getMetadata())).toList();
        } catch (IOException failure) {
            throw VoiceFailure.unavailable();
        }
    }
}
