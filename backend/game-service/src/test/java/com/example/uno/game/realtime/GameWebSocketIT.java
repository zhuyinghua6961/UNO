package com.example.uno.game.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.example.uno.game.GameApplication;
import com.example.uno.core.rules.UnoCard;
import com.example.uno.core.rules.UnoSnapshot;
import com.example.uno.core.rules.UnoState;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.auth.HttpSessionVerifier;
import com.example.uno.game.matches.MatchService;
import com.example.uno.game.matches.MatchDeadlineWorker;
import com.example.uno.game.matches.MatchCommandInput;
import com.example.uno.game.rooms.RoomService;
import com.example.uno.game.rooms.RoomView;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
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
import java.util.Random;
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
                "--uno.matches.snapshot-poll-ms=60000",
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
                Peer.CloseEvent displaced = guestPeer.nextCloseEvent();
                assertEquals(4001, displaced.statusCode());
                assertEquals("TAKEN_OVER", displaced.reason());
                sessions.remove(GUEST_TOKEN);
                application.getBean(GameWebSocketHandler.class).revalidateConnections();
                assertEquals(1008, reconnected.nextClose());
            } finally {
                reconnected.socket.abort();
            }
        } finally {
            hostPeer.socket.abort();
            guestPeer.socket.abort();
            outsiderPeer.socket.abort();
            sessions.clear();
        }
    }

    @Test
    void nativeHttpDepartureBroadcastsReasonToBothPlayers() throws Exception {
        GameIdentity host = player("LeavingHost");
        GameIdentity guest = player("WaitingGuest");
        sessions.put(HOST_TOKEN, host);
        sessions.put(GUEST_TOKEN, guest);
        RoomService rooms = application.getBean(RoomService.class);
        MatchService matches = application.getBean(MatchService.class);
        RoomView room = rooms.create(host, "CLASSIC", 2);
        room = rooms.join(guest, room.code());
        room = rooms.ready(room.id(), host, true, room.version());
        room = rooms.ready(room.id(), guest, true, room.version());
        UUID matchId = matches.start(room.id(), host, room.version()).matchId();
        Peer hostPeer = connect(HOST_TOKEN);
        Peer guestPeer = connect(GUEST_TOKEN);
        try {
            String subscribe = json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "SUBSCRIBE", "matchId", matchId));
            hostPeer.socket.sendText(subscribe, true).join();
            guestPeer.socket.sendText(subscribe, true).join();
            assertEquals("PLAYING", hostPeer.nextMessage().path("status").asText());
            assertEquals("PLAYING", guestPeer.nextMessage().path("status").asText());

            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:"
                            + application.getEnvironment().getProperty("local.server.port")
                            + "/api/matches/" + matchId + "/leave"))
                    .header("X-UNO-Client", "APP")
                    .header("Authorization", "Bearer " + HOST_TOKEN)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals("PLAYER_LEFT", json.readTree(response.body()).path("interruptionReason").asText());
            for (Peer peer : List.of(hostPeer, guestPeer)) {
                JsonNode notice = peer.nextMessage();
                assertEquals("MATCH_SNAPSHOT", notice.path("type").asText());
                assertEquals("INTERRUPTED", notice.path("status").asText());
                assertEquals("PLAYER_LEFT", notice.path("interruptionReason").asText());
                assertTrue(notice.path("deadlineAt").isNull());
            }
            assertEquals("WAITING", rooms.get(room.id(), guest).state());
            assertNull(rooms.current(host));
        } finally {
            hostPeer.socket.abort();
            guestPeer.socket.abort();
            sessions.clear();
        }
    }

    @Test
    void databasePollingRepairsAnUpdateCommittedOutsideThisSocketHandler() throws Exception {
        GameIdentity host = player("PollHost");
        GameIdentity guest = player("PollGuest");
        sessions.put(HOST_TOKEN, host);
        sessions.put(GUEST_TOKEN, guest);
        RoomService rooms = application.getBean(RoomService.class);
        MatchService matches = application.getBean(MatchService.class);
        RoomView room = rooms.create(host, "CLASSIC", 2);
        room = rooms.join(guest, room.code());
        room = rooms.ready(room.id(), host, true, room.version());
        room = rooms.ready(room.id(), guest, true, room.version());
        UUID matchId = matches.start(room.id(), host, room.version()).matchId();
        Peer hostPeer = connect(HOST_TOKEN);
        Peer guestPeer = connect(GUEST_TOKEN);
        try {
            String subscribe = json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "SUBSCRIBE", "matchId", matchId));
            hostPeer.socket.sendText(subscribe, true).join();
            guestPeer.socket.sendText(subscribe, true).join();
            JsonNode initial = hostPeer.nextMessage();
            assertEquals(1, initial.path("view").path("version").asInt());
            assertEquals(1, guestPeer.nextMessage().path("view").path("version").asInt());

            GameIdentity actor = initial.path("view").path("players")
                    .get(initial.path("view").path("currentSeat").asInt())
                    .path("userId").asText().equals(host.userId().toString()) ? host : guest;
            boolean chooseColor = initial.path("view").path("phase").asText().equals("INITIAL_WILD_COLOR");
            matches.command(matchId, actor, new MatchCommandInput(1, UUID.randomUUID(), 1,
                    chooseColor ? MatchCommandInput.Type.CHOOSE_INITIAL_COLOR : MatchCommandInput.Type.DRAW,
                    null, chooseColor ? UnoCard.Color.RED : null, null, false));
            // No controller or local WebSocket publish: the state changed via another instance.
            GameWebSocketHandler handler = application.getBean(GameWebSocketHandler.class);
            handler.pollSubscriptions();
            for (Peer peer : List.of(hostPeer, guestPeer)) {
                JsonNode updated = peer.nextMessage();
                assertEquals("MATCH_SNAPSHOT", updated.path("type").asText());
                assertEquals(2, updated.path("view").path("version").asInt());
            }
            handler.pollSubscriptions();
            assertNull(hostPeer.messages.poll(200, TimeUnit.MILLISECONDS));
            assertNull(guestPeer.messages.poll(200, TimeUnit.MILLISECONDS));
            matches.leave(matchId, guest);
            handler.pollSubscriptions();
            for (Peer peer : List.of(hostPeer, guestPeer)) {
                JsonNode interrupted = peer.nextMessage();
                assertEquals(2, interrupted.path("view").path("version").asInt());
                assertEquals("INTERRUPTED", interrupted.path("status").asText());
                assertEquals("PLAYER_LEFT", interrupted.path("interruptionReason").asText());
            }
        } finally {
            hostPeer.socket.abort();
            guestPeer.socket.abort();
            sessions.clear();
        }
    }

    @Test
    void repeatedTimeoutsBroadcastAnInterruptedStatusWithoutAFalseWinner() throws Exception {
        GameIdentity host = player("InterruptedHost");
        GameIdentity guest = player("InterruptedGuest");
        sessions.put(HOST_TOKEN, host);
        sessions.put(GUEST_TOKEN, guest);
        RoomService rooms = application.getBean(RoomService.class);
        MatchService matches = application.getBean(MatchService.class);
        RoomView room = rooms.create(host, "CLASSIC", 2);
        room = rooms.join(guest, room.code());
        room = rooms.ready(room.id(), host, true, room.version());
        room = rooms.ready(room.id(), guest, true, room.version());
        UUID matchId = matches.start(room.id(), host, room.version()).matchId();
        Peer hostPeer = connect(HOST_TOKEN);
        Peer guestPeer = connect(GUEST_TOKEN);
        try {
            String subscribe = json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "SUBSCRIBE", "matchId", matchId));
            hostPeer.socket.sendText(subscribe, true).join();
            guestPeer.socket.sendText(subscribe, true).join();
            assertEquals("PLAYING", hostPeer.nextMessage().path("status").asText());
            assertEquals("PLAYING", guestPeer.nextMessage().path("status").asText());
            JsonNode latest = null;
            for (int missed = 0; missed < 6; missed++) {
                application.getBean(JdbcTemplate.class).update("UPDATE game.matches SET deadline_at = ? WHERE id = ?",
                        Timestamp.from(Instant.now().minusSeconds(1)), matchId);
                application.getBean(MatchDeadlineWorker.class).resolveDueMatches();
                latest = hostPeer.nextMessage();
                JsonNode other = guestPeer.nextMessage();
                assertEquals(latest.path("status").asText(), other.path("status").asText());
                if ("INTERRUPTED".equals(latest.path("status").asText())) break;
            }
            assertNotNull(latest);
            assertEquals("INTERRUPTED", latest.path("status").asText());
            assertTrue(latest.path("deadlineAt").isNull());
            assertEquals("WAITING", rooms.get(room.id(), host).state());
        } finally {
            hostPeer.socket.abort();
            guestPeer.socket.abort();
            sessions.clear();
        }
    }

    @Test
    void rateLimitedConnectionProvidesARecoverableCloseReason() throws Exception {
        sessions.put(HOST_TOKEN, player("RateLimited"));
        Peer peer = connect(HOST_TOKEN);
        try {
            String invalid = "{\"protocolVersion\":1,\"type\":\"SUBSCRIBE\"}";
            for (int attempt = 0; attempt < 30; attempt++) {
                peer.socket.sendText(invalid, true).join();
                assertEquals("BAD_MESSAGE", peer.nextMessage().path("code").asText());
            }
            peer.socket.sendText(invalid, true).join();
            Peer.CloseEvent close = peer.nextCloseEvent();
            assertEquals(1008, close.statusCode());
            assertEquals("RATE_LIMITED", close.reason());
        } finally {
            peer.socket.abort();
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

    @Test
    void nativeBearerAllowsOnlyGatewayUpstreamOrigin() {
        sessions.put(HOST_TOKEN, player("AppHost"));
        String sameOrigin = "http://" + endpoint.getHost() + ":" + endpoint.getPort();
        Peer nativePeer = new Peer();
        nativePeer.socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .header("X-UNO-Client", "APP")
                .header("Authorization", "Bearer " + HOST_TOKEN)
                .header("Origin", sameOrigin)
                .buildAsync(endpoint, nativePeer).join();
        try {
            assertThrows(java.util.concurrent.CompletionException.class, () ->
                    HttpClient.newHttpClient().newWebSocketBuilder()
                            .header("X-UNO-Client", "APP")
                            .header("Authorization", "Bearer " + HOST_TOKEN)
                            .header("Origin", "http://evil.example")
                            .buildAsync(endpoint, new Peer()).join());
        } finally {
            nativePeer.socket.abort();
            sessions.clear();
        }
    }

    @Test
    void finalPlayThroughTwoSocketsPersistsWinnerAndReturnsRoomToWaiting() throws Exception {
        GameIdentity host = player("FinalHost");
        GameIdentity guest = player("FinalGuest");
        sessions.put(HOST_TOKEN, host);
        sessions.put(GUEST_TOKEN, guest);
        RoomService rooms = application.getBean(RoomService.class);
        MatchService matches = application.getBean(MatchService.class);
        JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
        RoomView room = rooms.create(host, "CLASSIC", 2);
        room = rooms.join(guest, room.code());
        room = rooms.ready(room.id(), host, true, room.version());
        room = rooms.ready(room.id(), guest, true, room.version());
        UUID matchId = matches.start(room.id(), host, room.version()).matchId();
        UnoSnapshot original = json.readValue(jdbc.queryForObject(
                "SELECT snapshot::text FROM game.matches WHERE id = ?", String.class, matchId), UnoSnapshot.class);
        List<List<Integer>> hands = new ArrayList<>();
        original.hands().forEach(hand -> hands.add(new ArrayList<>(hand)));
        List<Integer> pile = new ArrayList<>(original.drawPile());
        UnoCard.Color color = original.activeColor() == null ? UnoCard.Color.RED : original.activeColor();
        int lastCard = pile.stream().filter(id -> {
            UnoCard card = UnoCard.of(id);
            return card.kind() == UnoCard.Kind.NUMBER && card.color() == color;
        }).findFirst().orElseThrow();
        pile.remove(Integer.valueOf(lastCard));
        pile.addAll(hands.get(original.currentSeat()));
        hands.get(original.currentSeat()).clear();
        hands.get(original.currentSeat()).add(lastCard);
        List<Integer> scores = new ArrayList<>(List.of(0, 0));
        scores.set(original.currentSeat(), 499);
        UnoSnapshot prepared = new UnoSnapshot(original.players(), hands, pile, original.discardPile(),
                scores, original.dealerSeat(), original.currentSeat(), original.direction(),
                original.roundNumber(), 1, UnoState.Phase.TURN, color, null, null, null, null, 0);
        UnoState.restore(prepared);
        jdbc.update("UPDATE game.matches SET snapshot = CAST(? AS jsonb) WHERE id = ?",
                json.writeValueAsString(prepared), matchId);

        Peer hostPeer = connect(HOST_TOKEN);
        Peer guestPeer = connect(GUEST_TOKEN);
        try {
            String subscribe = json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "SUBSCRIBE", "matchId", matchId));
            hostPeer.socket.sendText(subscribe, true).join();
            guestPeer.socket.sendText(subscribe, true).join();
            assertEquals("MATCH_SNAPSHOT", hostPeer.nextMessage().path("type").asText());
            assertEquals("MATCH_SNAPSHOT", guestPeer.nextMessage().path("type").asText());
            Peer actor = prepared.players().get(prepared.currentSeat()).equals(host.userId()) ? hostPeer : guestPeer;
            Peer other = actor == hostPeer ? guestPeer : hostPeer;
            UUID commandId = UUID.randomUUID();
            String command = json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "COMMAND", "matchId", matchId,
                    "command", Map.of("protocolVersion", 1, "commandId", commandId,
                            "expectedVersion", 1, "type", "PLAY", "cardId", lastCard,
                            "callUno", false)));
            actor.socket.sendText(command, true).join();
            JsonNode ack = actor.nextMessage();
            JsonNode opponent = other.nextMessage();
            assertEquals("COMMAND_ACK", ack.path("type").asText(), ack.toString());
            assertEquals("MATCH_OVER", ack.path("result").path("view").path("phase").asText());
            assertEquals(prepared.currentSeat(), ack.path("result").path("view").path("roundWinnerSeat").asInt());
            assertTrue(ack.path("result").path("deadlineAt").isNull());
            assertEquals("MATCH_OVER", opponent.path("view").path("phase").asText());
            assertTrue(opponent.path("deadlineAt").isNull());
            assertEquals("ENDED", jdbc.queryForObject("SELECT state FROM game.matches WHERE id = ?", String.class, matchId));
            assertNotNull(jdbc.queryForObject("SELECT ended_at FROM game.matches WHERE id = ?", Timestamp.class, matchId));
            assertEquals("WAITING", rooms.get(room.id(), host).state());
            assertTrue(rooms.get(room.id(), host).members().stream().noneMatch(RoomView.Member::ready));
            assertNull(matches.current(room.id(), host));
            actor.socket.sendText(command, true).join();
            assertTrue(actor.nextMessage().path("result").path("duplicate").asBoolean());
            assertNull(other.messages.poll(300, TimeUnit.MILLISECONDS));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM game.match_commands WHERE match_id = ?",
                    Integer.class, matchId));
        } finally {
            hostPeer.socket.abort();
            guestPeer.socket.abort();
            sessions.clear();
        }
    }

    @Test
    void twoSocketsPlayAnEntireDealtRoundToServerDecidedWinner() throws Exception {
        GameIdentity host = player("RoundHost");
        GameIdentity guest = player("RoundGuest");
        sessions.put(HOST_TOKEN, host);
        sessions.put(GUEST_TOKEN, guest);
        RoomService rooms = application.getBean(RoomService.class);
        MatchService matches = application.getBean(MatchService.class);
        JdbcTemplate jdbc = application.getBean(JdbcTemplate.class);
        RoomView room = rooms.create(host, "CLASSIC", 2);
        room = rooms.join(guest, room.code());
        room = rooms.ready(room.id(), host, true, room.version());
        room = rooms.ready(room.id(), guest, true, room.version());
        UUID matchId = matches.start(room.id(), host, room.version()).matchId();
        UnoSnapshot original = json.readValue(jdbc.queryForObject(
                "SELECT snapshot::text FROM game.matches WHERE id = ?", String.class, matchId), UnoSnapshot.class);
        UnoState dealt = new com.example.uno.core.rules.ClassicUno().start(original.players(),
                original.dealerSeat(), new Random(93));
        UnoSnapshot initial = dealt.snapshot();
        UnoSnapshot prepared = new UnoSnapshot(initial.players(), initial.hands(), initial.drawPile(),
                initial.discardPile(), List.of(499, 499), initial.dealerSeat(), initial.currentSeat(),
                initial.direction(), initial.roundNumber(), initial.version(), initial.phase(),
                initial.activeColor(), initial.drawnCardId(), initial.pendingDrawFour(),
                initial.unoVulnerableSeat(), initial.roundWinnerSeat(), initial.roundPoints());
        UnoState.restore(prepared);
        jdbc.update("UPDATE game.matches SET snapshot = CAST(? AS jsonb) WHERE id = ?",
                json.writeValueAsString(prepared), matchId);

        Peer hostPeer = connect(HOST_TOKEN);
        Peer guestPeer = connect(GUEST_TOKEN);
        try {
            String subscribe = json.writeValueAsString(Map.of(
                    "protocolVersion", 1, "type", "SUBSCRIBE", "matchId", matchId));
            hostPeer.socket.sendText(subscribe, true).join();
            guestPeer.socket.sendText(subscribe, true).join();
            JsonNode hostView = hostPeer.nextMessage().path("view");
            JsonNode guestView = guestPeer.nextMessage().path("view");
            int actions = 0;
            while (!"MATCH_OVER".equals(hostView.path("phase").asText()) && actions++ < 1000) {
                String currentId = hostView.path("players").get(hostView.path("currentSeat").asInt())
                        .path("userId").asText();
                boolean hostActs = currentId.equals(host.userId().toString());
                Peer actor = hostActs ? hostPeer : guestPeer;
                Peer other = hostActs ? guestPeer : hostPeer;
                JsonNode actorView = hostActs ? hostView : guestView;
                Map<String, Object> action = autoplayAction(actorView);
                String command = json.writeValueAsString(Map.of(
                        "protocolVersion", 1, "type", "COMMAND", "matchId", matchId,
                        "command", action));
                actor.socket.sendText(command, true).join();
                JsonNode ack = actor.nextMessage();
                assertEquals("COMMAND_ACK", ack.path("type").asText(), ack.toString());
                JsonNode update = other.nextMessage();
                assertEquals("MATCH_SNAPSHOT", update.path("type").asText());
                if (hostActs) { hostView = ack.path("result").path("view"); guestView = update.path("view"); }
                else { guestView = ack.path("result").path("view"); hostView = update.path("view"); }
                assertEquals(hostView.path("version").asLong(), guestView.path("version").asLong());
            }
            assertTrue(actions < 1000, "the network autoplay should finish the dealt round");
            assertEquals("MATCH_OVER", hostView.path("phase").asText());
            assertEquals("MATCH_OVER", guestView.path("phase").asText());
            assertEquals(hostView.path("roundWinnerSeat").asInt(), guestView.path("roundWinnerSeat").asInt());
            assertEquals("ENDED", jdbc.queryForObject("SELECT state FROM game.matches WHERE id = ?", String.class, matchId));
            assertEquals("WAITING", rooms.get(room.id(), host).state());
        } finally {
            hostPeer.socket.abort();
            guestPeer.socket.abort();
            sessions.clear();
        }
    }

    private static Map<String, Object> autoplayAction(JsonNode view) {
        String phase = view.path("phase").asText();
        java.util.HashMap<String, Object> action = new java.util.HashMap<>();
        action.put("protocolVersion", 1);
        action.put("commandId", UUID.randomUUID());
        action.put("expectedVersion", view.path("version").asLong());
        if ("INITIAL_WILD_COLOR".equals(phase)) {
            action.put("type", "CHOOSE_INITIAL_COLOR");
            action.put("chosenColor", "RED");
        } else if ("DRAW_FOUR_RESPONSE".equals(phase)) {
            action.put("type", "ACCEPT_DRAW_FOUR");
        } else if ("AFTER_DRAW".equals(phase)) {
            action.put("type", "PLAY");
            action.put("cardId", view.path("drawnCardId").asInt());
        } else if ("TURN".equals(phase)) {
            UnoCard top = UnoCard.of(view.path("topCard").path("id").asInt());
            UnoCard.Color active = UnoCard.Color.valueOf(view.path("activeColor").asText());
            Integer playable = null;
            for (JsonNode candidate : view.path("ownHand")) {
                UnoCard card = UnoCard.of(candidate.path("id").asInt());
                if (card.color() == null || card.color() == active ||
                        (top.color() != null && card.kind() == top.kind()
                                && (card.kind() != UnoCard.Kind.NUMBER || card.number() == top.number()))) {
                    playable = card.id();
                    break;
                }
            }
            if (playable == null) action.put("type", "DRAW");
            else {
                action.put("type", "PLAY");
                action.put("cardId", playable);
            }
        } else {
            throw new AssertionError("Unexpected phase " + phase);
        }
        if ("PLAY".equals(action.get("type"))) {
            if (UnoCard.of((int) action.get("cardId")).color() == null) action.put("chosenColor", "RED");
            action.put("callUno", view.path("ownHand").size() == 2);
        }
        return action;
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
        final BlockingQueue<CloseEvent> closes = new LinkedBlockingQueue<>();
        final StringBuilder pending = new StringBuilder();

        JsonNode nextMessage() throws InterruptedException {
            String value = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(value, "Timed out waiting for WebSocket message");
            return json.readTree(value);
        }

        int nextClose() throws InterruptedException {
            return nextCloseEvent().statusCode();
        }

        CloseEvent nextCloseEvent() throws InterruptedException {
            CloseEvent close = closes.poll(5, TimeUnit.SECONDS);
            assertNotNull(close, "Timed out waiting for WebSocket close");
            return close;
        }

        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            pending.append(data);
            if (last) { messages.add(pending.toString()); pending.setLength(0); }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closes.add(new CloseEvent(statusCode, reason));
            return CompletableFuture.completedFuture(null);
        }

        record CloseEvent(int statusCode, String reason) { }
    }
}
