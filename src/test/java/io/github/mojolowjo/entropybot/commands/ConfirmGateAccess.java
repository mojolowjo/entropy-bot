package io.github.mojolowjo.entropybot.commands;

/** Test access to ConfirmGate's package-private dig count. */
public final class ConfirmGateAccess {
    private ConfirmGateAccess() {}

    public static long volume(String rest) {
        return ConfirmGate.digVolume(Texts.words(rest.toLowerCase()));
    }
}
