package com.example.uno.core.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Server-only, immutable round state. Constructed and validated by the rules engine. */
public final class UnoState {
    public enum Phase { INITIAL_WILD_COLOR, TURN, AFTER_DRAW, DRAW_FOUR_RESPONSE, ROUND_OVER, MATCH_OVER }

    public record PendingDrawFour(int offenderSeat, int challengerSeat, UnoCard.Color previousColor,
            boolean illegal, List<Integer> evidenceCardIds) {
        public PendingDrawFour { evidenceCardIds = List.copyOf(evidenceCardIds); }
    }

    private final List<UUID> players;
    private final List<List<Integer>> hands;
    private final List<Integer> drawPile;
    private final List<Integer> discardPile;
    private final List<Integer> scores;
    private final int dealerSeat;
    private final int currentSeat;
    private final int direction;
    private final int roundNumber;
    private final long version;
    private final Phase phase;
    private final UnoCard.Color activeColor;
    private final Integer drawnCardId;
    private final PendingDrawFour pendingDrawFour;
    private final Integer unoVulnerableSeat;
    private final Integer roundWinnerSeat;
    private final int roundPoints;

    UnoState(List<UUID> players, List<List<Integer>> hands, List<Integer> drawPile, List<Integer> discardPile,
            List<Integer> scores, int dealerSeat, int currentSeat, int direction, int roundNumber, long version,
            Phase phase, UnoCard.Color activeColor, Integer drawnCardId, PendingDrawFour pendingDrawFour,
            Integer unoVulnerableSeat, Integer roundWinnerSeat, int roundPoints) {
        this.players = List.copyOf(players);
        this.hands = hands.stream().map(List::copyOf).toList();
        this.drawPile = List.copyOf(drawPile);
        this.discardPile = List.copyOf(discardPile);
        this.scores = List.copyOf(scores);
        this.dealerSeat = dealerSeat;
        this.currentSeat = currentSeat;
        this.direction = direction;
        this.roundNumber = roundNumber;
        this.version = version;
        this.phase = Objects.requireNonNull(phase);
        this.activeColor = activeColor;
        this.drawnCardId = drawnCardId;
        this.pendingDrawFour = pendingDrawFour;
        this.unoVulnerableSeat = unoVulnerableSeat;
        this.roundWinnerSeat = roundWinnerSeat;
        this.roundPoints = roundPoints;
        validate();
    }

    private void validate() {
        int count = players.size();
        if (count < 2 || count > 6 || new HashSet<>(players).size() != count || players.stream().anyMatch(Objects::isNull)
                || hands.size() != count || scores.size() != count || scores.stream().anyMatch(score -> score < 0)
                || dealerSeat < 0 || dealerSeat >= count || currentSeat < 0 || currentSeat >= count
                || (direction != 1 && direction != -1) || roundNumber < 1 || version < 1 || discardPile.isEmpty()
                || (activeColor == null) != (phase == Phase.INITIAL_WILD_COLOR)
                || (drawnCardId != null) != (phase == Phase.AFTER_DRAW)
                || (pendingDrawFour != null) != (phase == Phase.DRAW_FOUR_RESPONSE)
                || (roundWinnerSeat != null) != (phase == Phase.ROUND_OVER || phase == Phase.MATCH_OVER)
                || (roundWinnerSeat != null && (roundWinnerSeat < 0 || roundWinnerSeat >= count))
                || (unoVulnerableSeat != null && (unoVulnerableSeat < 0 || unoVulnerableSeat >= count))) {
            throw new IllegalArgumentException("Invalid UNO state metadata");
        }
        boolean[] seen = new boolean[108];
        List<Integer> all = new ArrayList<>(108);
        hands.forEach(all::addAll);
        all.addAll(drawPile);
        all.addAll(discardPile);
        if (all.size() != 108) throw new IllegalArgumentException("UNO card count must remain 108");
        for (Integer id : all) {
            if (id == null || id < 0 || id >= 108 || seen[id]) throw new IllegalArgumentException("Duplicate or invalid UNO card ID");
            seen[id] = true;
        }
        if (drawnCardId != null && !hands.get(currentSeat).contains(drawnCardId))
            throw new IllegalArgumentException("Drawn card must remain in current hand");
        if (pendingDrawFour != null && (currentSeat != pendingDrawFour.challengerSeat()
                || pendingDrawFour.offenderSeat() == currentSeat
                || pendingDrawFour.offenderSeat() < 0 || pendingDrawFour.offenderSeat() >= count
                || pendingDrawFour.previousColor() == null
                || !hands.get(pendingDrawFour.offenderSeat()).containsAll(pendingDrawFour.evidenceCardIds())
                || UnoCard.of(discardPile.get(discardPile.size() - 1)).kind() != UnoCard.Kind.WILD_DRAW_FOUR))
            throw new IllegalArgumentException("Invalid draw-four challenge state");
        if (unoVulnerableSeat != null && hands.get(unoVulnerableSeat).size() != 1)
            throw new IllegalArgumentException("UNO catch target must hold one card");
        if (roundWinnerSeat != null && !hands.get(roundWinnerSeat).isEmpty())
            throw new IllegalArgumentException("Round winner must have no cards");
        UnoCard top = UnoCard.of(discardPile.get(discardPile.size() - 1));
        if (top.color() != null && activeColor != top.color())
            throw new IllegalArgumentException("Effective color must match a colored top card");
    }

    public List<UUID> players() { return players; }
    public List<List<Integer>> hands() { return hands; }
    public List<Integer> drawPile() { return drawPile; }
    public List<Integer> discardPile() { return discardPile; }
    public List<Integer> scores() { return scores; }
    public int dealerSeat() { return dealerSeat; }
    public int currentSeat() { return currentSeat; }
    public int direction() { return direction; }
    public int roundNumber() { return roundNumber; }
    public long version() { return version; }
    public Phase phase() { return phase; }
    public UnoCard.Color activeColor() { return activeColor; }
    public Integer drawnCardId() { return drawnCardId; }
    public PendingDrawFour pendingDrawFour() { return pendingDrawFour; }
    public Integer unoVulnerableSeat() { return unoVulnerableSeat; }
    public Integer roundWinnerSeat() { return roundWinnerSeat; }
    public int roundPoints() { return roundPoints; }
    public UnoCard topCard() { return UnoCard.of(discardPile.get(discardPile.size() - 1)); }
    public int seatOf(UUID player) {
        int seat = players.indexOf(player);
        if (seat < 0) throw new UnoRuleViolation(UnoRuleViolation.Code.UNKNOWN_PLAYER);
        return seat;
    }
}
