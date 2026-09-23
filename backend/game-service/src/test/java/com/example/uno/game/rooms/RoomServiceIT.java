package com.example.uno.game.rooms;

import com.example.uno.game.GameApplication;
import com.example.uno.game.auth.GameIdentity;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RoomServiceIT {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine"))
            .asCompatibleSubstituteFor("postgres"));
    private static ConfigurableApplicationContext application;
    private static RoomService rooms;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        application = new SpringApplicationBuilder(GameApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword());
        rooms = application.getBean(RoomService.class);
        jdbc = application.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stop() { if (application != null) application.close(); }

    @BeforeEach
    void empty() { jdbc.update("TRUNCATE game.room_members, game.rooms"); }

    @Test
    void classicRoomEnforcesMembershipVersionReadinessAndHostTransfer() {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        GameIdentity outsider = player("Outsider");
        RoomView created = rooms.create(host, "CLASSIC", 3);
        assertEquals(10, created.code().length());
        assertEquals(1, created.members().size());
        assertFalse(created.canStart());
        assertEquals("Host", created.members().get(0).nickname());
        assertEquals("ROOM_NOT_FOUND", assertThrows(RoomFailure.class,
                () -> rooms.get(created.id(), outsider)).code());

        RoomView joined = rooms.join(guest, created.code().toLowerCase());
        assertEquals(2, joined.members().size());
        assertEquals(joined.version(), rooms.join(guest, created.code()).version());
        assertEquals("ROOM_FORBIDDEN", assertThrows(RoomFailure.class,
                () -> rooms.changeMaxPlayers(created.id(), guest, 2, joined.version())).code());
        assertEquals("ROOM_CONFLICT", assertThrows(RoomFailure.class,
                () -> rooms.ready(created.id(), host, true, created.version())).code());

        RoomView readyHost = rooms.ready(created.id(), host, true, joined.version());
        RoomView readyBoth = rooms.ready(created.id(), guest, true, readyHost.version());
        assertTrue(readyBoth.canStart());
        RoomView changed = rooms.changeMaxPlayers(created.id(), host, 2, readyBoth.version());
        assertFalse(changed.canStart());
        assertTrue(changed.members().stream().noneMatch(RoomView.Member::ready));
        assertEquals("ROOM_FULL", assertThrows(RoomFailure.class,
                () -> rooms.join(outsider, created.code())).code());

        rooms.leave(created.id(), host);
        RoomView transferred = rooms.get(created.id(), guest);
        assertEquals(guest.userId(), transferred.hostUserId());
        rooms.leave(created.id(), guest);
        assertEquals("ROOM_NOT_FOUND", assertThrows(RoomFailure.class,
                () -> rooms.join(outsider, created.code())).code());
    }

    @Test
    void teamSeatsAlternateAndNeedTwoPerTeamAllReady() {
        GameIdentity a = player("A");
        GameIdentity b = player("B");
        GameIdentity c = player("C");
        GameIdentity d = player("D");
        RoomView room = rooms.create(a, "TEAM_2V2", 4);
        room = rooms.join(b, room.code());
        room = rooms.selectTeam(room.id(), b, "A", room.version());
        assertEquals("A", room.members().stream().filter(member -> member.userId().equals(b.userId())).findFirst().orElseThrow().team());
        room = rooms.join(c, room.code());
        room = rooms.join(d, room.code());
        assertEquals(2, room.members().stream().filter(member -> "A".equals(member.team())).count());
        assertEquals(2, room.members().stream().filter(member -> "B".equals(member.team())).count());
        UUID roomId = room.id();
        long version = room.version();
        assertEquals("ROOM_FULL", assertThrows(RoomFailure.class,
                () -> rooms.selectTeam(roomId, c, "A", version)).code());
        for (GameIdentity player : new GameIdentity[]{a, b, c, d}) {
            room = rooms.ready(room.id(), player, true, room.version());
        }
        assertTrue(room.canStart());
        rooms.leave(room.id(), d);
        assertFalse(rooms.get(room.id(), a).canStart());
    }

    @Test
    void concurrentJoinNeverOverfillsTheFinalSeat() throws Exception {
        GameIdentity host = player("Host");
        RoomView room = rooms.create(host, "CLASSIC", 2);
        GameIdentity first = player("First");
        GameIdentity second = player("Second");
        CountDownLatch begin = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> a = pool.submit(() -> attemptJoin(first, room.code(), begin));
            Future<Object> b = pool.submit(() -> attemptJoin(second, room.code(), begin));
            begin.countDown();
            Object resultA = a.get();
            Object resultB = b.get();
            assertTrue(resultA instanceof RoomView ^ resultB instanceof RoomView);
            assertEquals(2, rooms.get(room.id(), host).members().size());
            assertEquals("ROOM_FULL", (resultA instanceof RoomFailure ? (RoomFailure) resultA : (RoomFailure) resultB).code());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expiredInvitationCannotBeReadAndUserCanCreateAgain() {
        GameIdentity host = player("Host");
        RoomView room = rooms.create(host, "CLASSIC", 2);
        jdbc.update("UPDATE game.rooms SET expires_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), room.id());
        assertNull(rooms.current(host));
        assertEquals("ROOM_NOT_FOUND", assertThrows(RoomFailure.class,
                () -> rooms.get(room.id(), host)).code());
        assertEquals("ROOM_NOT_FOUND", assertThrows(RoomFailure.class,
                () -> rooms.join(player("Guest"), room.code())).code());
        assertNotEquals(room.id(), rooms.create(host, "CLASSIC", 2).id());
    }

    @Test
    void repeatedCodeGuessesAreLimitedPerAccount() {
        GameIdentity guesser = player("Guesser");
        for (int attempt = 0; attempt < 12; attempt++) {
            assertEquals("INVALID_ROOM_INPUT", assertThrows(RoomFailure.class,
                    () -> rooms.join(guesser, "invalid-code")).code());
        }
        assertEquals("JOIN_RATE_LIMITED", assertThrows(RoomFailure.class,
                () -> rooms.join(guesser, "invalid-code")).code());
    }

    private Object attemptJoin(GameIdentity player, String code, CountDownLatch begin) {
        try {
            begin.await();
            return rooms.join(player, code);
        } catch (RoomFailure failure) {
            return failure;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private GameIdentity player(String nickname) {
        return new GameIdentity(UUID.randomUUID(), UUID.randomUUID(), nickname, "APP", Instant.now().plusSeconds(3600));
    }
}
