package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.WoodOptions;
import io.github.mojolowjo.entropybot.aa.WoodOptions.Tree;
import io.github.mojolowjo.entropybot.aa.WoodOptions.WoodFacts;
import io.github.mojolowjo.entropybot.aa.Options;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Round 3 (0.38.b4): "area -x -y -z -w treeat work neutral" was refused with the usage line; every area command the wood options emit must parse. */
class WoodAreaCommandTest {
    static void parses(String chain) {
        String head = chain.split(" then ")[0];
        List<String> w = Arrays.asList(head.split(" ")).subList(1, head.split(" ").length);
        PolicyCommands.NewArea a = PolicyCommands.parseNew(w);
        assertNull(a.error(), head + " -> " + a.error());
    }

    @Test
    void everyWoodAreaCommandParses() {
        parses(WoodOptions.areaCmd("trees1", -43, -18, 20));
        parses(WoodOptions.areaCmd(WoodOptions.CHOP_AREA, 50, 0, WoodOptions.CHOP_R));
        parses(WoodOptions.areaCmd("trees3", -500, -700, 20));
        for (int x : new int[]{-60, 0, 60}) {
            Options.Choice c = WoodOptions.choose(new WoodFacts(4, List.of(new Tree(x, 64, -30, 50, null)), 0, true), new Options.OptionMemory(), 0);
            assertFalse(c.ranked().isEmpty());
            c.ranked().forEach(o -> parses(o.command()));
        }
    }
}
