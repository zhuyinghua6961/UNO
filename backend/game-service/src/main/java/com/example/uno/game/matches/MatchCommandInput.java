package com.example.uno.game.matches;

import com.example.uno.core.rules.UnoCard;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** Actor is derived from the verified session, never accepted from the request body. */
public record MatchCommandInput(@Min(1) int protocolVersion, @NotNull UUID commandId,
        @Min(1) long expectedVersion, @NotNull Type type, Integer cardId,
        UnoCard.Color chosenColor, UUID targetUserId, boolean callUno) {
    public enum Type { PLAY, DRAW, PASS, SAY_UNO, CATCH_UNO, ACCEPT_DRAW_FOUR,
        CHALLENGE_DRAW_FOUR, CHOOSE_INITIAL_COLOR, NEXT_ROUND }
}
