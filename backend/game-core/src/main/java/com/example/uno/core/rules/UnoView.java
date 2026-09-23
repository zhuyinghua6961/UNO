package com.example.uno.core.rules;

import java.util.List;
import java.util.UUID;

/** A per-player projection. It contains no draw order or opponent hand IDs. */
public record UnoView(int rulesVersion, long version, int roundNumber, UnoState.Phase phase,
        int currentSeat, int direction, UnoCard topCard, UnoCard.Color activeColor,
        int drawCount, int discardCount, List<UnoCard> ownHand, List<Player> players,
        Integer unoVulnerableSeat, Integer roundWinnerSeat, int roundPoints,
        boolean canRespondToDrawFour, Integer drawnCardId) {
    public UnoView { ownHand = List.copyOf(ownHand); players = List.copyOf(players); }
    public record Player(UUID userId, int seat, int handCount, int score) { }
}
