package com.example.uno.core.rules;

import java.util.ArrayList;
import java.util.List;

/** One physical card in the 108-card classic deck. IDs are stable within rules version 1. */
public final class UnoCard {
    public enum Color { RED, YELLOW, GREEN, BLUE }
    public enum Kind { NUMBER, SKIP, REVERSE, DRAW_TWO, WILD, WILD_DRAW_FOUR }

    private static final List<UnoCard> DECK = buildDeck();

    private final int id;
    private final Color color;
    private final Kind kind;
    private final int number;

    private UnoCard(int id, Color color, Kind kind, int number) {
        this.id = id;
        this.color = color;
        this.kind = kind;
        this.number = number;
    }

    public int id() { return id; }
    public Color color() { return color; }
    public Kind kind() { return kind; }
    /** Returns -1 for a non-number card. */
    public int number() { return number; }
    public int points() {
        return switch (kind) {
            case NUMBER -> number;
            case SKIP, REVERSE, DRAW_TWO -> 20;
            case WILD, WILD_DRAW_FOUR -> 50;
        };
    }

    public static UnoCard of(int id) {
        if (id < 0 || id >= DECK.size()) throw new IllegalArgumentException("Unknown physical card ID");
        return DECK.get(id);
    }

    public static List<UnoCard> standardDeck() { return DECK; }

    private static List<UnoCard> buildDeck() {
        List<UnoCard> cards = new ArrayList<>(108);
        for (Color color : Color.values()) {
            cards.add(new UnoCard(cards.size(), color, Kind.NUMBER, 0));
            for (int number = 1; number <= 9; number++) {
                cards.add(new UnoCard(cards.size(), color, Kind.NUMBER, number));
                cards.add(new UnoCard(cards.size(), color, Kind.NUMBER, number));
            }
            for (Kind kind : List.of(Kind.SKIP, Kind.REVERSE, Kind.DRAW_TWO)) {
                cards.add(new UnoCard(cards.size(), color, kind, -1));
                cards.add(new UnoCard(cards.size(), color, kind, -1));
            }
        }
        for (int copy = 0; copy < 4; copy++) cards.add(new UnoCard(cards.size(), null, Kind.WILD, -1));
        for (int copy = 0; copy < 4; copy++) cards.add(new UnoCard(cards.size(), null, Kind.WILD_DRAW_FOUR, -1));
        return List.copyOf(cards);
    }

    @Override public boolean equals(Object other) { return other instanceof UnoCard card && id == card.id; }
    @Override public int hashCode() { return Integer.hashCode(id); }
    @Override public String toString() { return "UnoCard[id=" + id + ", color=" + color + ", kind=" + kind + ", number=" + number + "]"; }
}
