package com.example.uno.game.voice;

import com.example.uno.game.auth.GameIdentity;
import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanPublishData;
import io.livekit.server.CanPublishSources;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VoiceService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final VoiceSettings settings;
    private final VoiceMedia media;

    VoiceService(JdbcTemplate jdbc, Clock roomClock, VoiceSettings settings, VoiceMedia media) {
        this.jdbc = jdbc;
        this.clock = roomClock;
        this.settings = settings;
        this.media = media;
    }

    public VoiceToken issue(UUID matchId, GameIdentity identity) {
        if (!settings.enabled()) throw VoiceFailure.unavailable();
        Instant now = clock.instant();
        if (!identity.expiresAt().isAfter(now.plusSeconds(2))) throw VoiceFailure.notFound();
        VoiceSeat seat = allowedSeat(matchId, identity.userId());
        if (seat == null) throw VoiceFailure.notFound();
        int acquired = jdbc.update("INSERT INTO game.voice_token_issuance(match_id, user_id, requested_at) "
                        + "VALUES (?, ?, ?) ON CONFLICT (match_id, user_id) DO UPDATE "
                        + "SET requested_at = EXCLUDED.requested_at "
                        + "WHERE game.voice_token_issuance.requested_at <= ?",
                matchId, identity.userId(), Timestamp.from(now), Timestamp.from(now.minusSeconds(3)));
        if (acquired == 0) throw VoiceFailure.rateLimited();
        String roomName = roomName(matchId, seat.generation(), seat.team());
        media.ensureRoom(roomName);
        VoiceSeat stillAllowed = allowedSeat(matchId, identity.userId());
        if (!seat.equals(stillAllowed)) throw VoiceFailure.notFound();
        Instant expiresAt = now.plusSeconds(60).isBefore(identity.expiresAt())
                ? now.plusSeconds(60) : identity.expiresAt();
        AccessToken token = new AccessToken(settings.apiKey(), settings.apiSecret());
        token.setIdentity(identity.userId().toString());
        token.setName(identity.nickname());
        token.setNotBefore(Date.from(now.minusSeconds(2)));
        token.setExpiration(Date.from(expiresAt));
        token.addGrants(new RoomJoin(true), new RoomName(roomName), new CanPublish(true),
                new CanPublishSources(List.of("microphone")), new CanSubscribe(true), new CanPublishData(false));
        return new VoiceToken(settings.publicUrl().toString(), token.toJwt(), expiresAt);
    }

    private VoiceSeat allowedSeat(UUID matchId, UUID userId) {
        List<VoiceSeat> found = jdbc.query("SELECT m.mode, m.state, m.voice_generation, r.state AS room_state, "
                        + "p.team_snapshot, p.seat, rm.seat AS current_seat, "
                        + "(SELECT count(*) FROM game.match_players q WHERE q.match_id = m.id) AS player_count, "
                        + "(SELECT count(*) FROM game.match_players q WHERE q.match_id = m.id AND q.team_snapshot = 'A') AS team_a_count, "
                        + "(SELECT count(*) FROM game.match_players q WHERE q.match_id = m.id AND q.team_snapshot = 'B') AS team_b_count "
                        + "FROM game.matches m JOIN game.match_players p ON p.match_id = m.id AND p.user_id = ? "
                        + "LEFT JOIN game.rooms r ON r.id = m.room_id "
                        + "LEFT JOIN game.room_members rm ON rm.room_id = m.room_id AND rm.user_id = p.user_id "
                        + "WHERE m.id = ?",
                (rs, row) -> {
                    int currentSeat = rs.getInt("current_seat");
                    boolean hasCurrentSeat = !rs.wasNull();
                    int savedSeat = rs.getInt("seat");
                    String team = rs.getString("team_snapshot");
                    if (!"TEAM_2V2".equals(rs.getString("mode")) || !"PLAYING".equals(rs.getString("state"))
                            || !"PLAYING".equals(rs.getString("room_state")) || !hasCurrentSeat
                            || savedSeat != currentSeat || rs.getInt("player_count") != 4
                            || rs.getInt("team_a_count") != 2 || rs.getInt("team_b_count") != 2
                            || !(savedSeat % 2 == 0 ? "A" : "B").equals(team)) return null;
                    return new VoiceSeat(rs.getObject("voice_generation", UUID.class), team);
                }, userId, matchId);
        return found.isEmpty() ? null : found.get(0);
    }

    @Scheduled(fixedDelayString = "${uno.voice.cleanup-poll-ms:3000}")
    @Transactional
    public void cleanEndedMatch() {
        if (!settings.enabled()) return;
        List<Cleanup> due = jdbc.query("SELECT match_id, voice_generation, attempts, retain_until FROM game.voice_cleanup "
                        + "WHERE next_attempt_at <= ? ORDER BY next_attempt_at LIMIT 8 FOR UPDATE SKIP LOCKED",
                (rs, row) -> new Cleanup(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getInt(3), rs.getTimestamp(4).toInstant()),
                Timestamp.from(clock.instant()));
        for (Cleanup task : due) {
            try {
                media.deleteRoom(roomName(task.matchId(), task.generation(), "A"));
                media.deleteRoom(roomName(task.matchId(), task.generation(), "B"));
                if (!clock.instant().isBefore(task.retainUntil())) {
                    jdbc.update("DELETE FROM game.voice_cleanup WHERE match_id = ?", task.matchId());
                } else {
                    jdbc.update("UPDATE game.voice_cleanup SET attempts = 0, next_attempt_at = ? WHERE match_id = ?",
                            Timestamp.from(clock.instant().plusSeconds(3)), task.matchId());
                }
            } catch (VoiceFailure failure) {
                long delay = Math.min(60, 1L << Math.min(task.attempts(), 5));
                jdbc.update("UPDATE game.voice_cleanup SET attempts = attempts + 1, next_attempt_at = ? WHERE match_id = ?",
                        Timestamp.from(clock.instant().plusSeconds(delay)), task.matchId());
            }
        }
    }

    static String roomName(UUID matchId, UUID generation, String team) {
        return "uno_" + matchId + "_" + generation + "_team_" + team;
    }

    public record VoiceToken(String url, String token, Instant expiresAt) { }
    private record VoiceSeat(UUID generation, String team) { }
    private record Cleanup(UUID matchId, UUID generation, int attempts, Instant retainUntil) { }
}
