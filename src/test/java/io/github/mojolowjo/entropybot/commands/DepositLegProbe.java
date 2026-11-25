package io.github.mojolowjo.entropybot.commands;

/** Test access to DepositLeg's pure checks (the class is package-private) for tests in other packages. */
public final class DepositLegProbe {
    private DepositLegProbe() {}

    public static boolean goneAt(String blockId) { return DepositLeg.goneAt(blockId); }

    public static String notKnownWords(String blockId) { return DepositLeg.notKnownWords(blockId); }
}
