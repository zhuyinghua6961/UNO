package com.example.uno.game.voice;

import java.util.Set;
import java.util.UUID;

interface VoiceSessionStatus {
    Set<UUID> activeIds(Set<UUID> sessionIds);
}
