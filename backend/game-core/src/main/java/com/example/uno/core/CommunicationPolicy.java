package com.example.uno.core;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CommunicationPolicy {
    public enum Team { NONE, A, B }
    public enum Phase { WAITING, PLAYING, FINISHED }
    public enum Channel { ROOM_TEXT, TEAM_TEXT, TEAM_VOICE }

    public record Player(UUID userId, Team team) {
        public Player {
            Objects.requireNonNull(userId);
            Objects.requireNonNull(team);
        }
    }

    public record Room(UUID roomId, UUID generation, GameMode mode, Phase phase, List<Player> players) {
        public Room {
            Objects.requireNonNull(roomId);
            Objects.requireNonNull(generation);
            Objects.requireNonNull(mode);
            Objects.requireNonNull(phase);
            players = List.copyOf(players);
            if (players.stream().map(Player::userId).distinct().count() != players.size()) {
                throw new IllegalArgumentException("Duplicate player identity");
            }
        }
    }

    public record Audience(String scope, List<UUID> recipients) {
        public Audience {
            recipients = List.copyOf(recipients);
        }
    }

    public Audience resolve(Room room, UUID userId, Channel channel) {
        Objects.requireNonNull(channel);
        Player player = room.players().stream()
                .filter(candidate -> candidate.userId().equals(userId))
                .findFirst().orElseThrow(() -> new SecurityException("Not a room member"));
        if (room.phase() == Phase.FINISHED) {
            throw new SecurityException("Communication session has ended");
        }
        String prefix = "room_" + room.roomId() + "_" + room.generation();
        if (channel == Channel.ROOM_TEXT) {
            return new Audience(prefix, room.players().stream().map(Player::userId).toList());
        }
        if (room.mode() != GameMode.TEAM_2V2 || player.team() == Team.NONE) {
            throw new SecurityException("No team channel in this mode");
        }
        List<UUID> teammates = room.players().stream()
                .filter(candidate -> candidate.team() == player.team()).map(Player::userId).toList();
        if (channel == Channel.TEAM_VOICE) {
            boolean balanced = room.players().size() == 4
                    && room.players().stream().filter(candidate -> candidate.team() == Team.A).count() == 2
                    && room.players().stream().filter(candidate -> candidate.team() == Team.B).count() == 2;
            if (room.phase() != Phase.PLAYING || !balanced) {
                throw new SecurityException("Voice requires an active, balanced 2v2 match");
            }
        }
        return new Audience(prefix + "_team_" + player.team(), teammates);
    }
}
