package com.example.uno.game.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.uno.game.GameApplication;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.auth.HttpSessionVerifier;
import com.example.uno.game.matches.MatchService;
import com.example.uno.game.matches.MatchDeadlineWorker;
import com.example.uno.game.matches.MatchCommandInput;
import com.example.uno.game.rooms.RoomService;
import com.example.uno.game.rooms.RoomView;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Instant;
import java.sql.Timestamp;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
class GameWebSocketIT {
    @Container
    static final PostgreSQLContainer database = new PostgreSQLContainer(DockerImageName.parse(
            System.getProperty("uno.postgres.image", "postgres:17-alpine"))
            .asCompatibleSubstituteFor("postgres"));
    private static final String HOST_TOKEN = "H".repeat(43);
    private static final String GUEST_TOKEN = "G".repeat(43);
    private static final String OUTSIDER_TOKEN = "O".repeat(43);
    private static final String WEB_TOKEN = "W".repeat(43);
    private static final Map<String, GameIdentity> sessions = new ConcurrentHashMap<>();
    private static final JsonMapper json = new JsonMapper();
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
        application = new SpringApplicationBuilder(
                GameApplication.class, FakeIdentityService.class).run(
                "--server.port=0", "--spring.datasource.url=" + database.getJdbcUrl(),
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword(),
                "--uno.auth.enabled=true", "--uno.auth.secure-cookies=false",
                "--uno.auth.allowed-origins=http://localhost:5173",
                "--uno.auth.identity-base-url=http://localhost:1",
                "--uno.auth.allow-insecure-http=true",
                "--uno.auth.service-key=" + "a".repeat(64));
        endpoint = URI.create("ws://localhost:" + application.getEnvironment().getProperty("local.server.port")
                + "/ws/game");
    }

    @AfterAll
    static void stop() { if (application != null) application.close(); }

    @Test
    void liveConnectionsGetOnlyOwnViewsAndRetriesDoNotReplayCommands() throws Exception {
        GameIdentity host = player("Host");
        GameIdentity guest = player("Guest");
        GameIdentity outsider = player("Outsider");
        sessions.put(HOST_TOKEN, host);
        sessions.put(GUEST_TOKEN, guest);
        sessions.put(OUTSIDER_TOKEN, outsider);
        RoomService rooms = application.getBean(RoomService.class);
        MatchService matches = application.getBean(MatchService.class);
        RoomView room = rooms.create(host, "CLASSIC", 2);
        room = rooms.join(guest, room.code());
        room = rooms.ready(room.id(), host, true, room.version());
        room = rooms.ready(room.id(), guest, true, room.version());
        UUID matchId = matches.start(room.id(), host, room.version()).matchId();
        Peer hostPeer = connect(HOST_TOKEN);
        Peer guestPeer = connect(GUEST_TOKEN);
        Peer outsiderPeer = connect(OUTSIDER_TOKEN);
        try {
            String subscribe = json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "SUBSCRIBE", "matchId", matchId));
            hostPeer.socket.sendText(subscribe, true).join();
            guestPeer.socket.sendText(subscribe, true).join();
            outsiderPeer.socket.sendText(subscribe, true).join();
            JsonNode hostSnapshot = hostPeer.nextMessage();
            JsonNode guestSnapshot = guestPeer.nextMessage();
            assertEquals("MATCH_SNAPSHOT", hostSnapshot.path("type").asText());
            assertEquals("MATCH_SNAPSHOT", guestSnapshot.path("type").asText());
            assertEquals(1, hostSnapshot.path("protocolVersion").asInt());
            assertFalse(hostSnapshot.path("deadlineAt").isMissingNode());
            assertEquals("MATCH_NOT_FOUND", outsiderPeer.nextMessage().path("code").asText());
            assertTrue(hostSnapshot.path("view").path("ownHand").size() >= 7);
            assertTrue(guestSnapshot.path("view").path("ownHand").size() >= 7);
            assertNotEquals(hostSnapshot.path("view").path("ownHand"),
                    guestSnapshot.path("view").path("ownHand"));
            assertFalse(hostSnapshot.toString().contains("drawPile"));

            JsonNode hostView = hostSnapshot.path("view");
            boolean hostActs = hostView.path("players").get(hostView.path("currentSeat").asInt())
                    .path("userId").asText().equals(host.userId().toString());
            Peer actor = hostActs ? hostPeer : guestPeer;
            Peer other = hostActs ? guestPeer : hostPeer;
            boolean chooseColor = hostView.path("phase").asText().equals("INITIAL_WILD_COLOR");
            UUID commandId = UUID.randomUUID();
            java.util.HashMap<String, Object> action = new java.util.HashMap<>(Map.of(
                    "protocolVersion", 1, "commandId", commandId, "expectedVersion", 1,
                    "type", chooseColor ? "CHOOSE_INITIAL_COLOR" : "DRAW"));
            if (chooseColor) action.put("chosenColor", "RED");
            assertDoesNotThrow(() -> json.readValue(json.writeValueAsString(action), MatchCommandInput.class));
            String command = json.writeValueAsString(Map.of("protocolVersion", 1, "type", "COMMAND",
                    "matchId", matchId, "command", action));
            actor.socket.sendText(command, true).join();
            JsonNode ack = actor.nextMessage();
            assertEquals("COMMAND_ACK", ack.path("type").asText(), ack.toString());
            assertFalse(ack.path("result").path("duplicate").asBoolean());
            assertEquals(2, ack.path("result").path("appliedVersion").asLong());
            JsonNode updated = other.nextMessage();
            assertEquals("MATCH_SNAPSHOT", updated.path("type").asText());
            assertEquals(2, updated.path("view").path("version").asLong());
            actor.socket.sendText(command, true).join();
            assertTrue(actor.nextMessage().path("result").path("duplicate").asBoolean());
            assertNull(other.messages.poll(300, TimeUnit.MILLISECONDS));

            application.getBean(JdbcTemplate.class).update(
                    "UPDATE game.matches SET deadline_at = ? WHERE id = ?",
                    Timestamp.from(Instant.now().minusSeconds(1)), matchId);
            application.getBean(MatchDeadlineWorker.class).resolveDueMatches();
            JsonNode hostTimeout = hostPeer.nextMessage();
            JsonNode guestTimeout = guestPeer.nextMessage();
            assertEquals("MATCH_SNAPSHOT", hostTimeout.path("type").asText());
            assertEquals("MATCH_SNAPSHOT", guestTimeout.path("type").asText());
            long timeoutVersion = hostTimeout.path("view").path("version").asLong();
            assertTrue(timeoutVersion > 2);
            assertEquals(timeoutVersion, guestTimeout.path("view").path("version").asLong());

            Peer reconnected = connect(GUEST_TOKEN);
            try {
                reconnected.socket.sendText(subscribe, true).join();
                JsonNode restored = reconnected.nextMessage();
                assertEquals("MATCH_SNAPSHOT", restored.path("type").asText());
                assertEquals(timeoutVersion, restored.path("view").path("version").asLong());
                assertEquals(guest.userId().toString(), restored.path("view").path("players").get(1)
                        .path("userId").asText());
            } finally {
                reconnected.socket.abort();
            }

            sessions.remove(GUEST_TOKEN);
            application.getBean(GameWebSocketHandler.class).revalidateConnections();
            assertEquals(1008, guestPeer.nextClose());
        } finally {
            hostPeer.socket.abort();
            guestPeer.socket.abort();
            outsiderPeer.socket.abort();
            sessions.clear();
        }
    }

    @Test
    void browserCookieRequiresAllowedOrigin() throws Exception {
        GameIdentity webHost = new GameIdentity(UUID.randomUUID(), UUID.randomUUID(), "WebHost", "WEB",
                Instant.now().plusSeconds(3600));
        GameIdentity guest = player("Guest");
        sessions.put(WEB_TOKEN, webHost);
        sessions.put(GUEST_TOKEN, guest);
        RoomService rooms = application.getBean(RoomService.class);
        MatchService matches = application.getBean(MatchService.class);
        RoomView room = rooms.create(webHost, "CLASSIC", 2);
        room = rooms.join(guest, room.code());
        room = rooms.ready(room.id(), webHost, true, room.version());
        room = rooms.ready(room.id(), guest, true, room.version());
        UUID matchId = matches.start(room.id(), webHost, room.version()).matchId();
        Peer webPeer = new Peer();
        webPeer.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Origin", "http://localhost:5173")
                .header("Cookie", "UNO_SESSION_DEV=" + WEB_TOKEN)
                .buildAsync(endpoint, webPeer).join();
        try {
            webPeer.socket.sendText(json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "SUBSCRIBE", "matchId", matchId)), true).join();
            assertEquals("MATCH_SNAPSHOT", webPeer.nextMessage().path("type").asText());
            assertThrows(java.util.concurrent.CompletionException.class, () ->
                    HttpClient.newHttpClient().newWebSocketBuilder()
                            .header("Origin", "http://evil.example")
                            .header("Cookie", "UNO_SESSION_DEV=" + WEB_TOKEN)
                            .buildAsync(endpoint, new Peer()).join());
        } finally {
            webPeer.socket.abort();
            sessions.clear();
        }
    }

    private static GameIdentity player(String name) {
        return new GameIdentity(UUID.randomUUID(), UUID.randomUUID(), name, "APP", Instant.now().plusSeconds(3600));
    }

    private Peer connect(String token) {
        Peer peer = new Peer();
        peer.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("X-UNO-Client", "APP").header("Authorization", "Bearer " + token)
                .buildAsync(endpoint, peer).join();
        return peer;
    }

    private static final class Peer implements WebSocket.Listener {
        WebSocket socket;
        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<Integer> closes = new LinkedBlockingQueue<>();
        final StringBuilder pending = new StringBuilder();

        JsonNode nextMessage() throws InterruptedException {
            String value = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(value, "Timed out waiting for WebSocket message");
            return json.readTree(value);
        }

        int nextClose() throws InterruptedException {
            Integer status = closes.poll(5, TimeUnit.SECONDS);
            assertNotNull(status, "Timed out waiting for WebSocket close");
            return status;
        }

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            pending.append(data);
            if (last) { messages.add(pending.toString()); pending.setLength(0); }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closes.add(statusCode);
            return CompletableFuture.completedFuture(null);
        }
    }
}
