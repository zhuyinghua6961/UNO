package com.example.uno.game.voice;

import static org.junit.jupiter.api.Assertions.*;

import com.example.uno.game.GameApplication;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.auth.GameAuthFailure;
import com.example.uno.game.matches.MatchService;
import com.example.uno.game.rooms.RoomService;
import com.example.uno.game.rooms.RoomView;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class VoiceServiceIT {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine"))
            .asCompatibleSubstituteFor("postgres"));
    private static ConfigurableApplicationContext application;
    private static JdbcTemplate jdbc;
    private static RoomService rooms;
    private static MatchService matches;
    private FakeMedia media;
    private FakeSessions sessions;
    private VoiceService voice;

    @BeforeAll
    static void start() {
        application = new SpringApplicationBuilder(GameApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--uno.matches.deadline-worker-enabled=false");
        jdbc = application.getBean(JdbcTemplate.class);
        rooms = application.getBean(RoomService.class);
        matches = application.getBean(MatchService.class);
    }

    @AfterAll
    static void stop() { if (application != null) application.close(); }

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE game.match_socket_ownership, game.voice_cleanup, "
                + "game.voice_issued_sessions, game.voice_token_issuance, game.match_commands, "
                + "game.match_players, game.matches, game.room_members, game.rooms");
        media = new FakeMedia();
        sessions = new FakeSessions();
        voice = new VoiceService(jdbc, Clock.systemUTC(), new VoiceSettings(true,
                URI.create("http://127.0.0.1:7880"), URI.create("ws://127.0.0.1:7880"),
                "devkey", "this-is-a-local-test-secret-of-at-least-32-bytes", true), media, sessions);
    }

    @Test
    void tokensUseImmutableTeamSeatAndOnlyMicrophoneGrant() throws Exception {
        GameIdentity a = player("A");
        GameIdentity b = player("B");
        GameIdentity teammate = player("A2");
        GameIdentity opponent = player("B2");
        UUID matchId = startTeam(a, b, teammate, opponent);
        String generation = jdbc.queryForObject("SELECT voice_generation::text FROM game.matches WHERE id = ?",
                String.class, matchId);
        var aToken = voice.issue(matchId, a);
        var teammateToken = voice.issue(matchId, teammate);
        var bToken = voice.issue(matchId, b);
        assertEquals("ws://127.0.0.1:7880", aToken.url());
        assertTrue(aToken.expiresAt().isBefore(Instant.now().plusSeconds(61)));
        assertEquals(2, media.created.size());
        JsonNode aClaims = claims(aToken.token());
        JsonNode teammateClaims = claims(teammateToken.token());
        JsonNode bClaims = claims(bToken.token());
        String aRoom = "uno_" + matchId + "_" + generation + "_team_A";
        String bRoom = "uno_" + matchId + "_" + generation + "_team_B";
        assertEquals(aRoom, aClaims.path("video").path("room").asText());
        assertEquals(aRoom, teammateClaims.path("video").path("room").asText());
        assertEquals(bRoom, bClaims.path("video").path("room").asText());
        assertNotEquals(aClaims.path("sub"), teammateClaims.path("sub"));
        assertTrue(aClaims.path("video").path("roomJoin").asBoolean());
        assertTrue(aClaims.path("video").path("canPublish").asBoolean());
        assertTrue(aClaims.path("video").path("canSubscribe").asBoolean());
        assertFalse(aClaims.path("video").path("canPublishData").asBoolean());
        assertEquals("microphone", aClaims.path("video").path("canPublishSources").get(0).asText());
        assertEquals(a.sessionId().toString(), aClaims.path("metadata").asText());
        assertFalse(aClaims.path("video").path("roomCreate").asBoolean());
        assertEquals("VOICE_RATE_LIMITED", assertThrows(VoiceFailure.class,
                () -> voice.issue(matchId, a)).code());
        assertEquals("VOICE_NOT_AVAILABLE", assertThrows(VoiceFailure.class,
                () -> voice.issue(matchId, player("Outsider"))).code());
    }

    @Test
    void classicEndedAndSeatMismatchCannotIssue() {
        GameIdentity a = player("A");
        GameIdentity b = player("B");
        GameIdentity teammate = player("A2");
        GameIdentity opponent = player("B2");
        UUID matchId = startTeam(a, b, teammate, opponent);
        jdbc.update("UPDATE game.room_members SET seat = 5 WHERE user_id = ?", a.userId());
        assertEquals("VOICE_NOT_AVAILABLE", assertThrows(VoiceFailure.class,
                () -> voice.issue(matchId, a)).code());
        jdbc.update("UPDATE game.matches SET state = 'ENDED', ended_at = now() WHERE id = ?", matchId);
        assertEquals("VOICE_NOT_AVAILABLE", assertThrows(VoiceFailure.class,
                () -> voice.issue(matchId, b)).code());
        RoomView room = rooms.create(player("Classic host"), "CLASSIC", 2);
        GameIdentity guest = player("Classic guest");
        room = rooms.join(guest, room.code());
        GameIdentity host = playerFromRoom(room);
        room = rooms.ready(room.id(), host, true, room.version());
        room = rooms.ready(room.id(), guest, true, room.version());
        UUID classic = matches.start(room.id(), host, room.version()).matchId();
        assertEquals("VOICE_NOT_AVAILABLE", assertThrows(VoiceFailure.class,
                () -> voice.issue(classic, guest)).code());
    }

    @Test
    void mediaFailureCannotStopGameAndCleanupRetries() {
        GameIdentity a = player("A");
        GameIdentity b = player("B");
        GameIdentity teammate = player("A2");
        GameIdentity opponent = player("B2");
        UUID matchId = startTeam(a, b, teammate, opponent);
        media.failCreate = true;
        assertEquals("VOICE_UNAVAILABLE", assertThrows(VoiceFailure.class,
                () -> voice.issue(matchId, a)).code());
        assertNotNull(matches.state(matchId, a));
        UUID generation = jdbc.queryForObject("SELECT voice_generation FROM game.matches WHERE id = ?",
                UUID.class, matchId);
        Instant now = Instant.now();
        jdbc.update("INSERT INTO game.voice_cleanup(match_id, voice_generation, next_attempt_at, retain_until) "
                        + "VALUES (?, ?, ?, ?)",
                matchId, generation, Timestamp.from(now), Timestamp.from(now.plusSeconds(70)));
        Instant retainUntil = jdbc.queryForObject("SELECT retain_until FROM game.voice_cleanup WHERE match_id = ?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), matchId);
        assertTrue(retainUntil.isAfter(now.plusSeconds(60)));
        media.failDelete = true;
        voice.cleanEndedMatch();
        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM game.voice_cleanup WHERE match_id = ?",
                Integer.class, matchId));
        media.failDelete = false;
        jdbc.update("UPDATE game.voice_cleanup SET next_attempt_at = ? WHERE match_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), matchId);
        voice.cleanEndedMatch();
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game.voice_cleanup WHERE match_id = ?",
                Integer.class, matchId));
        assertEquals(0, jdbc.queryForObject("SELECT attempts FROM game.voice_cleanup WHERE match_id = ?",
                Integer.class, matchId));
        assertEquals(1, media.deleted.stream().filter(name -> name.equals(VoiceService.roomName(matchId, generation, "A"))).count());
        assertEquals(1, media.deleted.stream().filter(name -> name.equals(VoiceService.roomName(matchId, generation, "B"))).count());
        // A still-valid LiveKit JWT can recreate a deleted room; the retained task deletes it again.
        jdbc.update("UPDATE game.voice_cleanup SET next_attempt_at = ?, retain_until = ? WHERE match_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), Timestamp.from(Instant.now().minusSeconds(1)), matchId);
        voice.cleanEndedMatch();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM game.voice_cleanup WHERE match_id = ?",
                Integer.class, matchId));
        assertEquals(2, media.deleted.stream().filter(name -> name.equals(VoiceService.roomName(matchId, generation, "A"))).count());
        assertEquals(2, media.deleted.stream().filter(name -> name.equals(VoiceService.roomName(matchId, generation, "B"))).count());
    }

    @Test
    void cleanupProcessesOtherMatchesWhenOneMediaRoomFails() {
        UUID first = startTeam(player("First A"), player("First B"), player("First A2"), player("First B2"));
        UUID second = startTeam(player("Second A"), player("Second B"), player("Second A2"), player("Second B2"));
        UUID firstGeneration = jdbc.queryForObject("SELECT voice_generation FROM game.matches WHERE id = ?", UUID.class, first);
        UUID secondGeneration = jdbc.queryForObject("SELECT voice_generation FROM game.matches WHERE id = ?", UUID.class, second);
        jdbc.update("INSERT INTO game.voice_cleanup(match_id, voice_generation, retain_until) VALUES (?, ?, ?), (?, ?, ?)",
                first, firstGeneration, Timestamp.from(Instant.now().minusSeconds(1)),
                second, secondGeneration, Timestamp.from(Instant.now().minusSeconds(1)));
        media.failRoom = VoiceService.roomName(first, firstGeneration, "A");

        voice.cleanEndedMatch();

        assertEquals(1, jdbc.queryForObject("SELECT attempts FROM game.voice_cleanup WHERE match_id = ?", Integer.class, first));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM game.voice_cleanup WHERE match_id = ?", Integer.class, second));
        assertTrue(media.deleted.contains(VoiceService.roomName(second, secondGeneration, "A")));
        assertTrue(media.deleted.contains(VoiceService.roomName(second, secondGeneration, "B")));
    }

    @Test
    void revokedConnectedSessionRotatesGenerationAndDeletesBothOldRooms() throws Exception {
        GameIdentity a = player("A");
        GameIdentity b = player("B");
        GameIdentity teammate = player("A2");
        GameIdentity opponent = player("B2");
        UUID matchId = startTeam(a, b, teammate, opponent);
        UUID oldGeneration = jdbc.queryForObject("SELECT voice_generation FROM game.matches WHERE id = ?", UUID.class, matchId);
        String oldRoom = VoiceService.roomName(matchId, oldGeneration, "A");
        voice.issue(matchId, a);
        voice.issue(matchId, teammate);
        media.connected.put(oldRoom, List.of(new VoiceMedia.Participant(a.userId().toString(), a.sessionId().toString()),
                new VoiceMedia.Participant(teammate.userId().toString(), teammate.sessionId().toString())));

        voice.reviewConnectedSessions();
        assertEquals(oldGeneration, jdbc.queryForObject("SELECT voice_generation FROM game.matches WHERE id = ?", UUID.class, matchId));
        sessions.revoked.add(a.sessionId());
        jdbc.update("UPDATE game.matches SET voice_reviewed_at = now() - INTERVAL '10 seconds' WHERE id = ?", matchId);
        voice.reviewConnectedSessions();

        UUID nextGeneration = jdbc.queryForObject("SELECT voice_generation FROM game.matches WHERE id = ?", UUID.class, matchId);
        assertNotEquals(oldGeneration, nextGeneration);
        assertTrue(media.deleted.contains(oldRoom));
        assertTrue(media.deleted.contains(VoiceService.roomName(matchId, oldGeneration, "B")));
        assertEquals(oldGeneration, jdbc.queryForObject("SELECT voice_generation FROM game.voice_cleanup WHERE match_id = ?", UUID.class, matchId));
        jdbc.update("UPDATE game.voice_token_issuance SET requested_at = now() - INTERVAL '5 seconds' WHERE match_id = ?", matchId);
        assertEquals(VoiceService.roomName(matchId, nextGeneration, "A"),
                claims(voice.issue(matchId, teammate).token()).path("video").path("room").asText());
    }

    @Test
    void revokedIssuedSessionRotatesAfterParticipantHasDisconnected() {
        GameIdentity a = player("A");
        GameIdentity teammate = player("A2");
        UUID matchId = startTeam(a, player("B"), teammate, player("B2"));
        UUID oldGeneration = jdbc.queryForObject("SELECT voice_generation FROM game.matches WHERE id = ?",
                UUID.class, matchId);
        voice.issue(matchId, a);
        voice.issue(matchId, teammate);
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM game.voice_issued_sessions "
                + "WHERE match_id = ? AND voice_generation = ?", Integer.class, matchId, oldGeneration));

        // Both media participants have left; their unexpired grants still name the old room.
        voice.reviewConnectedSessions();
        assertEquals(oldGeneration, jdbc.queryForObject("SELECT voice_generation FROM game.matches WHERE id = ?",
                UUID.class, matchId));
        sessions.revoked.add(a.sessionId());
        jdbc.update("UPDATE game.matches SET voice_reviewed_at = now() - INTERVAL '10 seconds' WHERE id = ?", matchId);
        voice.reviewConnectedSessions();

        assertNotEquals(oldGeneration, jdbc.queryForObject("SELECT voice_generation FROM game.matches WHERE id = ?",
                UUID.class, matchId));
        assertTrue(media.deleted.contains(VoiceService.roomName(matchId, oldGeneration, "A")));
        assertTrue(media.deleted.contains(VoiceService.roomName(matchId, oldGeneration, "B")));
    }

    @Test
    void activeVoiceFailsClosedWhenSessionStatusCannotBeVerified() {
        GameIdentity a = player("A");
        UUID matchId = startTeam(a, player("B"), player("A2"), player("B2"));
        UUID oldGeneration = jdbc.queryForObject("SELECT voice_generation FROM game.matches WHERE id = ?", UUID.class, matchId);
        voice.issue(matchId, a);
        media.connected.put(VoiceService.roomName(matchId, oldGeneration, "A"),
                List.of(new VoiceMedia.Participant(a.userId().toString(), a.sessionId().toString())));
        sessions.unavailable = true;

        voice.reviewConnectedSessions();

        assertNotEquals(oldGeneration, jdbc.queryForObject("SELECT voice_generation FROM game.matches WHERE id = ?", UUID.class, matchId));
        assertTrue(media.deleted.contains(VoiceService.roomName(matchId, oldGeneration, "A")));
        assertTrue(media.deleted.contains(VoiceService.roomName(matchId, oldGeneration, "B")));
    }

    private UUID startTeam(GameIdentity a, GameIdentity b, GameIdentity c, GameIdentity d) {
        RoomView room = rooms.create(a, "TEAM_2V2", 4);
        for (GameIdentity player : List.of(b, c, d)) room = rooms.join(player, room.code());
        for (GameIdentity player : List.of(a, b, c, d))
            room = rooms.ready(room.id(), player, true, room.version());
        return matches.start(room.id(), a, room.version()).matchId();
    }

    private static GameIdentity player(String nickname) {
        return new GameIdentity(UUID.randomUUID(), UUID.randomUUID(), nickname, "WEB", Instant.now().plusSeconds(3600));
    }

    private static GameIdentity playerFromRoom(RoomView room) {
        return new GameIdentity(room.hostUserId(), UUID.randomUUID(), "Classic host", "WEB", Instant.now().plusSeconds(3600));
    }

    private static JsonNode claims(String token) throws Exception {
        return new JsonMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
    }

    private static final class FakeMedia implements VoiceMedia {
        final List<String> created = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        boolean failCreate;
        boolean failDelete;
        String failRoom;
        final Map<String, List<VoiceMedia.Participant>> connected = new HashMap<>();

        @Override public void ensureRoom(String roomName) {
            if (failCreate) throw VoiceFailure.unavailable();
            if (!created.contains(roomName)) created.add(roomName);
        }

        @Override public void deleteRoom(String roomName) {
            if (failDelete || roomName.equals(failRoom)) throw VoiceFailure.unavailable();
            deleted.add(roomName);
            connected.remove(roomName);
        }

        @Override public List<VoiceMedia.Participant> participants(String roomName) {
            return connected.getOrDefault(roomName, List.of());
        }
    }

    private static final class FakeSessions implements VoiceSessionStatus {
        final Set<UUID> revoked = new HashSet<>();
        boolean unavailable;
        @Override public Set<UUID> activeIds(Set<UUID> ids) {
            if (unavailable) throw GameAuthFailure.unavailable();
            Set<UUID> found = new HashSet<>(ids);
            found.removeAll(revoked);
            return found;
        }
    }
}
