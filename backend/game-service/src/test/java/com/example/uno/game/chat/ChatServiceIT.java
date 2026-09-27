package com.example.uno.game.chat;

import com.example.uno.game.GameApplication;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.rooms.RoomService;
import com.example.uno.game.rooms.RoomView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class ChatServiceIT {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine"))
            .asCompatibleSubstituteFor("postgres"));
    private static ConfigurableApplicationContext application;
    private static RoomService rooms;
    private static ChatService chat;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void start() {
        application = new SpringApplicationBuilder(GameApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword());
        rooms = application.getBean(RoomService.class);
        chat = application.getBean(ChatService.class);
        jdbc = application.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void stop() { if (application != null) application.close(); }

    @BeforeEach
    void empty() {
        jdbc.update("TRUNCATE game.match_socket_ownership, game.chat_reports, game.chat_mutes, game.chat_messages, "
                + "game.chat_channel_sequences, game.voice_cleanup, "
                + "game.voice_token_issuance, game.match_commands, "
                + "game.match_players, game.matches, game.room_members, game.rooms");
    }

    @Test
    void roomMessagesAreOrderedIdempotentAndVisibleOnlyToCurrentMembers() {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        GameIdentity outsider = player("Outsider");
        RoomView room = rooms.create(host, "CLASSIC", 3);
        rooms.join(guest, room.code());
        UUID firstId = UUID.randomUUID();
        ChatService.ChatItem first = chat.send(room.id(), host, firstId, "<b>普通文字</b>");
        ChatService.ChatItem duplicate = chat.send(room.id(), host, firstId, "<b>普通文字</b>");
        ChatService.ChatItem second = chat.send(room.id(), guest, UUID.randomUUID(), "你好 👋");
        assertEquals(first.id(), duplicate.id());
        assertEquals(1, first.sequence());
        assertEquals(2, second.sequence());
        assertEquals(host.userId(), first.senderUserId());
        assertEquals("Host", first.senderNickname());
        assertEquals("<b>普通文字</b>", first.content());
        assertEquals(1, chat.history(room.id(), guest, 0, 1).items().size());
        assertTrue(chat.history(room.id(), guest, 0, 1).hasMore());
        assertEquals(second.id(), chat.history(room.id(), guest, 0, 1, true).items().get(0).id());
        assertEquals(second.id(), chat.history(room.id(), guest, 1, 10).items().get(0).id());
        assertEquals("CHAT_ROOM_NOT_FOUND", assertThrows(ChatFailure.class,
                () -> chat.history(room.id(), outsider, 0, 10)).code());
        assertEquals("CHAT_MESSAGE_CONFLICT", assertThrows(ChatFailure.class,
                () -> chat.send(room.id(), host, firstId, "另一条消息")).code());
        rooms.leave(room.id(), guest);
        assertEquals("CHAT_ROOM_NOT_FOUND", assertThrows(ChatFailure.class,
                () -> chat.history(room.id(), guest, 0, 10)).code());
        assertEquals("CHAT_ROOM_NOT_FOUND", assertThrows(ChatFailure.class,
                () -> chat.send(room.id(), guest, UUID.randomUUID(), "离开后不能发")).code());
    }

    @Test
    void contentRateAndRejoinHistoryAreConstrained() {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        RoomView room = rooms.create(host, "CLASSIC", 2);
        rooms.join(guest, room.code());
        assertEquals("INVALID_CHAT_INPUT", assertThrows(ChatFailure.class,
                () -> chat.send(room.id(), host, UUID.randomUUID(), "\u0000bad")).code());
        assertEquals("INVALID_CHAT_INPUT", assertThrows(ChatFailure.class,
                () -> chat.send(room.id(), host, UUID.randomUUID(), "x".repeat(501))).code());
        chat.send(room.id(), host, UUID.randomUUID(), "one");
        chat.send(room.id(), host, UUID.randomUUID(), "two");
        assertEquals("CHAT_RATE_LIMITED", assertThrows(ChatFailure.class,
                () -> chat.send(room.id(), host, UUID.randomUUID(), "three")).code());
        chat.send(room.id(), guest, UUID.randomUUID(), "guest");
        rooms.leave(room.id(), guest);
        rooms.join(guest, room.code());
        jdbc.update("UPDATE game.room_members SET joined_at = ? WHERE room_id = ? AND user_id = ?",
                Timestamp.from(Instant.now().plusSeconds(1)), room.id(), guest.userId());
        assertTrue(chat.history(room.id(), guest, 0, 10).items().isEmpty());
        assertEquals("INVALID_CHAT_INPUT", assertThrows(ChatFailure.class,
                () -> chat.history(room.id(), host, -1, 10)).code());
    }

    @Test
    void teamTextUsesServerSeatAndSwitchingTeamsStartsANewHistoryWindow() {
        GameIdentity a = player("A");
        GameIdentity b = player("B");
        GameIdentity outsider = player("Outsider");
        RoomView room = rooms.create(a, "TEAM_2V2", 4);
        room = rooms.join(b, room.code());
        UUID roomId = room.id();
        UUID oldAId = UUID.randomUUID();
        var oldA = chat.send(roomId, a, oldAId, "A old", "TEAM");
        var oldB = chat.send(roomId, b, UUID.randomUUID(), "B old", "TEAM");
        assertEquals("TEAM_A", oldA.channel());
        assertEquals("TEAM_B", oldB.channel());
        assertEquals(1, oldA.sequence());
        assertEquals(1, oldB.sequence());
        assertEquals(List.of(oldA), chat.history(roomId, a, 0, 10, false, "TEAM").items());
        assertEquals(List.of(oldB), chat.history(roomId, b, 0, 10, false, "TEAM").items());
        assertTrue(chat.visibleTo(oldA, a));
        assertFalse(chat.visibleTo(oldA, b));
        assertEquals("CHAT_ROOM_NOT_FOUND", assertThrows(ChatFailure.class,
                () -> chat.history(roomId, outsider, 0, 10, false, "TEAM")).code());
        assertEquals("INVALID_CHAT_INPUT", assertThrows(ChatFailure.class,
                () -> chat.send(roomId, b, UUID.randomUUID(), "forged", "TEAM_A")).code());

        room = rooms.selectTeam(roomId, b, "A", room.version());
        assertEquals(2, room.members().stream().filter(member -> member.userId().equals(b.userId()))
                .findFirst().orElseThrow().seat());
        assertTrue(chat.history(roomId, b, 0, 10, false, "TEAM").items().isEmpty());
        assertFalse(chat.visibleTo(oldA, b));
        assertFalse(chat.visibleTo(oldB, b));
        assertEquals(1, chat.history(roomId, b, 0, 10, false, "TEAM").nextSequence());
        assertEquals("CHAT_MESSAGE_CONFLICT", assertThrows(ChatFailure.class,
                () -> chat.send(roomId, b, oldB.clientMessageId(), "B old", "TEAM")).code());
        var newA = chat.send(roomId, b, UUID.randomUUID(), "A new", "TEAM");
        assertEquals(2, newA.sequence());
        assertEquals(List.of(newA), chat.history(roomId, b, 0, 10, false, "TEAM").items());
        assertTrue(chat.visibleTo(newA, b));
        assertFalse(chat.visibleTo(oldA, b));
        assertEquals(List.of(oldA, newA), chat.history(roomId, a, 0, 10, false, "TEAM").items());

        room = rooms.selectTeam(roomId, b, "B", room.version());
        assertTrue(chat.history(roomId, b, 0, 10, false, "TEAM").items().isEmpty());
        assertFalse(chat.visibleTo(newA, b));
        assertEquals(1, chat.history(roomId, b, 0, 10, true, "TEAM").nextSequence());
        assertEquals(List.of(oldA, newA), chat.history(roomId, a, 0, 10, false, "TEAM").items());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM game.chat_messages "
                + "WHERE room_id = ? AND channel = 'TEAM_A'", Integer.class, roomId));
    }

    @Test
    void classicRoomHasNoTeamChannel() {
        GameIdentity host = player("Host");
        RoomView room = rooms.create(host, "CLASSIC", 2);
        assertEquals("INVALID_CHAT_INPUT", assertThrows(ChatFailure.class,
                () -> chat.send(room.id(), host, UUID.randomUUID(), "hidden", "TEAM")).code());
        assertEquals("INVALID_CHAT_INPUT", assertThrows(ChatFailure.class,
                () -> chat.history(room.id(), host, 0, 10, false, "TEAM")).code());
    }

    @Test
    void expiredMessagesAndOrphanedChannelCountersAreRemoved() {
        GameIdentity host = player("Host");
        RoomView room = rooms.create(host, "CLASSIC", 2);
        chat.send(room.id(), host, UUID.randomUUID(), "temporary");
        jdbc.update("UPDATE game.chat_messages SET expires_at = ? WHERE room_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)), room.id());
        rooms.leave(room.id(), host);
        chat.deleteExpired();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM game.chat_messages WHERE room_id = ?",
                Integer.class, room.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM game.chat_channel_sequences WHERE room_id = ?",
                Integer.class, room.id()));
    }

    @Test
    void reportsRequireCurrentVisibilityAndOperatorMuteBlocksNewSends() {
        GameIdentity a1 = player("A1");
        GameIdentity b1 = player("B1");
        GameIdentity a2 = player("A2");
        GameIdentity b2 = player("B2");
        RoomView room = rooms.create(a1, "TEAM_2V2", 4);
        rooms.join(b1, room.code());
        rooms.join(a2, room.code());
        rooms.join(b2, room.code());
        UUID sentId = UUID.randomUUID();
        var sent = chat.send(room.id(), b1, sentId, "reported text", "TEAM");
        assertEquals("CHAT_MESSAGE_NOT_FOUND", assertThrows(ChatFailure.class,
                () -> chat.report(room.id(), a1, sent.id(), "ABUSE")).code());
        assertEquals("INVALID_CHAT_INPUT", assertThrows(ChatFailure.class,
                () -> chat.report(room.id(), b2, sent.id(), "UNKNOWN")).code());
        var report = chat.report(room.id(), b2, sent.id(), "ABUSE");
        assertEquals(report, chat.report(room.id(), b2, sent.id(), "ABUSE"));
        assertEquals("OPEN", report.status());
        assertEquals(b1.userId(), jdbc.queryForObject("SELECT reported_user_id FROM game.chat_reports "
                + "WHERE id = ?", UUID.class, report.id()));
        assertEquals("reported text", jdbc.queryForObject("SELECT content_snapshot FROM game.chat_reports "
                + "WHERE id = ?", String.class, report.id()));
        jdbc.update("UPDATE game.chat_messages SET content = '[消息已移除]', redacted_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), sent.id());
        var redacted = chat.history(room.id(), b2, 0, 10, false, "TEAM").items().get(0);
        assertTrue(redacted.redacted());
        assertEquals("[消息已移除]", redacted.content());
        assertTrue(chat.send(room.id(), b1, sentId, "reported text", "TEAM").redacted());
        assertEquals("reported text", jdbc.queryForObject("SELECT content_snapshot FROM game.chat_reports "
                + "WHERE id = ?", String.class, report.id()));

        var another = chat.send(room.id(), b1, UUID.randomUUID(), "second team text", "TEAM");
        for (int index = 0; index < 19; index++) {
            jdbc.update("INSERT INTO game.chat_reports(id, room_id, message_id, reporter_user_id, "
                    + "reported_user_id, reason, content_snapshot, created_at, expires_at) "
                    + "VALUES (?, ?, ?, ?, ?, 'SPAM', 'snapshot', ?, ?)",
                    UUID.randomUUID(), room.id(), UUID.randomUUID(), b2.userId(), b1.userId(),
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now().plusSeconds(3600)));
        }
        assertEquals("CHAT_RATE_LIMITED", assertThrows(ChatFailure.class,
                () -> chat.report(room.id(), b2, another.id(), "SPAM")).code());

        jdbc.update("INSERT INTO game.chat_mutes(user_id, muted_until, reason, updated_at) "
                + "VALUES (?, ?, ?, ?)", b1.userId(), Timestamp.from(Instant.now().plusSeconds(3600)),
                "Operator review", Timestamp.from(Instant.now()));
        assertEquals("CHAT_MUTED", assertThrows(ChatFailure.class,
                () -> chat.send(room.id(), b1, UUID.randomUUID(), "new", "TEAM")).code());
        assertEquals(sent.id(), chat.send(room.id(), b1, sentId, "reported text", "TEAM").id());
        rooms.leave(room.id(), b2);
        assertEquals("CHAT_ROOM_NOT_FOUND", assertThrows(ChatFailure.class,
                () -> chat.report(room.id(), b2, sent.id(), "ABUSE")).code());
        jdbc.update("UPDATE game.chat_reports SET created_at = ?, expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(172800)),
                Timestamp.from(Instant.now().minusSeconds(86400)), report.id());
        jdbc.update("UPDATE game.chat_mutes SET muted_until = ? WHERE user_id = ?",
                Timestamp.from(Instant.now().minusSeconds(31L * 86400)), b1.userId());
        chat.deleteExpired();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM game.chat_reports WHERE id = ?",
                Integer.class, report.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM game.chat_mutes WHERE user_id = ?",
                Integer.class, b1.userId()));
    }

    private static GameIdentity player(String nickname) {
        return new GameIdentity(UUID.randomUUID(), UUID.randomUUID(), nickname, "APP", Instant.now().plusSeconds(3600));
    }
}
