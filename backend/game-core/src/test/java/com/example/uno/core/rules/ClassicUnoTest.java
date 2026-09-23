package com.example.uno.core.rules;

import static com.example.uno.core.rules.UnoCard.Color.*;
import static com.example.uno.core.rules.UnoCard.Kind.*;
import static com.example.uno.core.rules.UnoRuleViolation.Code.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClassicUnoTest {
    private final ClassicUno rules = new ClassicUno();
    private final List<UUID> players = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test
    void classicDeckHas108StablePhysicalCardsAndOfficialPointValues() {
        assertEquals(108, UnoCard.standardDeck().size());
        assertEquals(108, UnoCard.standardDeck().stream().map(UnoCard::id).distinct().count());
        for (UnoCard.Color color : UnoCard.Color.values()) {
            assertEquals(25, UnoCard.standardDeck().stream().filter(card -> color == card.color()).count());
            assertEquals(1, UnoCard.standardDeck().stream().filter(card -> color == card.color()
                    && card.kind() == NUMBER && card.number() == 0).count());
            for (int number = 1; number <= 9; number++) {
                int expected = number;
                assertEquals(2, UnoCard.standardDeck().stream().filter(card -> color == card.color()
                        && card.kind() == NUMBER && card.number() == expected).count());
            }
        }
        assertEquals(4, UnoCard.standardDeck().stream().filter(card -> card.kind() == WILD).count());
        assertEquals(4, UnoCard.standardDeck().stream().filter(card -> card.kind() == WILD_DRAW_FOUR).count());
        assertEquals(20, UnoCard.of(id(RED, DRAW_TWO, -1, 0)).points());
        assertEquals(50, UnoCard.of(id(null, WILD, -1, 0)).points());
    }

    @Test
    void seededDealIsDeterministicAndOpeningActionsAreApplied() {
        List<UUID> pair = players.subList(0, 2);
        UnoState first = rules.start(pair, 0, new Random(57));
        UnoState again = rules.start(pair, 0, new Random(57));
        assertEquals(first.hands(), again.hands());
        assertEquals(first.drawPile(), again.drawPile());
        assertEquals(first.discardPile(), again.discardPile());
        assertEquals(108, countCards(first));
        for (UnoCard.Kind kind : List.of(SKIP, REVERSE, DRAW_TWO, WILD)) {
            UnoState found = opening(kind, pair);
            if (kind == WILD) {
                assertEquals(UnoState.Phase.INITIAL_WILD_COLOR, found.phase());
                assertNull(found.activeColor());
                assertEquals(1, found.currentSeat());
                UnoState colored = rules.apply(found, new UnoCommand.ChooseInitialColor(pair.get(1), BLUE)).state();
                assertEquals(UnoState.Phase.TURN, colored.phase());
                assertEquals(BLUE, colored.activeColor());
            } else if (kind == REVERSE) {
                assertEquals(-1, found.direction());
                assertEquals(0, found.currentSeat());
            } else {
                assertEquals(0, found.currentSeat());
                assertEquals(kind == DRAW_TWO ? 9 : 7, found.hands().get(1).size());
            }
        }
        for (int seed = 0; seed < 100; seed++) assertNotEquals(WILD_DRAW_FOUR,
                rules.start(pair, 0, new Random(seed)).topCard().kind());
    }

    @Test
    void onlyMatchingCardsMayBePlayedAndRejectedCommandsDoNotChangeState() {
        int redFive = id(RED, NUMBER, 5, 0);
        int blueFive = id(BLUE, NUMBER, 5, 0);
        int blueSeven = id(BLUE, NUMBER, 7, 0);
        int redTwo = id(RED, NUMBER, 2, 0);
        int wild = id(null, WILD, -1, 0);
        UnoState state = fixture(List.of(List.of(blueFive, blueSeven, redTwo, wild), List.of(id(GREEN, NUMBER, 4, 0))),
                List.of(redFive), List.of(), 0, 1, UnoState.Phase.TURN, RED, null, null, List.of(0, 0));
        assertEquals(Set.of(blueFive, redTwo, wild), Set.copyOf(rules.legalPlays(state, players.get(0))));
        assertEquals(ILLEGAL_PLAY, violation(() -> rules.apply(state, new UnoCommand.Play(players.get(0), blueSeven, null, false))));
        assertEquals(NOT_YOUR_TURN, violation(() -> rules.apply(state, new UnoCommand.Draw(players.get(1)))));
        assertEquals(CARD_NOT_IN_HAND, violation(() -> rules.apply(state, new UnoCommand.Play(players.get(0), redFive, null, false))));
        assertEquals(COLOR_REQUIRED, violation(() -> rules.apply(state, new UnoCommand.Play(players.get(0), wild, null, false))));
        assertEquals(COLOR_NOT_ALLOWED, violation(() -> rules.apply(state, new UnoCommand.Play(players.get(0), redTwo, BLUE, false))));
        assertEquals(4, state.hands().get(0).size());
        assertEquals(1, state.discardPile().size());
        assertEquals(1, state.version());
    }

    @Test
    void drawnCardCanBePlayedOrPassedButNoOldHandCardCanFollowDraw() {
        int redFive = id(RED, NUMBER, 5, 0);
        int blueFive = id(BLUE, NUMBER, 5, 0);
        int redTwo = id(RED, NUMBER, 2, 0);
        UnoState state = fixture(List.of(List.of(redTwo, id(GREEN, NUMBER, 7, 0)), List.of(id(YELLOW, NUMBER, 9, 0))),
                List.of(redFive), List.of(blueFive), 0, 1, UnoState.Phase.TURN, RED, null, null, List.of(0, 0));
        UnoState drawn = rules.apply(state, new UnoCommand.Draw(players.get(0))).state();
        assertEquals(UnoState.Phase.AFTER_DRAW, drawn.phase());
        assertEquals(blueFive, drawn.drawnCardId());
        assertEquals(DRAWN_CARD_ONLY, violation(() -> rules.apply(drawn, new UnoCommand.Play(players.get(0), redTwo, null, false))));
        assertEquals(List.of(blueFive), rules.legalPlays(drawn, players.get(0)));
        UnoState played = rules.apply(drawn, new UnoCommand.Play(players.get(0), blueFive, null, false)).state();
        assertEquals(blueFive, played.topCard().id());
        assertEquals(1, played.currentSeat());
        UnoState passed = rules.apply(drawn, new UnoCommand.Pass(players.get(0))).state();
        assertEquals(1, passed.currentSeat());
        assertNull(passed.drawnCardId());
        assertTrue(passed.hands().get(0).contains(blueFive));
    }

    @Test
    void anUnplayableDrawEndsTheTurnAndActionCardsDoNotStack() {
        int redFive = id(RED, NUMBER, 5, 0);
        int blueSeven = id(BLUE, NUMBER, 7, 0);
        UnoState state = fixture(List.of(List.of(id(GREEN, NUMBER, 4, 0)),
                        List.of(id(RED, DRAW_TWO, -1, 0), id(YELLOW, NUMBER, 3, 0))),
                List.of(redFive), List.of(blueSeven), 0, 1, UnoState.Phase.TURN, RED, null, null, List.of(0, 0));
        UnoState after = rules.apply(state, new UnoCommand.Draw(players.get(0))).state();
        assertEquals(UnoState.Phase.TURN, after.phase());
        assertEquals(1, after.currentSeat());
        assertNull(after.drawnCardId());
        UnoState penalized = rules.apply(after, new UnoCommand.Play(players.get(1), id(RED, DRAW_TWO, -1, 0), null, false)).state();
        assertEquals(1, penalized.currentSeat()); // two players: victim loses turn
        assertEquals(4, penalized.hands().get(0).size());
        assertEquals(NOT_YOUR_TURN, violation(() -> rules.apply(penalized, new UnoCommand.Play(players.get(0), blueSeven, null, false))));
    }

    @Test
    void reverseAndSkipApplyTwoPlayerAndThreePlayerTurnOrder() {
        int redFive = id(RED, NUMBER, 5, 0);
        int redReverse = id(RED, REVERSE, -1, 0);
        int redSkip = id(RED, SKIP, -1, 0);
        UnoState two = fixture(List.of(List.of(redReverse, id(BLUE, NUMBER, 2, 0)), List.of(id(GREEN, NUMBER, 9, 0))),
                List.of(redFive), List.of(), 0, 1, UnoState.Phase.TURN, RED, null, null, List.of(0, 0));
        UnoState reversedTwo = rules.apply(two, new UnoCommand.Play(players.get(0), redReverse, null, false)).state();
        assertEquals(0, reversedTwo.currentSeat());
        assertEquals(-1, reversedTwo.direction());
        UnoState three = fixture(List.of(List.of(redSkip, id(BLUE, NUMBER, 2, 0)), List.of(id(GREEN, NUMBER, 9, 0)),
                List.of(id(YELLOW, NUMBER, 8, 0))), List.of(redFive), List.of(), 0, 1,
                UnoState.Phase.TURN, RED, null, null, List.of(0, 0, 0));
        assertEquals(2, rules.apply(three, new UnoCommand.Play(players.get(0), redSkip, null, false)).state().currentSeat());
        UnoState reverseThree = fixture(List.of(List.of(redReverse, id(BLUE, NUMBER, 2, 0)), List.of(id(GREEN, NUMBER, 9, 0)),
                List.of(id(YELLOW, NUMBER, 8, 0))), List.of(redFive), List.of(), 0, 1,
                UnoState.Phase.TURN, RED, null, null, List.of(0, 0, 0));
        UnoState reversedThree = rules.apply(reverseThree, new UnoCommand.Play(players.get(0), redReverse, null, false)).state();
        assertEquals(-1, reversedThree.direction());
        assertEquals(2, reversedThree.currentSeat());
    }

    @Test
    void drawFourChallengeUsesPlayTimeColorEvidenceAndRevealsOnlyToChallenger() {
        int redFive = id(RED, NUMBER, 5, 0);
        int drawFour = id(null, WILD_DRAW_FOUR, -1, 0);
        int redSeven = id(RED, NUMBER, 7, 0);
        UnoState state = fixture(List.of(List.of(drawFour, redSeven), List.of(id(BLUE, NUMBER, 2, 0)),
                List.of(id(GREEN, NUMBER, 2, 0))), List.of(redFive), List.of(), 0, 1,
                UnoState.Phase.TURN, RED, null, null, List.of(0, 0, 0));
        UnoState pending = rules.apply(state, new UnoCommand.Play(players.get(0), drawFour, BLUE, false)).state();
        assertEquals(UnoState.Phase.DRAW_FOUR_RESPONSE, pending.phase());
        assertEquals(BLUE, pending.activeColor());
        assertTrue(rules.view(pending, players.get(1)).canRespondToDrawFour());
        assertFalse(rules.view(pending, players.get(2)).canRespondToDrawFour());
        assertEquals(1, rules.view(pending, players.get(1)).players().get(0).handCount());
        assertEquals(WRONG_PHASE, violation(() -> rules.apply(pending, new UnoCommand.Play(players.get(1), id(BLUE, NUMBER, 2, 0), null, false))));
        UnoTransition challenged = rules.apply(pending, new UnoCommand.ChallengeDrawFour(players.get(1)));
        assertEquals(UnoTransition.ChallengeOutcome.OFFENDER_GUILTY, challenged.challengeOutcome());
        assertEquals(4, challenged.cardsDrawnBySeat().get(0));
        assertEquals(1, challenged.state().currentSeat());
        assertEquals(Set.of(players.get(1)), challenged.privateReveals().keySet());
        assertEquals(List.of(redSeven), challenged.privateReveals().get(players.get(1)).stream().map(UnoCard::id).toList());
        assertFalse(rules.view(challenged.state(), players.get(2)).ownHand().stream().anyMatch(card -> card.id() == redSeven));
        assertEquals(108, countCards(challenged.state()));
    }

    @Test
    void innocentDrawFourChallengeDrawsSixAndLastCardWinWaitsForResolution() {
        int redFive = id(RED, NUMBER, 5, 0);
        int drawFour = id(null, WILD_DRAW_FOUR, -1, 0);
        UnoState state = fixture(List.of(List.of(drawFour), List.of(id(BLUE, NUMBER, 2, 0))),
                List.of(redFive), List.of(), 0, 1, UnoState.Phase.TURN, RED, null, null, List.of(0, 0));
        UnoState pending = rules.apply(state, new UnoCommand.Play(players.get(0), drawFour, GREEN, false)).state();
        assertEquals(UnoState.Phase.DRAW_FOUR_RESPONSE, pending.phase());
        assertNull(pending.roundWinnerSeat());
        UnoTransition challenged = rules.apply(pending, new UnoCommand.ChallengeDrawFour(players.get(1)));
        assertEquals(UnoTransition.ChallengeOutcome.OFFENDER_INNOCENT, challenged.challengeOutcome());
        assertEquals(6, challenged.cardsDrawnBySeat().get(1));
        assertEquals(UnoState.Phase.ROUND_OVER, challenged.state().phase());
        assertEquals(0, challenged.state().roundWinnerSeat());
        assertTrue(challenged.state().roundPoints() > 2);
        UnoTransition accepted = rules.apply(pending, new UnoCommand.AcceptDrawFour(players.get(1)));
        assertEquals(4, accepted.cardsDrawnBySeat().get(1));
        assertEquals(UnoState.Phase.ROUND_OVER, accepted.state().phase());
        assertEquals(0, accepted.state().roundWinnerSeat());
    }

    @Test
    void matchingNumberDoesNotMakeDrawFourIllegalAndOnlyVictimMayChallenge() {
        int redFive = id(RED, NUMBER, 5, 0);
        int blueFive = id(BLUE, NUMBER, 5, 0);
        int drawFour = id(null, WILD_DRAW_FOUR, -1, 0);
        UnoState state = fixture(List.of(List.of(drawFour, blueFive), List.of(id(GREEN, NUMBER, 2, 0)),
                List.of(id(YELLOW, NUMBER, 7, 0))), List.of(redFive), List.of(), 0, 1,
                UnoState.Phase.TURN, RED, null, null, List.of(0, 0, 0));
        UnoState pending = rules.apply(state, new UnoCommand.Play(players.get(0), drawFour, GREEN, true)).state();
        assertEquals(NOT_YOUR_TURN, violation(() -> rules.apply(pending, new UnoCommand.ChallengeDrawFour(players.get(2)))));
        UnoTransition challenged = rules.apply(pending, new UnoCommand.ChallengeDrawFour(players.get(1)));
        assertEquals(UnoTransition.ChallengeOutcome.OFFENDER_INNOCENT, challenged.challengeOutcome());
        assertEquals(6, challenged.cardsDrawnBySeat().get(1));
        assertEquals(2, challenged.state().currentSeat());
        assertEquals(List.of(blueFive), challenged.privateReveals().get(players.get(1)).stream().map(UnoCard::id).toList());
        assertEquals(List.of(id(YELLOW, NUMBER, 7, 0)), rules.view(challenged.state(), players.get(2))
                .ownHand().stream().map(UnoCard::id).toList());
    }

    @Test
    void catchingUnoDuringDrawFourResponseKeepsPlayTimeChallengeEvidence() {
        int redFive = id(RED, NUMBER, 5, 0);
        int redSeven = id(RED, NUMBER, 7, 0);
        int drawFour = id(null, WILD_DRAW_FOUR, -1, 0);
        UnoState state = fixture(List.of(List.of(drawFour, redSeven), List.of(id(BLUE, NUMBER, 2, 0)),
                List.of(id(GREEN, NUMBER, 2, 0))), List.of(redFive), List.of(), 0, 1,
                UnoState.Phase.TURN, RED, null, null, List.of(0, 0, 0));
        UnoState pending = rules.apply(state, new UnoCommand.Play(players.get(0), drawFour, BLUE, false)).state();
        UnoTransition caught = rules.apply(pending, new UnoCommand.CatchUno(players.get(2), players.get(0)));
        assertEquals(UnoState.Phase.DRAW_FOUR_RESPONSE, caught.state().phase());
        assertEquals(3, caught.state().hands().get(0).size());
        UnoTransition challenged = rules.apply(caught.state(), new UnoCommand.ChallengeDrawFour(players.get(1)));
        assertEquals(UnoTransition.ChallengeOutcome.OFFENDER_GUILTY, challenged.challengeOutcome());
        assertEquals(List.of(redSeven), challenged.privateReveals().get(players.get(1)).stream()
                .map(UnoCard::id).toList());
        assertEquals(7, challenged.state().hands().get(0).size());
    }

    @Test
    void unoCanBeDeclaredOrCaughtOnlyBeforeNextTurnAction() {
        int redFive = id(RED, NUMBER, 5, 0);
        int redTwo = id(RED, NUMBER, 2, 0);
        int greenSeven = id(GREEN, NUMBER, 7, 0);
        UnoState state = fixture(List.of(List.of(redTwo, greenSeven), List.of(id(BLUE, NUMBER, 1, 0)),
                List.of(id(YELLOW, NUMBER, 6, 0))), List.of(redFive), List.of(), 0, 1,
                UnoState.Phase.TURN, RED, null, null, List.of(0, 0, 0));
        UnoState exposed = rules.apply(state, new UnoCommand.Play(players.get(0), redTwo, null, false)).state();
        assertEquals(0, exposed.unoVulnerableSeat());
        assertEquals(2, rules.apply(exposed, new UnoCommand.CatchUno(players.get(2), players.get(0)))
                .cardsDrawnBySeat().get(0));
        UnoState declared = rules.apply(exposed, new UnoCommand.SayUno(players.get(0))).state();
        assertNull(declared.unoVulnerableSeat());
        assertEquals(UNO_NOT_OPEN, violation(() -> rules.apply(declared, new UnoCommand.CatchUno(players.get(2), players.get(0)))));
        UnoState afterNextAction = rules.apply(exposed, new UnoCommand.Draw(players.get(1))).state();
        assertNull(afterNextAction.unoVulnerableSeat());
        assertEquals(UNO_NOT_OPEN, violation(() -> rules.apply(afterNextAction,
                new UnoCommand.CatchUno(players.get(2), players.get(0)))));
        UnoState immediate = rules.apply(state, new UnoCommand.Play(players.get(0), redTwo, null, true)).state();
        assertNull(immediate.unoVulnerableSeat());
    }

    @Test
    void lastDrawTwoPenalizesVictimBeforeScoringAndScoreCarriesToNextRound() {
        int redFive = id(RED, NUMBER, 5, 0);
        int drawTwo = id(RED, DRAW_TWO, -1, 0);
        UnoState state = fixture(List.of(List.of(drawTwo), List.of(id(GREEN, NUMBER, 7, 0))),
                List.of(redFive), List.of(), 0, 1, UnoState.Phase.TURN, RED, null, null, List.of(490, 0));
        UnoTransition result = rules.apply(state, new UnoCommand.Play(players.get(0), drawTwo, null, false));
        assertEquals(2, result.cardsDrawnBySeat().get(1));
        assertEquals(UnoState.Phase.MATCH_OVER, result.state().phase());
        assertEquals(0, result.state().roundWinnerSeat());
        assertEquals(490 + result.state().roundPoints(), result.state().scores().get(0));
        assertEquals(ROUND_NOT_OVER, violation(() -> rules.nextRound(result.state(), new Random(1))));
        UnoState ordinary = fixture(List.of(List.of(drawTwo), List.of(id(GREEN, NUMBER, 7, 0))),
                List.of(redFive), List.of(), 0, 1, UnoState.Phase.TURN, RED, null, null, List.of(0, 0));
        UnoState ended = rules.apply(ordinary, new UnoCommand.Play(players.get(0), drawTwo, null, false)).state();
        UnoState next = rules.nextRound(ended, new Random(7));
        assertEquals(2, next.roundNumber());
        assertEquals(1, next.dealerSeat());
        assertEquals(ended.scores(), next.scores());
        assertEquals(7, next.hands().get(0).size() - (next.topCard().kind() == DRAW_TWO && next.currentSeat() == 0 ? 2 : 0));
    }

    @Test
    void emptyDrawPileRecyclesAllButTopDiscardAndPreserves108Cards() {
        int redFive = id(RED, NUMBER, 5, 0);
        int blueSeven = id(BLUE, NUMBER, 7, 0);
        int greenSeven = id(GREEN, NUMBER, 7, 0);
        List<Integer> discard = new ArrayList<>();
        for (int id = 0; id < 108; id++) {
            if (id != blueSeven && id != greenSeven && id != redFive) discard.add(id);
        }
        discard.add(redFive);
        UnoState state = new UnoState(players.subList(0, 2), List.of(List.of(blueSeven), List.of(greenSeven)),
                List.of(), discard, List.of(0, 0), 1, 0, 1, 1, 1,
                UnoState.Phase.TURN, RED, null, null, null, null, 0);
        UnoState after = rules.apply(state, new UnoCommand.Draw(players.get(0)), new Random(11)).state();
        assertEquals(redFive, after.topCard().id());
        assertEquals(1, after.discardPile().size());
        assertEquals(108, countCards(after));
        assertEquals(104, after.drawPile().size());
    }

    @Test
    void noDrawableCardsAdvancesTurnWithoutChangingHands() {
        int redFive = id(RED, NUMBER, 5, 0);
        int blueSeven = id(BLUE, NUMBER, 7, 0);
        List<Integer> otherHand = new ArrayList<>();
        for (int id = 0; id < 108; id++) if (id != redFive && id != blueSeven) otherHand.add(id);
        UnoState state = new UnoState(players.subList(0, 2), List.of(List.of(blueSeven), otherHand),
                List.of(), List.of(redFive), List.of(0, 0), 1, 0, 1, 1, 1,
                UnoState.Phase.TURN, RED, null, null, null, null, 0);
        UnoTransition result = rules.apply(state, new UnoCommand.Draw(players.get(0)));
        assertEquals(UnoTransition.Event.DRAW_UNAVAILABLE, result.event());
        assertEquals(1, result.state().currentSeat());
        assertEquals(state.hands(), result.state().hands());
        assertTrue(result.cardsDrawnBySeat().isEmpty());
    }

    @Test
    void seededAutoplayCompletesARealRoundWithoutLosingCards() {
        List<UUID> pair = players.subList(0, 2);
        Random random = new Random(93);
        UnoState state = rules.start(pair, 0, random);
        int commands = 0;
        while (state.phase() != UnoState.Phase.ROUND_OVER && state.phase() != UnoState.Phase.MATCH_OVER
                && commands < 10000) {
            UUID actor = pair.get(state.currentSeat());
            UnoCommand command;
            if (state.phase() == UnoState.Phase.INITIAL_WILD_COLOR) {
                command = new UnoCommand.ChooseInitialColor(actor, RED);
            } else if (state.phase() == UnoState.Phase.DRAW_FOUR_RESPONSE) {
                command = new UnoCommand.AcceptDrawFour(actor);
            } else {
                List<Integer> legal = rules.legalPlays(state, actor);
                if (!legal.isEmpty()) {
                    UnoCard card = UnoCard.of(legal.get(0));
                    command = new UnoCommand.Play(actor, card.id(), card.color() == null ? RED : null, true);
                } else if (state.phase() == UnoState.Phase.AFTER_DRAW) {
                    command = new UnoCommand.Pass(actor);
                } else {
                    command = new UnoCommand.Draw(actor);
                }
            }
            state = rules.apply(state, command, random).state();
            assertEquals(108, countCards(state));
            commands++;
        }
        assertTrue(commands < 10000, "seeded round should eventually finish");
        assertNotNull(state.roundWinnerSeat());
        assertTrue(state.roundPoints() >= 0);
    }

    private UnoState opening(UnoCard.Kind kind, List<UUID> pair) {
        for (int seed = 0; seed < 10000; seed++) {
            UnoState state = rules.start(pair, 0, new Random(seed));
            if (state.topCard().kind() == kind) return state;
        }
        throw new AssertionError("Could not find opening " + kind);
    }

    private int countCards(UnoState state) {
        List<Integer> all = new ArrayList<>();
        state.hands().forEach(all::addAll);
        all.addAll(state.drawPile());
        all.addAll(state.discardPile());
        assertEquals(all.size(), new HashSet<>(all).size());
        return all.size();
    }

    private UnoRuleViolation.Code violation(org.junit.jupiter.api.function.Executable action) {
        return assertThrows(UnoRuleViolation.class, action).code();
    }

    private int id(UnoCard.Color color, UnoCard.Kind kind, int number, int copy) {
        return UnoCard.standardDeck().stream().filter(card -> card.color() == color && card.kind() == kind
                && card.number() == number).skip(copy).findFirst().orElseThrow().id();
    }

    private UnoState fixture(List<List<Integer>> hands, List<Integer> discard, List<Integer> nextDraws,
            int currentSeat, int direction, UnoState.Phase phase, UnoCard.Color activeColor,
            Integer drawnCardId, UnoState.PendingDrawFour pending, List<Integer> scores) {
        Set<Integer> used = new HashSet<>(discard);
        hands.forEach(used::addAll);
        used.addAll(nextDraws);
        int expectedUsed = discard.size() + hands.stream().mapToInt(List::size).sum() + nextDraws.size();
        assertEquals(expectedUsed, used.size(), "fixture cards must be distinct");
        List<Integer> draw = new ArrayList<>();
        for (int id = 0; id < 108; id++) if (!used.contains(id)) draw.add(id);
        for (int index = nextDraws.size() - 1; index >= 0; index--) draw.add(nextDraws.get(index));
        return new UnoState(players.subList(0, hands.size()), hands, draw, discard, scores, 0,
                currentSeat, direction, 1, 1, phase, activeColor, drawnCardId, pending, null, null, 0);
    }
}
