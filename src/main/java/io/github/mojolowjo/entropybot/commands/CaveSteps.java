package io.github.mojolowjo.entropybot.commands;

import net.minecraft.client.player.LocalPlayer;

/** B7d contract: Seq hands every step whose type starts with "cave", "explore" or "mineore" here. D3 implements. */
final class CaveSteps {
    private CaveSteps() {}

    static String step(Seq seq, Seq.Step st, LocalPlayer p, long elapsed) {
        return "not built yet: the " + st.type + " step (B7d D3)";
    }
}
