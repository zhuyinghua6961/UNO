package com.example.uno.core.rules;

import java.util.UUID;

/** The caller supplies intent only. The engine derives card ownership, turn and effects from state. */
public sealed interface UnoCommand permits UnoCommand.Play, UnoCommand.Draw, UnoCommand.Pass,
        UnoCommand.SayUno, UnoCommand.CatchUno, UnoCommand.AcceptDrawFour,
        UnoCommand.ChallengeDrawFour, UnoCommand.ChooseInitialColor {
    UUID actor();

    record Play(UUID actor, int cardId, UnoCard.Color chosenColor, boolean callUno) implements UnoCommand { }
    record Draw(UUID actor) implements UnoCommand { }
    record Pass(UUID actor) implements UnoCommand { }
    record SayUno(UUID actor) implements UnoCommand { }
    record CatchUno(UUID actor, UUID target) implements UnoCommand { }
    record AcceptDrawFour(UUID actor) implements UnoCommand { }
    record ChallengeDrawFour(UUID actor) implements UnoCommand { }
    record ChooseInitialColor(UUID actor, UnoCard.Color color) implements UnoCommand { }
}
