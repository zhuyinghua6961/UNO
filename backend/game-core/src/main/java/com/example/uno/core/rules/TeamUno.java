package com.example.uno.core.rules;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Four seats alternate A/B/A/B. One teammate going out ends the match for their team. */
public final class TeamUno {
    public static final int RULES_VERSION = 1;
    private final ClassicUno classic = new ClassicUno();

    public UnoState start(List<UUID> players, int dealerSeat) {
        return start(players, dealerSeat, new SecureRandom());
    }

    public UnoState start(List<UUID> players, int dealerSeat, RandomGenerator random) {
        if (players == null || players.size() != 4)
            throw new IllegalArgumentException("A team match needs exactly four players");
        return classic.start(players, dealerSeat, random);
    }

    public UnoTransition apply(UnoState state, UnoCommand command) {
        return apply(state, command, new SecureRandom());
    }

    public UnoTransition apply(UnoState state, UnoCommand command, RandomGenerator random) {
        if (state.players().size() != 4) throw new IllegalArgumentException("A team match needs four players");
        UnoTransition transition = classic.apply(state, command, random);
        UnoState result = transition.state();
        if (result.phase() != UnoState.Phase.ROUND_OVER && result.phase() != UnoState.Phase.MATCH_OVER)
            return transition;

        int winningParity = result.roundWinnerSeat() % 2;
        int points = 0;
        for (int seat = 0; seat < 4; seat++) {
            if (seat % 2 != winningParity)
                for (int cardId : result.hands().get(seat)) points += UnoCard.of(cardId).points();
        }
        List<Integer> scores = List.of(
                winningParity == 0 ? points : 0,
                winningParity == 1 ? points : 0,
                winningParity == 0 ? points : 0,
                winningParity == 1 ? points : 0);
        UnoSnapshot snapshot = result.snapshot();
        UnoState finished = UnoState.restore(new UnoSnapshot(snapshot.players(), snapshot.hands(),
                snapshot.drawPile(), snapshot.discardPile(), scores, snapshot.dealerSeat(),
                snapshot.currentSeat(), snapshot.direction(), snapshot.roundNumber(), snapshot.version(),
                UnoState.Phase.MATCH_OVER, snapshot.activeColor(), null, null, null,
                snapshot.roundWinnerSeat(), points));
        return new UnoTransition(finished, transition.event(), transition.cardsDrawnBySeat(),
                transition.challengeOutcome(), transition.privateReveals());
    }
}
