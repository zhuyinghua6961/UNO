package com.example.uno.game;

import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.matches.MatchService;
import com.example.uno.game.rooms.RoomService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
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
class GameDatabaseIT {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine"))
            .asCompatibleSubstituteFor("postgres"));

    @Test
    void gameServiceMigratesAndRestartsWithWaitingRoomsAndMatches() {
        for (int restart = 0; restart < 2; restart++) {
            try (var application = startService()) {
                var jdbc = application.getBean(JdbcTemplate.class);
                assertEquals(15, jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class));
                assertEquals(1, jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'game'", Integer.class));
                assertEquals(11, jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'game'", Integer.class));
                assertNull(jdbc.queryForObject("SELECT to_regclass('accounts')", String.class));
            }
        }
    }

    @Test
    void activeMatchKeepsItsDeadlineAndPrivateViewsAcrossServiceRestart() {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        UUID matchId;
        UUID roomId;
        Instant deadline;
        long version;
        try (var application = startService()) {
            var rooms = application.getBean(RoomService.class);
            var matches = application.getBean(MatchService.class);
            var room = rooms.create(host, "CLASSIC", 2);
            room = rooms.join(guest, room.code());
            room = rooms.ready(room.id(), host, true, room.version());
            room = rooms.ready(room.id(), guest, true, room.version());
            roomId = room.id();
            var started = matches.start(roomId, host, room.version());
            matchId = started.matchId();
            deadline = started.deadlineAt();
            version = started.view().version();
            assertNotNull(deadline);
        }

        try (var application = startService()) {
            var rooms = application.getBean(RoomService.class);
            var matches = application.getBean(MatchService.class);
            var jdbc = application.getBean(JdbcTemplate.class);
            var hostState = matches.snapshot(matchId, host);
            var guestState = matches.snapshot(matchId, guest);
            assertEquals(deadline, hostState.deadlineAt());
            assertEquals(deadline, guestState.deadlineAt());
            assertEquals(version, hostState.view().version());
            assertEquals(version, guestState.view().version());
            assertNotEquals(hostState.view().ownHand(), guestState.view().ownHand());
            assertEquals(matchId, matches.current(roomId, guest).matchId());
            assertEquals("PLAYING", rooms.get(roomId, host).state());

            jdbc.update("UPDATE game.matches SET deadline_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minusSeconds(1)), matchId);
            assertNotNull(matches.resolveTimeout(matchId));
            long resolvedVersion = matches.snapshot(matchId, host).view().version();
            assertTrue(resolvedVersion > version);
            assertNull(matches.resolveTimeout(matchId));
            assertEquals(resolvedVersion, matches.snapshot(matchId, host).view().version());
        }
    }

    private ConfigurableApplicationContext startService() {
        return new SpringApplicationBuilder(GameApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--uno.matches.deadline-worker-enabled=false");
    }

    private static GameIdentity player(String nickname) {
        return new GameIdentity(UUID.randomUUID(), UUID.randomUUID(), nickname, "APP", Instant.now().plusSeconds(3600));
    }
}
