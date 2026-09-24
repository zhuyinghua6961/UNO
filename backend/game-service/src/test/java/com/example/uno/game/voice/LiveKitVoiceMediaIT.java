package com.example.uno.game.voice;

import static org.junit.jupiter.api.Assertions.*;

import io.livekit.server.RoomServiceClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Run against the optional local LiveKit container; credentials never appear in test output. */
@EnabledIfEnvironmentVariable(named = "UNO_LIVEKIT_IT", matches = "true")
class LiveKitVoiceMediaIT {
    @Test
    void createsTwoSeatRoomAndDeletesIt() throws Exception {
        RoomServiceClient client = RoomServiceClient.create("http://127.0.0.1:7880",
                System.getenv("LIVEKIT_API_KEY"), System.getenv("LIVEKIT_API_SECRET"), false,
                builder -> builder.callTimeout(2, java.util.concurrent.TimeUnit.SECONDS));
        LiveKitVoiceMedia media = new LiveKitVoiceMedia(client);
        String room = "uno_media_it_" + UUID.randomUUID();
        media.ensureRoom(room);
        try {
            var listing = client.listRooms(List.of(room)).execute();
            assertTrue(listing.isSuccessful());
            assertEquals(1, listing.body().size());
            assertEquals(2, listing.body().get(0).getMaxParticipants());
        } finally {
            media.deleteRoom(room);
        }
        media.deleteRoom(room);
        assertTrue(client.listRooms(List.of(room)).execute().body().isEmpty());
    }
}
