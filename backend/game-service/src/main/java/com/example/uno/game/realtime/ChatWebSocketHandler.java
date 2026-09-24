package com.example.uno.game.realtime;

import com.example.uno.game.auth.GameAuthFailure;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.auth.HttpSessionVerifier;
import com.example.uno.game.chat.ChatFailure;
import com.example.uno.game.chat.ChatService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

/** A room subscription receives only messages still visible to its authenticated member. */
@Component
public final class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final int MAX_MESSAGE_BYTES = 8192;
    private static final int MAX_MESSAGES_PER_WINDOW = 30;
    private static final long WINDOW_NANOS = 10_000_000_000L;
    private static final Set<String> SUBSCRIBE_FIELDS = Set.of("protocolVersion", "type", "roomId");
    private static final Set<String> SEND_FIELDS = Set.of(
            "protocolVersion", "type", "roomId", "clientMessageId", "channel", "content");
    private final ChatService chat;
    private final HttpSessionVerifier verifier;
    private final JsonMapper json = new JsonMapper();
    private final ConcurrentHashMap<String, Client> clients = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ChatService chat, HttpSessionVerifier verifier) {
        this.chat = chat;
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
            close(client, CloseStatus.POLICY_VIOLATION.withReason("RATE_LIMITED"));
            return;
        }
        if (!verify(client)) return;
        Map<?, ?> inbound;
        UUID roomId;
        try {
            inbound = json.readValue(message.getPayload(), Map.class);
            if (!Integer.valueOf(1).equals(inbound.get("protocolVersion"))
                    || !(inbound.get("type") instanceof String)
                    || !(inbound.get("roomId") instanceof String rawRoom))
                throw new IllegalArgumentException("Invalid chat envelope");
            roomId = UUID.fromString(rawRoom);
        } catch (RuntimeException exception) {
            send(client, Map.of("type", "ERROR", "code", "BAD_MESSAGE"));
            return;
        }
        if ("SUBSCRIBE".equals(inbound.get("type")) && SUBSCRIBE_FIELDS.containsAll(inbound.keySet())) {
            try {
                chat.history(roomId, client.auth.identity(), 0, 1, true, "ROOM");
                client.roomId = roomId;
                send(client, Map.of("type", "CHAT_SUBSCRIBED", "roomId", roomId));
            } catch (ChatFailure failure) {
                send(client, Map.of("type", "ERROR", "code", failure.code()));
            }
        } else if ("CHAT_SEND".equals(inbound.get("type")) && SEND_FIELDS.containsAll(inbound.keySet())) {
            UUID id;
            try {
                if (!roomId.equals(client.roomId) || !(inbound.get("clientMessageId") instanceof String rawId)
                        || !(inbound.get("channel") instanceof String scope)
                        || !(inbound.get("content") instanceof String content))
                    throw new IllegalArgumentException("Invalid chat send");
                id = UUID.fromString(rawId);
                ChatService.ChatItem saved = chat.send(roomId, client.auth.identity(), id, content, scope);
                publish(saved);
                send(client, Map.of("type", "CHAT_ACK", "roomId", roomId, "item", saved));
            } catch (IllegalArgumentException failure) {
                send(client, Map.of("type", "CHAT_REJECTED", "code", "BAD_MESSAGE"));
            } catch (ChatFailure failure) {
                send(client, Map.of("type", "CHAT_REJECTED", "code", failure.code()));
            }
        } else {
            send(client, Map.of("type", "ERROR", "code", "BAD_MESSAGE"));
        }
    }

    /** Called only after the database send transaction has committed. */
    public void publish(ChatService.ChatItem item) {
        for (Client recipient : clients.values()) {
            if (!item.roomId().equals(recipient.roomId) || !verify(recipient)) continue;
            try {
                if (chat.visibleTo(item, recipient.auth.identity()))
                    send(recipient, Map.of("type", "CHAT_MESSAGE", "roomId", item.roomId(), "item", item));
            } catch (IOException failure) {
                close(recipient, CloseStatus.SERVER_ERROR);
            } catch (RuntimeException failure) {
                // Delivery is best effort after commit. Cursor history repairs missed events.
            }
        }
    }

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
        } catch (RuntimeException failure) {
            close(client, CloseStatus.SERVER_ERROR);
        }
        return false;
    }

    private void send(Client client, Map<String, ?> payload) throws IOException {
        HashMap<String, Object> envelope = new HashMap<>(payload);
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

    private static final class Client {
        final WebSocketSession session;
        final GameWebSocketHandshake.SessionAuth auth;
        volatile UUID roomId;
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
