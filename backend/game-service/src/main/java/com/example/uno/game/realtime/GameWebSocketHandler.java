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
    private final MatchService matches;
    private final HttpSessionVerifier verifier;
    private final JsonMapper json = new JsonMapper();
    private final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();

    public GameWebSocketHandler(MatchService matches, HttpSessionVerifier verifier) {
        this.matches = matches;
        this.verifier = verifier;
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
        if (message.getPayload().getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES
                || !client.allowMessage()) {
            close(client, CloseStatus.POLICY_VIOLATION);
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
                var view = matches.state(inbound.matchId(), client.auth.identity());
                client.matchId = inbound.matchId();
                send(client, Map.of("type", "MATCH_SNAPSHOT", "matchId", inbound.matchId(), "view", view));
            } catch (MatchFailure failure) {
                send(client, Map.of("type", "ERROR", "code", failure.code()));
            }
        } else if ("COMMAND".equals(inbound.type())) {
            if (!inbound.matchId().equals(client.matchId) || inbound.command() == null
                    || inbound.command().protocolVersion() != 1 || inbound.command().commandId() == null
                    || inbound.command().type() == null || inbound.command().expectedVersion() < 1) {
                send(client, Map.of("type", "COMMAND_REJECTED", "code", "BAD_MESSAGE"));
                return;
            }
            try {
                var receipt = matches.command(inbound.matchId(), client.auth.identity(), inbound.command());
                send(client, Map.of("type", "COMMAND_ACK", "matchId", inbound.matchId(), "result", receipt));
                if (!receipt.duplicate()) broadcast(inbound.matchId(), client.session.getId());
            } catch (MatchFailure failure) {
                send(client, Map.of("type", "COMMAND_REJECTED", "matchId", inbound.matchId(),
                        "commandId", inbound.command().commandId(), "code", failure.code()));
            }
        } else {
            send(client, Map.of("type", "ERROR", "code", "BAD_MESSAGE"));
        }
    }

    private void broadcast(UUID matchId, String originSessionId) {
        for (Client recipient : clients.values()) {
            if (!matchId.equals(recipient.matchId) || recipient.session.getId().equals(originSessionId)) continue;
            if (!verify(recipient)) continue;
            try {
                var view = matches.state(matchId, recipient.auth.identity());
                send(recipient, Map.of("type", "MATCH_SNAPSHOT", "matchId", matchId, "view", view));
            } catch (MatchFailure failure) {
                close(recipient, CloseStatus.POLICY_VIOLATION);
            } catch (IOException exception) {
                close(recipient, CloseStatus.SERVER_ERROR);
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
        try { if (client.session.isOpen()) client.session.close(status); }
        catch (IOException ignored) { }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        clients.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Client client = clients.get(session.getId());
        if (client != null) close(client, CloseStatus.SERVER_ERROR);
    }

    private record Inbound(int protocolVersion, String type, UUID matchId, MatchCommandInput command) { }

    private static final class Client {
        final WebSocketSession session;
        final GameWebSocketHandshake.SessionAuth auth;
        volatile UUID matchId;
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
