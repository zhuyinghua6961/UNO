package com.example.uno.game.realtime;

import com.example.uno.game.auth.GameAuthFailure;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.auth.HttpSessionVerifier;
import com.example.uno.game.matches.MatchCommandInput;
import com.example.uno.game.matches.MatchFailure;
import com.example.uno.game.matches.MatchService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

/** One authenticated subscription per connection; all state is projected per recipient. */
@Component
public final class GameWebSocketHandler extends TextWebSocketHandler {
    private static final int MAX_MESSAGE_BYTES = 8192;
    private static final int MAX_MESSAGES_PER_WINDOW = 30;
    private static final long WINDOW_NANOS = 10_000_000_000L;
    private static final CloseStatus TAKEN_OVER = new CloseStatus(4001, "TAKEN_OVER");
    private final MatchService matches;
    private final HttpSessionVerifier verifier;
    private final JsonMapper json = new JsonMapper();
    private final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<SubscriptionKey, Client> subscriptions = new ConcurrentHashMap<>();
    private final Object[] subscriptionLocks = new Object[256];

    public GameWebSocketHandler(MatchService matches, HttpSessionVerifier verifier) {
        this.matches = matches;
        this.verifier = verifier;
        for (int index = 0; index < subscriptionLocks.length; index++) subscriptionLocks[index] = new Object();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Object attribute = session.getAttributes().get(GameWebSocketHandshake.AUTH_ATTRIBUTE);
        if (!(attribute instanceof GameWebSocketHandshake.SessionAuth auth)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.setTextMessageSizeLimit(MAX_MESSAGE_BYTES);
        clients.put(session.getId(), new Client(session, auth));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Client client = clients.get(session.getId());
        if (client == null) { session.close(CloseStatus.POLICY_VIOLATION); return; }
        if (message.getPayload().getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            close(client, CloseStatus.POLICY_VIOLATION);
            return;
        }
        if (!client.allowMessage()) {
            close(client, CloseStatus.POLICY_VIOLATION.withReason("RATE_LIMITED"));
            return;
        }
        if (!verify(client)) return;
        Inbound inbound;
        try {
            inbound = json.readValue(message.getPayload(), Inbound.class);
            if (inbound.protocolVersion() != 1 || inbound.matchId() == null || inbound.type() == null)
                throw new IllegalArgumentException("Invalid protocol envelope");
        } catch (RuntimeException exception) {
            send(client, Map.of("type", "ERROR", "code", "BAD_MESSAGE"));
            return;
        }
        if ("SUBSCRIBE".equals(inbound.type())) {
            if (inbound.command() != null) { send(client, Map.of("type", "ERROR", "code", "BAD_MESSAGE")); return; }
            try {
                SubscriptionKey key = new SubscriptionKey(inbound.matchId(), client.auth.identity().userId());
                synchronized (lockFor(key)) {
                    matches.snapshot(inbound.matchId(), client.auth.identity());
                    SubscriptionKey previousKey = client.subscription;
                    if (previousKey != null) subscriptions.remove(previousKey, client);
                    client.matchId = inbound.matchId();
                    client.subscription = key;
                    Client displaced = subscriptions.put(key, client);
                    if (displaced != null && displaced != client) close(displaced, TAKEN_OVER);
                    // A command can commit between the membership check and registration.
                    // Refresh after registration so its broadcast cannot be the only copy.
                    try {
                        sendSnapshot(client, inbound.matchId(), matches.snapshot(inbound.matchId(), client.auth.identity()));
                    } catch (MatchFailure failure) {
                        close(client, CloseStatus.POLICY_VIOLATION);
                    }
                }
            } catch (MatchFailure failure) {
                send(client, Map.of("type", "ERROR", "code", failure.code()));
            }
        } else if ("COMMAND".equals(inbound.type())) {
            if (!inbound.matchId().equals(client.matchId)
                    || subscriptions.get(client.subscription) != client || inbound.command() == null
                    || inbound.command().protocolVersion() != 1 || inbound.command().commandId() == null
                    || inbound.command().type() == null || inbound.command().expectedVersion() < 1) {
                send(client, Map.of("type", "COMMAND_REJECTED", "code", "BAD_MESSAGE"));
                return;
            }
            try {
                MatchService.CommandResult receipt;
                synchronized (lockFor(client.subscription)) {
                    if (subscriptions.get(client.subscription) != client) {
                        close(client, TAKEN_OVER);
                        return;
                    }
                    receipt = matches.command(inbound.matchId(), client.auth.identity(), inbound.command());
                }
                synchronized (client) {
                    send(client, Map.of("type", "COMMAND_ACK", "matchId", inbound.matchId(), "result", receipt));
                    client.lastSnapshot = new SnapshotMarker(receipt.view().version(), receipt.status(),
                            receipt.deadlineAt(), null);
                }
                if (!receipt.duplicate()) broadcast(inbound.matchId(), client.session.getId());
            } catch (MatchFailure failure) {
                send(client, Map.of("type", "COMMAND_REJECTED", "matchId", inbound.matchId(),
                        "commandId", inbound.command().commandId(), "code", failure.code()));
            }
        } else {
            send(client, Map.of("type", "ERROR", "code", "BAD_MESSAGE"));
        }
    }

    public void publish(UUID matchId) { broadcast(matchId, null); }

    private void broadcast(UUID matchId, String originSessionId) {
        for (Client recipient : clients.values()) {
            if (!matchId.equals(recipient.matchId) || recipient.session.getId().equals(originSessionId)) continue;
            if (!verify(recipient)) continue;
            try {
                sendSnapshot(recipient, matchId, matches.snapshot(matchId, recipient.auth.identity()));
            } catch (MatchFailure failure) {
                close(recipient, CloseStatus.POLICY_VIOLATION);
            } catch (IOException exception) {
                close(recipient, CloseStatus.SERVER_ERROR);
            }
        }
    }

    private void sendSnapshot(Client client, UUID matchId, MatchService.MatchState snapshot) throws IOException {
        java.util.HashMap<String, Object> payload = new java.util.HashMap<>();
        payload.put("type", "MATCH_SNAPSHOT");
        payload.put("matchId", matchId);
        payload.put("view", snapshot.view());
        payload.put("deadlineAt", snapshot.deadlineAt());
        payload.put("status", snapshot.status());
        payload.put("interruptionReason", snapshot.interruptionReason());
        synchronized (client) {
            send(client, payload);
            client.lastSnapshot = SnapshotMarker.of(snapshot);
        }
    }

    /** Repairs updates committed by another Game instance or missed after a local publish. */
    @Scheduled(fixedDelayString = "${uno.matches.snapshot-poll-ms:1000}")
    public void pollSubscriptions() {
        for (Client client : clients.values()) {
            UUID matchId = client.matchId;
            if (matchId == null || client.lastSnapshot != null && !"PLAYING".equals(client.lastSnapshot.status()))
                continue;
            try {
                MatchService.MatchState current = matches.snapshot(matchId, client.auth.identity());
                SnapshotMarker marker = SnapshotMarker.of(current);
                if (marker.equals(client.lastSnapshot) || !verify(client)) continue;
                synchronized (client) {
                    if (matchId.equals(client.matchId) && !marker.equals(client.lastSnapshot))
                        sendSnapshot(client, matchId, current);
                }
            } catch (MatchFailure failure) {
                close(client, CloseStatus.POLICY_VIOLATION);
            } catch (IOException | RuntimeException failure) {
                close(client, CloseStatus.SERVER_ERROR);
            }
        }
    }

    /** Revoked or expired sessions stop receiving updates even while idle. */
    @Scheduled(fixedDelay = 30000)
    public void revalidateConnections() {
        for (Client client : clients.values()) verify(client);
    }

    private boolean verify(Client client) {
        try {
            GameIdentity refreshed = verifier.verify(client.auth.token(), client.auth.clientType()).orElse(null);
            if (refreshed != null && refreshed.userId().equals(client.auth.identity().userId())
                    && refreshed.sessionId().equals(client.auth.identity().sessionId())) return true;
            close(client, CloseStatus.POLICY_VIOLATION);
        } catch (GameAuthFailure failure) {
            close(client, CloseStatus.SERVER_ERROR);
        }
        return false;
    }

    private void send(Client client, Map<String, ?> payload) throws IOException {
        java.util.HashMap<String, Object> envelope = new java.util.HashMap<>(payload);
        envelope.put("protocolVersion", 1);
        synchronized (client) {
            if (client.session.isOpen()) client.session.sendMessage(new TextMessage(json.writeValueAsString(envelope)));
        }
    }

    private void close(Client client, CloseStatus status) {
        clients.remove(client.session.getId(), client);
        if (client.subscription != null) subscriptions.remove(client.subscription, client);
        try { if (client.session.isOpen()) client.session.close(status); }
        catch (IOException ignored) { }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Client client = clients.remove(session.getId());
        if (client != null && client.subscription != null) subscriptions.remove(client.subscription, client);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Client client = clients.get(session.getId());
        if (client != null) close(client, CloseStatus.SERVER_ERROR);
    }

    private record Inbound(int protocolVersion, String type, UUID matchId, MatchCommandInput command) { }
    private record SubscriptionKey(UUID matchId, UUID userId) { }
    private record SnapshotMarker(long version, String status, java.time.Instant deadlineAt,
            String interruptionReason) {
        static SnapshotMarker of(MatchService.MatchState state) {
            return new SnapshotMarker(state.view().version(), state.status(), state.deadlineAt(),
                    state.interruptionReason());
        }
    }

    private Object lockFor(SubscriptionKey key) {
        return subscriptionLocks[Math.floorMod(key.hashCode(), subscriptionLocks.length)];
    }

    private static final class Client {
        final WebSocketSession session;
        final GameWebSocketHandshake.SessionAuth auth;
        volatile UUID matchId;
        volatile SubscriptionKey subscription;
        volatile SnapshotMarker lastSnapshot;
        private long windowStarted = System.nanoTime();
        private int messages;

        Client(WebSocketSession session, GameWebSocketHandshake.SessionAuth auth) {
            this.session = session;
            this.auth = auth;
        }

        synchronized boolean allowMessage() {
            long now = System.nanoTime();
            if (now - windowStarted >= WINDOW_NANOS) { windowStarted = now; messages = 0; }
            return ++messages <= MAX_MESSAGES_PER_WINDOW;
        }
    }
}
