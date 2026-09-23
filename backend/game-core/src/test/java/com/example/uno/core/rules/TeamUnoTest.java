package com.example.uno.core.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeamUnoTest {
    private final TeamUno rules = new TeamUno();
    private final List<UUID> players = List.of(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID());

    @Test
    void onlyFourDistinctPlayersMayStartAndTeammateCardsStayPrivate() {
        assertThrows(IllegalArgumentException.class, () -> rules.start(players.subList(0, 3), 0));
        assertThrows(IllegalArgumentException.class,
                () -> rules.start(List.of(players.get(0), players.get(1), players.get(0), players.get(3)), 0));
        UnoState state = rules.start(players, 0);
        UnoView myView = new ClassicUno().view(state, players.get(0));
        assertEquals(7, myView.ownHand().size());
        assertEquals(7, myView.players().get(2).handCount());
        assertNotEquals(state.hands().get(2), myView.ownHand().stream().map(UnoCard::id).toList());
    }

    @Test
    void anyPartnerGoingOutWinsForBothAndOnlyOpponentsHandsScore() {
        int top = card(UnoCard.Color.RED, UnoCard.Kind.NUMBER, 5);
        int finish = card(UnoCard.Color.RED, UnoCard.Kind.SKIP, -1);
        int teammate = card(null, UnoCard.Kind.WILD, -1);
        int rivalOne = card(UnoCard.Color.BLUE, UnoCard.Kind.NUMBER, 3);
        int rivalTwo = card(UnoCard.Color.GREEN, UnoCard.Kind.NUMBER, 7);
        UnoState before = fixture(List.of(List.of(finish), List.of(rivalOne),
                List.of(teammate), List.of(rivalTwo)), top, 0, 1);
        UnoTransition transition = rules.apply(before, new UnoCommand.Play(players.get(0), finish, null, false));
        UnoState after = transition.state();
        assertEquals(UnoState.Phase.MATCH_OVER, after.phase());
        assertEquals(0, after.roundWinnerSeat());
        assertEquals(10, after.roundPoints());
        assertEquals(List.of(10, 0, 10, 0), after.scores());
        assertEquals(2, after.currentSeat()); // Skip still advances over the next seat.
        assertEquals(0, after.hands().get(0).size());
        assertEquals(1, after.hands().get(2).size());
        assertEquals(after.scores(), UnoState.restore(after.snapshot()).scores());
        assertThrows(UnoRuleViolation.class,
                () -> rules.apply(after, new UnoCommand.Draw(players.get(2))));
    }

    @Test
    void reverseStillChangesDirectionBeforePartnerWins() {
        int top = card(UnoCard.Color.RED, UnoCard.Kind.NUMBER, 5);
        int reverse = card(UnoCard.Color.RED, UnoCard.Kind.REVERSE, -1);
        UnoState before = fixture(List.of(List.of(reverse),
                List.of(card(UnoCard.Color.BLUE, UnoCard.Kind.NUMBER, 3)),
                List.of(card(UnoCard.Color.GREEN, UnoCard.Kind.NUMBER, 8)),
                List.of(card(UnoCard.Color.YELLOW, UnoCard.Kind.NUMBER, 4))), top, 0, 1);
        UnoState after = rules.apply(before, new UnoCommand.Play(players.get(0), reverse, null, false)).state();
        assertEquals(-1, after.direction());
        assertEquals(3, after.currentSeat());
        assertEquals(List.of(7, 0, 7, 0), after.scores());
    }

    @Test
    void finalDrawFourWaitsForChallengeAndScoresOnlyAfterResolution() {
        int top = card(UnoCard.Color.RED, UnoCard.Kind.NUMBER, 5);
        int drawFour = card(null, UnoCard.Kind.WILD_DRAW_FOUR, -1);
        UnoState before = fixture(List.of(List.of(drawFour),
                List.of(card(UnoCard.Color.BLUE, UnoCard.Kind.NUMBER, 3)),
                List.of(card(UnoCard.Color.GREEN, UnoCard.Kind.NUMBER, 8)),
                List.of(card(UnoCard.Color.YELLOW, UnoCard.Kind.NUMBER, 4))), top, 0, 1);
        UnoState pending = rules.apply(before,
                new UnoCommand.Play(players.get(0), drawFour, UnoCard.Color.BLUE, false)).state();
        assertEquals(UnoState.Phase.DRAW_FOUR_RESPONSE, pending.phase());
        assertNull(pending.roundWinnerSeat());
        UnoTransition resolved = rules.apply(pending, new UnoCommand.ChallengeDrawFour(players.get(1)));
        assertEquals(UnoTransition.ChallengeOutcome.OFFENDER_INNOCENT, resolved.challengeOutcome());
        assertEquals(6, resolved.cardsDrawnBySeat().get(1));
        assertEquals(UnoState.Phase.MATCH_OVER, resolved.state().phase());
        assertEquals(resolved.state().scores().get(0), resolved.state().scores().get(2));
        assertEquals(0, resolved.state().scores().get(1));
        assertEquals(0, resolved.state().scores().get(3));
    }

    private UnoState fixture(List<List<Integer>> hands, int top, int currentSeat, int direction) {
        List<Integer> drawPile = new ArrayList<>();
        for (int id = 0; id < 108; id++) {
            boolean inHand = false;
            for (List<Integer> hand : hands) inHand |= hand.contains(id);
            if (id != top && !inHand) drawPile.add(id);
        }
        return UnoState.restore(new UnoSnapshot(players, hands, drawPile, List.of(top),
                List.of(0, 0, 0, 0), 0, currentSeat, direction, 1, 1,
                UnoState.Phase.TURN, UnoCard.of(top).color(), null, null, null, null, 0));
    }

    private int card(UnoCard.Color color, UnoCard.Kind kind, int number) {
        return UnoCard.standardDeck().stream().filter(card -> card.color() == color
                && card.kind() == kind && (kind != UnoCard.Kind.NUMBER || card.number() == number))
                .findFirst().orElseThrow().id();
    }
}
