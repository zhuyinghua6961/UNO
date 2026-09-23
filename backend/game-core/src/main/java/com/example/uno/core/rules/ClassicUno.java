package com.example.uno.core.rules;

import static com.example.uno.core.rules.UnoRuleViolation.Code.*;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Pure classic UNO rules, version 1. The caller owns command ordering and persistence. */
public final class ClassicUno {
    public static final int RULES_VERSION = 1;
    public static final int WINNING_SCORE = 500;

    public UnoState start(List<UUID> players, int dealerSeat) {
        return start(players, dealerSeat, new SecureRandom());
    }

    public UnoState start(List<UUID> players, int dealerSeat, RandomGenerator random) {
        Objects.requireNonNull(random);
        if (players == null || players.size() < 2 || players.size() > 6 || dealerSeat < 0
                || dealerSeat >= players.size() || players.stream().anyMatch(Objects::isNull)
                || players.stream().distinct().count() != players.size()) {
            throw new IllegalArgumentException("A classic round needs 2–6 distinct players and a valid dealer");
        }
        return startRound(players, dealerSeat, java.util.Collections.nCopies(players.size(), 0), 1, 1, random);
    }

    public UnoState nextRound(UnoState previous, RandomGenerator random) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(random);
        if (previous.phase() != UnoState.Phase.ROUND_OVER) throw new UnoRuleViolation(ROUND_NOT_OVER);
        return startRound(previous.players(), (previous.dealerSeat() + 1) % previous.players().size(),
                previous.scores(), previous.roundNumber() + 1, previous.version() + 1, random);
    }

    public UnoTransition apply(UnoState state, UnoCommand command) {
        return apply(state, command, new SecureRandom());
    }

    public UnoTransition apply(UnoState state, UnoCommand command, RandomGenerator random) {
        Objects.requireNonNull(state);
        Objects.requireNonNull(command);
        Objects.requireNonNull(random);
        if (state.phase() == UnoState.Phase.ROUND_OVER || state.phase() == UnoState.Phase.MATCH_OVER)
            throw new UnoRuleViolation(WRONG_PHASE);
        if (command.actor() == null) throw new UnoRuleViolation(UNKNOWN_PLAYER);
        int actor = state.seatOf(command.actor());
        Frame frame = new Frame(state);
        Map<Integer, Integer> drawn = new HashMap<>();
        Map<UUID, List<UnoCard>> reveals = Map.of();
        UnoTransition.Event event;
        UnoTransition.ChallengeOutcome outcome = UnoTransition.ChallengeOutcome.NOT_APPLICABLE;

        if (command instanceof UnoCommand.Play play) {
            play(state, frame, actor, play, random, drawn);
            event = UnoTransition.Event.PLAYED;
        } else if (command instanceof UnoCommand.Draw) {
            requireTurn(state, actor, UnoState.Phase.TURN);
            frame.unoVulnerableSeat = null;
            if (availableToDraw(frame) == 0) {
                frame.currentSeat = advance(actor, 1, frame.direction, frame.players.size());
                event = UnoTransition.Event.DRAW_UNAVAILABLE;
            } else {
                int cardId = drawOne(frame, random);
                frame.hands.get(actor).add(cardId);
                drawn.put(actor, 1);
                if (isPlayable(state, cardId)) {
                    frame.phase = UnoState.Phase.AFTER_DRAW;
                    frame.drawnCardId = cardId;
                } else {
                    frame.currentSeat = advance(actor, 1, frame.direction, frame.players.size());
                }
                event = UnoTransition.Event.DREW;
            }
        } else if (command instanceof UnoCommand.Pass) {
            requireTurn(state, actor, UnoState.Phase.AFTER_DRAW);
            frame.currentSeat = advance(actor, 1, frame.direction, frame.players.size());
            frame.drawnCardId = null;
            frame.phase = UnoState.Phase.TURN;
            event = UnoTransition.Event.PASSED;
        } else if (command instanceof UnoCommand.SayUno) {
            if (state.unoVulnerableSeat() == null || state.unoVulnerableSeat() != actor)
                throw new UnoRuleViolation(UNO_NOT_OPEN);
            frame.unoVulnerableSeat = null;
            event = UnoTransition.Event.UNO_DECLARED;
        } else if (command instanceof UnoCommand.CatchUno caught) {
            if (caught.target() == null) throw new UnoRuleViolation(UNKNOWN_PLAYER);
            int target = state.seatOf(caught.target());
            if (actor == target) throw new UnoRuleViolation(CANNOT_CATCH_SELF);
            if (state.unoVulnerableSeat() == null || state.unoVulnerableSeat() != target)
                throw new UnoRuleViolation(UNO_NOT_OPEN);
            drawMany(frame, target, 2, random, drawn);
            frame.unoVulnerableSeat = null;
            event = UnoTransition.Event.UNO_CAUGHT;
        } else if (command instanceof UnoCommand.AcceptDrawFour) {
            requireTurn(state, actor, UnoState.Phase.DRAW_FOUR_RESPONSE);
            frame.unoVulnerableSeat = null;
            int offender = state.pendingDrawFour().offenderSeat();
            drawMany(frame, actor, 4, random, drawn);
            frame.pendingDrawFour = null;
            resolveDrawFour(frame, offender, actor);
            event = UnoTransition.Event.DRAW_FOUR_ACCEPTED;
        } else if (command instanceof UnoCommand.ChallengeDrawFour) {
            requireTurn(state, actor, UnoState.Phase.DRAW_FOUR_RESPONSE);
            frame.unoVulnerableSeat = null;
            UnoState.PendingDrawFour pending = state.pendingDrawFour();
            reveals = Map.of(state.players().get(actor), pending.evidenceCardIds().stream().map(UnoCard::of).toList());
            if (pending.illegal()) {
                drawMany(frame, pending.offenderSeat(), 4, random, drawn);
                frame.pendingDrawFour = null;
                frame.currentSeat = actor;
                frame.phase = UnoState.Phase.TURN;
                outcome = UnoTransition.ChallengeOutcome.OFFENDER_GUILTY;
            } else {
                drawMany(frame, actor, 6, random, drawn);
                frame.pendingDrawFour = null;
                resolveDrawFour(frame, pending.offenderSeat(), actor);
                outcome = UnoTransition.ChallengeOutcome.OFFENDER_INNOCENT;
            }
            event = UnoTransition.Event.DRAW_FOUR_CHALLENGED;
        } else if (command instanceof UnoCommand.ChooseInitialColor choice) {
            requireTurn(state, actor, UnoState.Phase.INITIAL_WILD_COLOR);
            if (choice.color() == null) throw new UnoRuleViolation(COLOR_REQUIRED);
            frame.activeColor = choice.color();
            frame.phase = UnoState.Phase.TURN;
            event = UnoTransition.Event.INITIAL_COLOR_CHOSEN;
        } else {
            throw new IllegalStateException("Unhandled UNO command");
        }
        return new UnoTransition(frame.freeze(state.version() + 1), event, drawn, outcome, reveals);
    }

    /** A player may see only their own card identities; all other hands are counts. */
    public UnoView view(UnoState state, UUID viewer) {
        int seat = state.seatOf(viewer);
        List<UnoView.Player> players = new ArrayList<>();
        for (int index = 0; index < state.players().size(); index++) {
            players.add(new UnoView.Player(state.players().get(index), index,
                    state.hands().get(index).size(), state.scores().get(index)));
        }
        return new UnoView(RULES_VERSION, state.version(), state.roundNumber(), state.phase(), state.currentSeat(),
                state.direction(), state.topCard(), state.activeColor(), state.drawPile().size(),
                state.discardPile().size(), state.hands().get(seat).stream().map(UnoCard::of).toList(),
                players, state.unoVulnerableSeat(), state.roundWinnerSeat(), state.roundPoints(),
                state.phase() == UnoState.Phase.DRAW_FOUR_RESPONSE && state.currentSeat() == seat,
                state.currentSeat() == seat ? state.drawnCardId() : null);
    }

    public List<Integer> legalPlays(UnoState state, UUID player) {
        int seat = state.seatOf(player);
        if (seat != state.currentSeat() || (state.phase() != UnoState.Phase.TURN
                && state.phase() != UnoState.Phase.AFTER_DRAW)) return List.of();
        return state.hands().get(seat).stream()
                .filter(id -> state.phase() != UnoState.Phase.AFTER_DRAW || id.equals(state.drawnCardId()))
                .filter(id -> isPlayable(state, id)).toList();
    }

    private UnoState startRound(List<UUID> players, int dealerSeat, List<Integer> scores, int roundNumber,
            long version, RandomGenerator random) {
        List<Integer> drawPile = new ArrayList<>(108);
        for (int id = 0; id < 108; id++) drawPile.add(id);
        shuffle(drawPile, random);
        List<List<Integer>> hands = new ArrayList<>();
        for (int index = 0; index < players.size(); index++) hands.add(new ArrayList<>());
        for (int card = 0; card < 7; card++) {
            for (int offset = 1; offset <= players.size(); offset++) {
                int seat = (dealerSeat + offset) % players.size();
                hands.get(seat).add(drawPile.remove(drawPile.size() - 1));
            }
        }
        int opening;
        do {
            opening = drawPile.remove(drawPile.size() - 1);
            if (UnoCard.of(opening).kind() == UnoCard.Kind.WILD_DRAW_FOUR) {
                drawPile.add(opening);
                shuffle(drawPile, random);
            }
        } while (UnoCard.of(opening).kind() == UnoCard.Kind.WILD_DRAW_FOUR);
        UnoCard top = UnoCard.of(opening);
        int left = (dealerSeat + 1) % players.size();
        int current = left;
        int direction = 1;
        UnoState.Phase phase = UnoState.Phase.TURN;
        UnoCard.Color activeColor = top.color();
        if (top.kind() == UnoCard.Kind.WILD) {
            phase = UnoState.Phase.INITIAL_WILD_COLOR;
        } else if (top.kind() == UnoCard.Kind.DRAW_TWO) {
            hands.get(left).add(drawPile.remove(drawPile.size() - 1));
            hands.get(left).add(drawPile.remove(drawPile.size() - 1));
            current = (dealerSeat + 2) % players.size();
        } else if (top.kind() == UnoCard.Kind.SKIP) {
            current = (dealerSeat + 2) % players.size();
        } else if (top.kind() == UnoCard.Kind.REVERSE) {
            direction = -1;
            current = dealerSeat;
        }
        return new UnoState(players, hands, drawPile, List.of(opening), scores, dealerSeat, current,
                direction, roundNumber, version, phase, activeColor, null, null, null, null, 0);
    }

    private void play(UnoState state, Frame frame, int actor, UnoCommand.Play play, RandomGenerator random,
            Map<Integer, Integer> drawn) {
        if (state.phase() != UnoState.Phase.TURN && state.phase() != UnoState.Phase.AFTER_DRAW)
            throw new UnoRuleViolation(WRONG_PHASE);
        if (state.currentSeat() != actor) throw new UnoRuleViolation(NOT_YOUR_TURN);
        if (!state.hands().get(actor).contains(play.cardId())) throw new UnoRuleViolation(CARD_NOT_IN_HAND);
        if (state.phase() == UnoState.Phase.AFTER_DRAW && !Objects.equals(state.drawnCardId(), play.cardId()))
            throw new UnoRuleViolation(DRAWN_CARD_ONLY);
        UnoCard card = UnoCard.of(play.cardId());
        if (!isPlayable(state, play.cardId())) throw new UnoRuleViolation(ILLEGAL_PLAY);
        if (card.color() == null && play.chosenColor() == null) throw new UnoRuleViolation(COLOR_REQUIRED);
        if (card.color() != null && play.chosenColor() != null) throw new UnoRuleViolation(COLOR_NOT_ALLOWED);
        boolean illegalDrawFour = card.kind() == UnoCard.Kind.WILD_DRAW_FOUR
                && state.hands().get(actor).stream().filter(id -> id != play.cardId())
                        .map(UnoCard::of).anyMatch(other -> state.activeColor() == other.color());
        frame.unoVulnerableSeat = null;
        frame.hands.get(actor).remove(Integer.valueOf(play.cardId()));
        frame.discardPile.add(play.cardId());
        frame.activeColor = card.color() == null ? play.chosenColor() : card.color();
        frame.drawnCardId = null;
        if (frame.hands.get(actor).size() == 1 && !play.callUno()) frame.unoVulnerableSeat = actor;
        int next = advance(actor, 1, frame.direction, frame.players.size());
        switch (card.kind()) {
            case NUMBER, WILD -> frame.currentSeat = next;
            case SKIP -> frame.currentSeat = advance(actor, 2, frame.direction, frame.players.size());
            case REVERSE -> {
                frame.direction *= -1;
                frame.currentSeat = frame.players.size() == 2 ? actor
                        : advance(actor, 1, frame.direction, frame.players.size());
            }
            case DRAW_TWO -> {
                drawMany(frame, next, 2, random, drawn);
                frame.currentSeat = advance(next, 1, frame.direction, frame.players.size());
            }
            case WILD_DRAW_FOUR -> {
                frame.currentSeat = next;
                frame.phase = UnoState.Phase.DRAW_FOUR_RESPONSE;
                frame.pendingDrawFour = new UnoState.PendingDrawFour(actor, next, state.activeColor(),
                        illegalDrawFour, List.copyOf(frame.hands.get(actor)));
            }
        }
        if (frame.phase != UnoState.Phase.DRAW_FOUR_RESPONSE) {
            frame.phase = UnoState.Phase.TURN;
            if (frame.hands.get(actor).isEmpty()) finishRound(frame, actor);
        }
    }

    private void resolveDrawFour(Frame frame, int offender, int challenger) {
        if (frame.hands.get(offender).isEmpty()) {
            finishRound(frame, offender);
        } else {
            frame.currentSeat = advance(challenger, 1, frame.direction, frame.players.size());
            frame.phase = UnoState.Phase.TURN;
        }
    }

    private void finishRound(Frame frame, int winner) {
        int points = 0;
        for (int seat = 0; seat < frame.players.size(); seat++) {
            if (seat == winner) continue;
            for (int id : frame.hands.get(seat)) points += UnoCard.of(id).points();
        }
        frame.roundWinnerSeat = winner;
        frame.roundPoints = points;
        frame.scores.set(winner, frame.scores.get(winner) + points);
        frame.phase = frame.scores.get(winner) >= WINNING_SCORE
                ? UnoState.Phase.MATCH_OVER : UnoState.Phase.ROUND_OVER;
        frame.unoVulnerableSeat = null;
    }

    private void requireTurn(UnoState state, int actor, UnoState.Phase phase) {
        if (state.phase() != phase) throw new UnoRuleViolation(WRONG_PHASE);
        if (state.currentSeat() != actor) throw new UnoRuleViolation(NOT_YOUR_TURN);
    }

    private boolean isPlayable(UnoState state, int cardId) {
        UnoCard card = UnoCard.of(cardId);
        if (card.color() == null) return true; // A +4 can be challenged; its legality is recorded at play time.
        UnoCard top = state.topCard();
        return card.color() == state.activeColor() || (top.color() != null
                && card.kind() == top.kind() && (card.kind() != UnoCard.Kind.NUMBER
                        || card.number() == top.number()));
    }

    private int drawMany(Frame frame, int seat, int requested, RandomGenerator random, Map<Integer, Integer> counts) {
        int actual = Math.min(requested, availableToDraw(frame));
        for (int index = 0; index < actual; index++) frame.hands.get(seat).add(drawOne(frame, random));
        if (actual > 0) counts.merge(seat, actual, Integer::sum);
        return actual;
    }

    private int availableToDraw(Frame frame) { return frame.drawPile.size() + frame.discardPile.size() - 1; }

    private int drawOne(Frame frame, RandomGenerator random) {
        if (frame.drawPile.isEmpty()) {
            int top = frame.discardPile.remove(frame.discardPile.size() - 1);
            frame.drawPile.addAll(frame.discardPile);
            frame.discardPile.clear();
            frame.discardPile.add(top);
            shuffle(frame.drawPile, random);
        }
        return frame.drawPile.remove(frame.drawPile.size() - 1);
    }

    private int advance(int seat, int steps, int direction, int count) {
        return Math.floorMod(seat + direction * steps, count);
    }

    private void shuffle(List<Integer> cards, RandomGenerator random) {
        for (int last = cards.size() - 1; last > 0; last--) {
            int swap = random.nextInt(last + 1);
            int temporary = cards.get(last);
            cards.set(last, cards.get(swap));
            cards.set(swap, temporary);
        }
    }

    private static final class Frame {
        final List<UUID> players;
        final List<List<Integer>> hands = new ArrayList<>();
        final List<Integer> drawPile;
        final List<Integer> discardPile;
        final List<Integer> scores;
        final int dealerSeat;
        final int roundNumber;
        int currentSeat;
        int direction;
        UnoState.Phase phase;
        UnoCard.Color activeColor;
        Integer drawnCardId;
        UnoState.PendingDrawFour pendingDrawFour;
        Integer unoVulnerableSeat;
        Integer roundWinnerSeat;
        int roundPoints;

        Frame(UnoState state) {
            players = state.players();
            state.hands().forEach(hand -> hands.add(new ArrayList<>(hand)));
            drawPile = new ArrayList<>(state.drawPile());
            discardPile = new ArrayList<>(state.discardPile());
            scores = new ArrayList<>(state.scores());
            dealerSeat = state.dealerSeat();
            roundNumber = state.roundNumber();
            currentSeat = state.currentSeat();
            direction = state.direction();
            phase = state.phase();
            activeColor = state.activeColor();
            drawnCardId = state.drawnCardId();
            pendingDrawFour = state.pendingDrawFour();
            unoVulnerableSeat = state.unoVulnerableSeat();
            roundWinnerSeat = state.roundWinnerSeat();
            roundPoints = state.roundPoints();
        }

        UnoState freeze(long version) {
            return new UnoState(players, hands, drawPile, discardPile, scores, dealerSeat, currentSeat,
                    direction, roundNumber, version, phase, activeColor, drawnCardId, pendingDrawFour,
                    unoVulnerableSeat, roundWinnerSeat, roundPoints);
        }
    }
}
