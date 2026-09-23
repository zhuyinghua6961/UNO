package com.example.uno.core.rules;

public final class UnoRuleViolation extends RuntimeException {
    public enum Code {
        UNKNOWN_PLAYER, NOT_YOUR_TURN, WRONG_PHASE, CARD_NOT_IN_HAND, ILLEGAL_PLAY,
        DRAWN_CARD_ONLY, COLOR_REQUIRED, COLOR_NOT_ALLOWED,
        UNO_NOT_OPEN, CANNOT_CATCH_SELF, ROUND_NOT_OVER
    }

    private final Code code;
    public UnoRuleViolation(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
