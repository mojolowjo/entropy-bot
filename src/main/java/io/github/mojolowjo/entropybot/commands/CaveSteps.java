package io.github.mojolowjo.entropybot.commands;

import net.minecraft.client.player.LocalPlayer;

/**
 * B7d contract: Seq hands every step whose type starts with "cave", "explore" or "mineore" here. D3: they are
 * {@link Mining}'s steps - cavestep, caveend, cavexz, caverestart (mine cave), explore, mineore, mineoretool,
 * mineorecheck (the Baritone mine and its pickaxe trip).
 */
final class CaveSteps {
    private CaveSteps() {}

    static String step(Seq seq, Seq.Step st, LocalPlayer p, long elapsed) {
        return Mining.get().step(seq, st, p, elapsed);
    }
}
