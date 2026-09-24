package com.example.uno.core.rules;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server result. Private challenge evidence is explicitly addressed to one player. */
public record UnoTransition(UnoState state, Event event, Map<Integer, Integer> cardsDrawnBySeat,
        ChallengeOutcome challengeOutcome, Map<UUID, List<UnoCard>> privateReveals) {
    public enum Event { PLAYED, DREW, DRAW_UNAVAILABLE, PASSED, UNO_DECLARED, UNO_CAUGHT, DRAW_FOUR_ACCEPTED,
        DRAW_FOUR_CHALLENGED, INITIAL_COLOR_CHOSEN, ROUND_STARTED }
    public enum ChallengeOutcome { NOT_APPLICABLE, OFFENDER_GUILTY, OFFENDER_INNOCENT }

    public UnoTransition {
        cardsDrawnBySeat = Map.copyOf(cardsDrawnBySeat);
        privateReveals = privateReveals.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }
}
