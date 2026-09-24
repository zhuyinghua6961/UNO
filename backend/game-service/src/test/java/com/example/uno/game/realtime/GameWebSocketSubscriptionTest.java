package com.example.uno.game.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.uno.core.rules.UnoCard;
import com.example.uno.core.rules.UnoState;
import com.example.uno.core.rules.UnoView;
import com.example.uno.game.auth.GameIdentity;
import com.example.uno.game.auth.HttpSessionVerifier;
import com.example.uno.game.matches.MatchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.json.JsonMapper;

class GameWebSocketSubscriptionTest {
    @Test
    void subscriptionIncludesCommandCommittedDuringItsMembershipCheck() throws Exception {
        UUID matchId = UUID.randomUUID();
        GameIdentity identity = new GameIdentity(UUID.randomUUID(), UUID.randomUUID(), "Player", "APP",
                Instant.now().plusSeconds(600));
        MatchService matches = mock(MatchService.class);
        HttpSessionVerifier verifier = mock(HttpSessionVerifier.class);
        when(verifier.verify("token", "APP")).thenReturn(java.util.Optional.of(identity));
        CountDownLatch firstReadStarted = new CountDownLatch(1);
        CountDownLatch allowFirstRead = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        when(matches.snapshot(matchId, identity)).thenAnswer(invocation -> {
            if (reads.incrementAndGet() == 1) {
                firstReadStarted.countDown();
                assertTrue(allowFirstRead.await(5, TimeUnit.SECONDS));
                return snapshot(identity.userId(), 1);
            }
            return snapshot(identity.userId(), 2);
        });
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(session.getAttributes()).thenReturn(Map.of(GameWebSocketHandshake.AUTH_ATTRIBUTE,
                new GameWebSocketHandshake.SessionAuth("token", "APP", identity)));
        when(session.isOpen()).thenReturn(true);
        List<TextMessage> sent = new ArrayList<>();
        doAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        GameWebSocketHandler handler = new GameWebSocketHandler(matches, verifier);
        handler.afterConnectionEstablished(session);
        String subscribe = "{\"protocolVersion\":1,\"type\":\"SUBSCRIBE\",\"matchId\":\"" + matchId + "\"}";
        CompletableFuture<Void> pending = CompletableFuture.runAsync(() -> {
            try {
                handler.handleTextMessage(session, new TextMessage(subscribe));
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        try {
            assertTrue(firstReadStarted.await(5, TimeUnit.SECONDS));
            handler.publish(matchId); // The commit lands before the subscriber is registered.
        } finally {
            allowFirstRead.countDown();
        }
        pending.get(5, TimeUnit.SECONDS);
        assertEquals(1, sent.size());
        assertEquals(2, new JsonMapper().readTree(sent.get(0).getPayload()).path("view").path("version").asInt());
    }

    private MatchService.MatchState snapshot(UUID playerId, long version) {
        UnoView view = new UnoView(1, version, 1, UnoState.Phase.TURN, 0, 1, UnoCard.of(0),
                UnoCard.Color.RED, 80, 1, List.of(UnoCard.of(1)),
                List.of(new UnoView.Player(playerId, 0, 1, 0)), null, null, 0, false, null);
        return new MatchService.MatchState(view, Instant.now().plusSeconds(30), "PLAYING");
    }
}
