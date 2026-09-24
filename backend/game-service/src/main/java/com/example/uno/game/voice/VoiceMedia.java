package com.example.uno.game.voice;

import java.util.List;

interface VoiceMedia {
    void ensureRoom(String roomName);
    void deleteRoom(String roomName);
    List<Participant> participants(String roomName);

    record Participant(String identity, String metadata) { }
}
