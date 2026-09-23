package com.example.uno.core.rules;

import java.util.List;
import java.util.UUID;

/** Full server-only state for durable storage. Never return this from a player endpoint. */
public record UnoSnapshot(List<UUID> players, List<List<Integer>> hands, List<Integer> drawPile,
        List<Integer> discardPile, List<Integer> scores, int dealerSeat, int currentSeat,
        int direction, int roundNumber, long version, UnoState.Phase phase,
        UnoCard.Color activeColor, Integer drawnCardId, UnoState.PendingDrawFour pendingDrawFour,
        Integer unoVulnerableSeat, Integer roundWinnerSeat, int roundPoints) {
    public UnoSnapshot {
        players = List.copyOf(players);
        hands = hands.stream().map(List::copyOf).toList();
        drawPile = List.copyOf(drawPile);
        discardPile = List.copyOf(discardPile);
        scores = List.copyOf(scores);
    }
}
