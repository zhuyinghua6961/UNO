package com.example.uno.game.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.uno.game.GameApplication;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.auth.HttpSessionVerifier;
import com.example.uno.game.chat.ChatService;
import com.example.uno.game.rooms.RoomService;
import com.example.uno.game.rooms.RoomView;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class ChatWebSocketIT {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine"))
            .asCompatibleSubstituteFor("postgres"));
    private static final JsonMapper json = new JsonMapper();
    private static final Map<String, GameIdentity> sessions = new ConcurrentHashMap<>();
    private static ConfigurableApplicationContext application;
    private static URI endpoint;

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeIdentityService {
        @Bean @Primary
        HttpSessionVerifier testSessionVerifier() {
            HttpSessionVerifier fake = org.mockito.Mockito.mock(HttpSessionVerifier.class);
            when(fake.verify(anyString(), anyString())).thenAnswer(invocation -> {
                GameIdentity identity = sessions.get(invocation.getArgument(0));
                return identity != null && identity.clientType().equals(invocation.getArgument(1))
                        ? Optional.of(identity) : Optional.empty();
            });
            return fake;
        }
    }

    @BeforeAll
    static void start() {
        application = startInstance();
        endpoint = endpointFor(application);
    }

    private static ConfigurableApplicationContext startInstance() {
        return new SpringApplicationBuilder(GameApplication.class, FakeIdentityService.class).run(
                "--server.port=0", "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--uno.auth.enabled=true", "--uno.auth.secure-cookies=false",
                "--uno.auth.allowed-origins=http://localhost:5173",
                "--uno.auth.identity-base-url=http://localhost:1",
                "--uno.auth.allow-insecure-http=true",
                "--uno.chat.message-poll-ms=60000",
                "--uno.auth.service-key=" + "a".repeat(64));
    }

    private static URI endpointFor(ConfigurableApplicationContext context) {
        return URI.create("ws://localhost:" + context.getEnvironment().getProperty("local.server.port")
                + "/ws/chat");
    }

    @AfterAll
    static void stop() { if (application != null) application.close(); }

    @Test
    void liveTeamAndRoomMessagesRespectMembershipForWebSocketAndHttp() throws Exception {
        GameIdentity a1 = player("A1");
        GameIdentity b1 = player("B1");
        GameIdentity a2 = player("A2");
        GameIdentity b2 = player("B2");
        GameIdentity outsider = player("Outsider");
        sessions.put("a", a1);
        sessions.put("b", b1);
        sessions.put("c", a2);
        sessions.put("d", b2);
        sessions.put("o", outsider);
        RoomService rooms = application.getBean(RoomService.class);
        RoomView room = rooms.create(a1, "TEAM_2V2", 4);
        room = rooms.join(b1, room.code());
        room = rooms.join(a2, room.code());
        room = rooms.join(b2, room.code());
        UUID roomId = room.id();
        Peer pA1 = connect("a");
        Peer pB1 = connect("b");
        Peer pA2 = connect("c");
        Peer pB2 = connect("d");
        Peer pOutsider = connect("o");
        try {
            String subscribe = json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "SUBSCRIBE", "roomId", roomId));
            for (Peer peer : List.of(pA1, pB1, pA2, pB2, pOutsider))
                peer.socket.sendText(subscribe, true).join();
            for (Peer peer : List.of(pA1, pB1, pA2, pB2))
                assertEquals("CHAT_SUBSCRIBED", peer.nextMessage().path("type").asText());
            assertEquals("CHAT_ROOM_NOT_FOUND", pOutsider.nextMessage().path("code").asText());

            UUID teamId = UUID.randomUUID();
            pB1.socket.sendText(json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "CHAT_SEND", "roomId", roomId,
                    "clientMessageId", teamId, "channel", "TEAM", "content", "B team only")), true).join();
            JsonNode bEvent = pB1.nextMessage();
            assertEquals("CHAT_MESSAGE", bEvent.path("type").asText());
            assertEquals("TEAM_B", bEvent.path("item").path("channel").asText());
            assertEquals(b1.userId().toString(), bEvent.path("item").path("senderUserId").asText());
            assertEquals(teamId.toString(), bEvent.path("item").path("clientMessageId").asText());
            assertEquals(1, bEvent.path("item").path("sequence").asLong());
            assertNotNull(Instant.parse(bEvent.path("item").path("createdAt").asText()));
            assertEquals("CHAT_ACK", pB1.nextMessage().path("type").asText());
            assertEquals(bEvent.path("item"), pB2.nextMessage().path("item"));
            for (Peer peer : List.of(pA1, pA2, pOutsider))
                assertNull(peer.messages.poll(300, TimeUnit.MILLISECONDS));

            // Simulate a commit on another instance, which has no access to this handler's publish hook.
            ChatService chat = application.getBean(ChatService.class);
            ChatWebSocketHandler handler = application.getBean(ChatWebSocketHandler.class);
            UUID remoteId = UUID.randomUUID();
            chat.send(roomId, a2, remoteId, "A team from another instance", "TEAM");
            handler.pollSubscriptions();
            for (Peer peer : List.of(pA1, pA2)) {
                JsonNode remote = peer.nextMessage();
                assertEquals("CHAT_MESSAGE", remote.path("type").asText());
                assertEquals(remoteId.toString(), remote.path("item").path("clientMessageId").asText());
                assertEquals("TEAM_A", remote.path("item").path("channel").asText());
            }
            for (Peer peer : List.of(pB1, pB2, pOutsider))
                assertNull(peer.messages.poll(200, TimeUnit.MILLISECONDS));
            handler.pollSubscriptions();
            for (Peer peer : List.of(pA1, pA2, pB1, pB2))
                assertNull(peer.messages.poll(200, TimeUnit.MILLISECONDS));

            ChatService.ChatItem first = chat.send(roomId, a1, UUID.randomUUID(), "ordered one", "ROOM");
            ChatService.ChatItem second = chat.send(roomId, b2, UUID.randomUUID(), "ordered two", "ROOM");
            handler.publish(second); // A later local publication must not skip an earlier remote commit.
            for (Peer peer : List.of(pA1, pB1, pA2, pB2))
                assertNull(peer.messages.poll(100, TimeUnit.MILLISECONDS));
            handler.pollSubscriptions();
            for (Peer peer : List.of(pA1, pB1, pA2, pB2)) {
                assertEquals(first.id().toString(), peer.nextMessage().path("item").path("id").asText());
                assertEquals(second.id().toString(), peer.nextMessage().path("item").path("id").asText());
            }

            URI reportEndpoint = URI.create("http://localhost:" + endpoint.getPort() + "/api/rooms/"
                    + roomId + "/messages/" + bEvent.path("item").path("id").asText() + "/reports");
            HttpRequest report = HttpRequest.newBuilder(reportEndpoint)
                    .header("X-UNO-Client", "APP").header("Authorization", "Bearer d")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"ABUSE\"}"))
                    .build();
            assertEquals(200, HttpClient.newHttpClient().send(report, HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpRequest wrongTeamReport = HttpRequest.newBuilder(reportEndpoint)
                    .header("X-UNO-Client", "APP").header("Authorization", "Bearer a")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"ABUSE\"}"))
                    .build();
            assertEquals(404, HttpClient.newHttpClient().send(wrongTeamReport,
                    HttpResponse.BodyHandlers.ofString()).statusCode());

            UUID roomMessageId = UUID.randomUUID();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + endpoint.getPort()
                            + "/api/rooms/" + roomId + "/messages"))
                    .header("X-UNO-Client", "APP").header("Authorization", "Bearer a")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                            "clientMessageId", roomMessageId, "channel", "ROOM", "content", "Room hello"))))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            for (Peer peer : List.of(pA1, pB1, pA2, pB2)) {
                JsonNode event = peer.nextMessage();
                assertEquals("CHAT_MESSAGE", event.path("type").asText());
                assertEquals("ROOM", event.path("item").path("channel").asText());
                assertEquals(roomMessageId.toString(), event.path("item").path("clientMessageId").asText());
            }
            assertNull(pOutsider.messages.poll(300, TimeUnit.MILLISECONDS));

            pA1.socket.sendText(json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "CHAT_SEND", "roomId", roomId,
                    "clientMessageId", UUID.randomUUID(), "channel", "TEAM", "content", "forged",
                    "senderUserId", b1.userId())), true).join();
            assertEquals("BAD_MESSAGE", pA1.nextMessage().path("code").asText());
            assertNull(pB1.messages.poll(300, TimeUnit.MILLISECONDS));

            rooms.leave(roomId, b2);
            pB1.socket.sendText(json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "CHAT_SEND", "roomId", roomId,
                    "clientMessageId", UUID.randomUUID(), "channel", "TEAM", "content", "after leave")), true).join();
            assertEquals("CHAT_MESSAGE", pB1.nextMessage().path("type").asText());
            assertEquals("CHAT_ACK", pB1.nextMessage().path("type").asText());
            assertNull(pB2.messages.poll(300, TimeUnit.MILLISECONDS));

            sessions.remove("b");
            handler.revalidateConnections();
            assertEquals(1008, pB1.nextClose());
        } finally {
            for (Peer peer : List.of(pA1, pB1, pA2, pB2, pOutsider)) peer.socket.abort();
            sessions.clear();
        }
    }

    @Test
    void pollingResetsTeamCursorAfterSeatChangeWithoutRevealingOldTeamHistory() throws Exception {
        GameIdentity a1 = player("Team host");
        GameIdentity b1 = player("Team guest B");
        GameIdentity a2 = player("Team guest A");
        sessions.put("ta", a1);
        sessions.put("tb", b1);
        sessions.put("tc", a2);
        RoomService rooms = application.getBean(RoomService.class);
        RoomView room = rooms.create(a1, "TEAM_2V2", 4);
        room = rooms.join(b1, room.code());
        room = rooms.join(a2, room.code());
        UUID roomId = room.id();
        ChatService chat = application.getBean(ChatService.class);
        ChatWebSocketHandler handler = application.getBean(ChatWebSocketHandler.class);
        UUID oldB = UUID.randomUUID();
        chat.send(roomId, b1, oldB, "old B message", "TEAM");
        Peer pA1 = connect("ta");
        Peer pB1 = connect("tb");
        Peer pA2 = connect("tc");
        try {
            String subscribe = json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "SUBSCRIBE", "roomId", roomId));
            for (Peer peer : List.of(pA1, pB1, pA2)) {
                peer.socket.sendText(subscribe, true).join();
                assertEquals("CHAT_SUBSCRIBED", peer.nextMessage().path("type").asText());
            }
            UUID aMessage = UUID.randomUUID();
            chat.send(roomId, a1, aMessage, "A before switch", "TEAM");
            handler.pollSubscriptions();
            for (Peer peer : List.of(pA1, pA2))
                assertEquals(aMessage.toString(), peer.nextMessage().path("item").path("clientMessageId").asText());
            assertNull(pB1.messages.poll(200, TimeUnit.MILLISECONDS));

            rooms.selectTeam(roomId, a2, "B", room.version());
            handler.pollSubscriptions();
            assertNull(pA2.messages.poll(200, TimeUnit.MILLISECONDS), "Old B history must stay hidden");

            UUID newB = UUID.randomUUID();
            chat.send(roomId, a2, newB, "new B message", "TEAM");
            handler.pollSubscriptions();
            for (Peer peer : List.of(pB1, pA2))
                assertEquals(newB.toString(), peer.nextMessage().path("item").path("clientMessageId").asText());
            assertNull(pA1.messages.poll(200, TimeUnit.MILLISECONDS));
        } finally {
            for (Peer peer : List.of(pA1, pB1, pA2)) peer.socket.abort();
            sessions.clear();
        }
    }

    @Test
    void separateGameInstancesDeliverCommittedTeamMessageToRemoteSubscriber() throws Exception {
        GameIdentity a1 = player("Instance A");
        GameIdentity b1 = player("Instance B sender");
        GameIdentity a2 = player("Instance A peer");
        GameIdentity b2 = player("Instance B peer");
        sessions.put("ia", a1);
        sessions.put("ib", b1);
        sessions.put("ic", b2);
        RoomService rooms = application.getBean(RoomService.class);
        RoomView room = rooms.create(a1, "TEAM_2V2", 4);
        room = rooms.join(b1, room.code());
        room = rooms.join(a2, room.code());
        room = rooms.join(b2, room.code());
        UUID roomId = room.id();
        try (ConfigurableApplicationContext second = startInstance()) {
            URI remoteEndpoint = endpointFor(second);
            Peer pA1 = connect("ia", remoteEndpoint);
            Peer pB2 = connect("ic", remoteEndpoint);
            try {
                String subscribe = json.writeValueAsString(Map.of(
                        "protocolVersion", 1, "type", "SUBSCRIBE", "roomId", roomId));
                for (Peer peer : List.of(pA1, pB2)) {
                    peer.socket.sendText(subscribe, true).join();
                    assertEquals("CHAT_SUBSCRIBED", peer.nextMessage().path("type").asText());
                }
                UUID messageId = UUID.randomUUID();
                application.getBean(ChatService.class).send(roomId, b1, messageId,
                        "from the other Game instance", "TEAM");
                ChatWebSocketHandler remoteHandler = second.getBean(ChatWebSocketHandler.class);
                remoteHandler.pollSubscriptions();
                JsonNode event = pB2.nextMessage();
                assertEquals(messageId.toString(), event.path("item").path("clientMessageId").asText());
                assertEquals("TEAM_B", event.path("item").path("channel").asText());
                assertNull(pA1.messages.poll(200, TimeUnit.MILLISECONDS));
                remoteHandler.pollSubscriptions();
                assertNull(pB2.messages.poll(200, TimeUnit.MILLISECONDS));
            } finally {
                pA1.socket.abort();
                pB2.socket.abort();
            }
        } finally {
            sessions.clear();
        }
    }

    private static GameIdentity player(String name) {
        return new GameIdentity(UUID.randomUUID(), UUID.randomUUID(), name, "APP", Instant.now().plusSeconds(3600));
    }

    private static Peer connect(String token) {
        return connect(token, endpoint);
    }

    private static Peer connect(String token, URI socketEndpoint) {
        Peer peer = new Peer();
        peer.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("X-UNO-Client", "APP").header("Authorization", "Bearer " + token)
                .buildAsync(socketEndpoint, peer).join();
        return peer;
    }

    private static final class Peer implements WebSocket.Listener {
        WebSocket socket;
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> closes = new LinkedBlockingQueue<>();
        final StringBuilder pending = new StringBuilder();

        JsonNode nextMessage() throws InterruptedException {
            String value = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(value, "Timed out waiting for chat WebSocket message");
            return json.readTree(value);
        }

        int nextClose() throws InterruptedException {
            Integer code = closes.poll(5, TimeUnit.SECONDS);
            assertNotNull(code, "Timed out waiting for chat WebSocket close");
            return code;
        }

        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            pending.append(data);
            if (last) { messages.add(pending.toString()); pending.setLength(0); }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
            closes.add(statusCode);
            return CompletableFuture.completedFuture(null);
        }
    }
}
