package com.example.uno.game.rooms;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoomView(UUID id, String code, String mode, int maxPlayers, UUID hostUserId,
        String state, long version, Instant expiresAt, boolean canStart, List<Member> members) {
    public record Member(UUID userId, String nickname, int seat, String team, boolean ready) { }
}
