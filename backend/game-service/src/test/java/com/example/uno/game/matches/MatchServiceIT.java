package com.example.uno.game.matches;

import static org.junit.jupiter.api.Assertions.*;

import com.example.uno.game.GameApplication;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.rooms.RoomFailure;
import com.example.uno.game.rooms.RoomService;
import com.example.uno.game.rooms.RoomView;
import com.example.uno.core.rules.UnoCard;
import com.example.uno.core.rules.UnoState;
import java.time.Instant;
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
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class MatchServiceIT {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine"))
            .asCompatibleSubstituteFor("postgres"));
    private static ConfigurableApplicationContext application;
    private static MatchService matches;
    private static RoomService rooms;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        application = new SpringApplicationBuilder(GameApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword());
        matches = application.getBean(MatchService.class);
        rooms = application.getBean(RoomService.class);
        jdbc = application.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stop() { if (application != null) application.close(); }

    @BeforeEach
    void empty() { jdbc.update("TRUNCATE game.match_commands, game.match_players, game.matches, game.room_members, game.rooms"); }

    @Test
    void readyClassicRoomStartsOnceAndRestoresSeparatePrivateViews() {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        GameIdentity outsider = player("Outsider");
        RoomView room = rooms.create(host, "CLASSIC", 2);
        room = rooms.join(guest, room.code());
        UUID roomId = room.id();
        long oldVersion = room.version();
        assertEquals("MATCH_FORBIDDEN", assertThrows(MatchFailure.class,
                () -> matches.start(roomId, guest, oldVersion)).code());
        assertEquals("MATCH_CONFLICT", assertThrows(MatchFailure.class,
                () -> matches.start(roomId, host, oldVersion)).code());
        room = rooms.ready(roomId, host, true, room.version());
        room = rooms.ready(roomId, guest, true, room.version());
        long readyVersion = room.version();
        assertEquals("MATCH_CONFLICT", assertThrows(MatchFailure.class,
                () -> matches.start(roomId, host, oldVersion)).code());

        MatchService.MatchStart started = matches.start(roomId, host, readyVersion);
        assertEquals(started.matchId(), matches.start(roomId, host, readyVersion).matchId());
        assertEquals(started.matchId(), matches.current(roomId, guest).matchId());
        assertNull(matches.current(roomId, outsider));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game.matches", Integer.class));
        assertEquals("PLAYING", rooms.get(roomId, host).state());
        assertEquals(readyVersion + 1, started.roomVersion());
        assertEquals("ROOM_CONFLICT", assertThrows(RoomFailure.class,
                () -> rooms.leave(roomId, guest)).code());

        var hostView = matches.state(started.matchId(), host);
        var guestView = matches.state(started.matchId(), guest);
        assertEquals(started.view(), hostView);
        assertTrue(hostView.ownHand().size() >= 7);
        assertTrue(guestView.ownHand().size() >= 7);
        assertNotEquals(hostView.ownHand(), guestView.ownHand());
        assertEquals(guestView.ownHand().size(), hostView.players().get(1).handCount());
        assertEquals(hostView.ownHand().size(), guestView.players().get(0).handCount());
        assertEquals("MATCH_NOT_FOUND", assertThrows(MatchFailure.class,
                () -> matches.state(started.matchId(), outsider)).code());
        String responseJson = new JsonMapper().writeValueAsString(hostView);
        assertTrue(responseJson.contains("\"ownHand\""));
        assertTrue(responseJson.contains("\"id\""));
        assertFalse(responseJson.contains("drawPile"));

        GameIdentity actor = started.view().players().get(started.view().currentSeat()).userId().equals(host.userId())
                ? host : guest;
        boolean openingWild = started.view().phase() == UnoState.Phase.INITIAL_WILD_COLOR;
        MatchCommandInput action = new MatchCommandInput(1, UUID.randomUUID(), started.view().version(),
                openingWild ? MatchCommandInput.Type.CHOOSE_INITIAL_COLOR : MatchCommandInput.Type.DRAW,
                null, openingWild ? UnoCard.Color.RED : null, null, false);
        var applied = matches.command(started.matchId(), actor, action);
        assertFalse(applied.duplicate());
        assertEquals(2, applied.appliedVersion());
        var repeated = matches.command(started.matchId(), actor, action);
        assertTrue(repeated.duplicate());
        assertEquals(2, repeated.appliedVersion());
        assertEquals(applied.view(), repeated.view());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game.match_commands", Integer.class));
        MatchCommandInput reusedId = new MatchCommandInput(1, action.commandId(), 1,
                MatchCommandInput.Type.PASS, null, null, null, false);
        assertEquals("MATCH_CONFLICT", assertThrows(MatchFailure.class,
                () -> matches.command(started.matchId(), actor, reusedId)).code());
        MatchCommandInput stale = new MatchCommandInput(1, UUID.randomUUID(), 1,
                MatchCommandInput.Type.DRAW, null, null, null, false);
        assertEquals("MATCH_CONFLICT", assertThrows(MatchFailure.class,
                () -> matches.command(started.matchId(), actor, stale)).code());
        assertEquals("MATCH_NOT_FOUND", assertThrows(MatchFailure.class,
                () -> matches.command(started.matchId(), outsider, stale)).code());
    }

    @Test
    void seatsAreCompactForGameAfterSomeoneLeavesWaitingRoom() {
        GameIdentity host = player("Host");
        GameIdentity leaver = player("Leaver");
        GameIdentity guest = player("Guest");
        RoomView room = rooms.create(host, "CLASSIC", 3);
        room = rooms.join(leaver, room.code());
        room = rooms.join(guest, room.code());
        rooms.leave(room.id(), leaver);
        room = rooms.get(room.id(), host);
        assertEquals(2, room.members().get(1).seat());
        room = rooms.ready(room.id(), host, true, room.version());
        room = rooms.ready(room.id(), guest, true, room.version());
        var started = matches.start(room.id(), host, room.version());
        assertEquals(1, matches.state(started.matchId(), guest).players().get(1).seat());
    }

    private GameIdentity player(String nickname) {
        return new GameIdentity(UUID.randomUUID(), UUID.randomUUID(), nickname, "APP", Instant.now().plusSeconds(3600));
    }
}
