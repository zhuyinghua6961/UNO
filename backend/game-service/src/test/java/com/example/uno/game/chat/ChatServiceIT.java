package com.example.uno.game.chat;

import com.example.uno.game.GameApplication;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.rooms.RoomService;
import com.example.uno.game.rooms.RoomView;
import java.sql.Timestamp;
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
        jdbc.update("TRUNCATE game.chat_messages, game.chat_channel_sequences, game.match_commands, "
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

    private static GameIdentity player(String nickname) {
        return new GameIdentity(UUID.randomUUID(), UUID.randomUUID(), nickname, "APP", Instant.now().plusSeconds(3600));
    }
}
