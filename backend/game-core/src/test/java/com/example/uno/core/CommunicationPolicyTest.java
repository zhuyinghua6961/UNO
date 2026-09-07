package com.example.uno.core;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.example.uno.core.CommunicationPolicy.*;

class CommunicationPolicyTest {
    private final CommunicationPolicy policy = new CommunicationPolicy();
    private final UUID user = UUID.randomUUID();
    private final UUID teammate = UUID.randomUUID();
    private final UUID opponent = UUID.randomUUID();
    private final UUID otherOpponent = UUID.randomUUID();

    private Room room(GameMode mode, Phase phase) {
        return new Room(UUID.randomUUID(), UUID.randomUUID(), mode, phase, List.of(
                new Player(user, Team.A), new Player(teammate, Team.A),
                new Player(opponent, Team.B), new Player(otherOpponent, Team.B)));
    }

    @Test
    void voiceIncludesOnlyTwoTeammates() {
        Audience audience = policy.resolve(room(GameMode.TEAM_2V2, Phase.PLAYING), user, Channel.TEAM_VOICE);
        assertEquals(List.of(user, teammate), audience.recipients());
    }

    @Test
    void teamsHaveDifferentVoiceScopes() {
        Room room = room(GameMode.TEAM_2V2, Phase.PLAYING);
        assertNotEquals(policy.resolve(room, user, Channel.TEAM_VOICE).scope(),
                policy.resolve(room, opponent, Channel.TEAM_VOICE).scope());
    }

    @Test
    void roomTextIncludesAllMembersAndTeamTextDoesNot() {
        Room room = room(GameMode.TEAM_2V2, Phase.WAITING);
        assertEquals(4, policy.resolve(room, user, Channel.ROOM_TEXT).recipients().size());
        assertEquals(List.of(user, teammate), policy.resolve(room, user, Channel.TEAM_TEXT).recipients());
    }

    @Test
    void outsidersCannotReadAnyChannel() {
        Room room = room(GameMode.TEAM_2V2, Phase.PLAYING);
        for (Channel channel : Channel.values()) {
            assertThrows(SecurityException.class, () -> policy.resolve(room, UUID.randomUUID(), channel));
        }
    }

    @Test
    void classicModeCannotUseTeamChannels() {
        Room room = room(GameMode.CLASSIC, Phase.PLAYING);
        assertThrows(SecurityException.class, () -> policy.resolve(room, user, Channel.TEAM_TEXT));
        assertThrows(SecurityException.class, () -> policy.resolve(room, user, Channel.TEAM_VOICE));
    }

    @Test
    void waitingOrFinishedRoomsCannotUseVoice() {
        for (Phase phase : List.of(Phase.WAITING, Phase.FINISHED)) {
            Room room = room(GameMode.TEAM_2V2, phase);
            assertThrows(SecurityException.class, () -> policy.resolve(room, user, Channel.TEAM_VOICE));
        }
    }

    @Test
    void unbalancedTeamsCannotUseVoice() {
        Room room = new Room(UUID.randomUUID(), UUID.randomUUID(), GameMode.TEAM_2V2, Phase.PLAYING,
                List.of(new Player(user, Team.A), new Player(teammate, Team.A), new Player(opponent, Team.B)));
        assertThrows(SecurityException.class, () -> policy.resolve(room, user, Channel.TEAM_VOICE));
    }

    @Test
    void changedGenerationChangesVoiceScope() {
        Room previous = room(GameMode.TEAM_2V2, Phase.PLAYING);
        Room current = new Room(previous.roomId(), UUID.randomUUID(), previous.mode(), previous.phase(), previous.players());
        assertNotEquals(policy.resolve(previous, user, Channel.TEAM_VOICE).scope(),
                policy.resolve(current, user, Channel.TEAM_VOICE).scope());
    }

    @Test
    void duplicateMembersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Room(UUID.randomUUID(), UUID.randomUUID(),
                GameMode.TEAM_2V2, Phase.PLAYING, List.of(new Player(user, Team.A), new Player(user, Team.B))));
    }

    @Test
    void validatesChatWithoutInterpretingMarkup() {
        assertEquals("<b>hello</b>", new ChatMessage("<b>hello</b>").content());
        assertEquals("😀".repeat(500), new ChatMessage("😀".repeat(500)).content());
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage("😀".repeat(501)));
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage(" "));
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage("hello\u0000"));
    }
}
