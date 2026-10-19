package io.github.mojolowjo.entropybot.cave;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** AA round 2 item 5: "mine <ore> 1 at x y z dig|walk" and its vein flood. */
class MineRulesParseAtTest {
    @Test
    void theAtForm() {
        MineRules.At a = MineRules.parseAt("iron 1 at 10 -5 -300 dig");
        assertNull(a.error());
        assertEquals("iron", a.word());
        assertEquals(1, a.count());
        assertEquals("10 -5 -300", a.cell());
        assertTrue(a.dig());
        assertEquals("iron 1 dig", a.plain());
        MineRules.At w = MineRules.parseAt("Iron_Ore at 1 2 3 walk");
        assertEquals("iron_ore", w.word());
        assertEquals(1, w.count(), "no count: 1");
        assertFalse(w.dig());
        assertFalse(MineRules.parseAt("coal 2 at 1 2 3").dig(), "no word: walk");
    }

    @Test
    void notTheAtForm() {
        assertNull(MineRules.parseAt("iron 3 dig"));
        assertNull(MineRules.parseAt("cave iron 3"));
        assertNull(MineRules.parseAt("strip iron 3 at 1 2 3"), "strip has its own grammar");
        assertNull(MineRules.parseAt("attic_ore 3"), "\"at\" only as a word");
        assertNotNull(MineRules.parseAt("iron 1 at 1 2").error());
        assertNotNull(MineRules.parseAt("iron 1 at 1 2 3 fly").error());
        assertNotNull(MineRules.parseAt("iron 0 at 1 2 3").error());
        assertNotNull(MineRules.parseAt("iron 1 at 99999999999 2 3").error(), "review: no exception");
    }

    @Test
    void argsReadTheAtFormAsThePlainMine() {
        MineRules.Args a = MineRules.args("iron 1 at 4 5 6 dig");
        assertEquals("minecraft:iron", a.id());
        assertEquals(1, a.count());
        assertTrue(a.dig());
        assertFalse(MineRules.args("iron_ore 1 at 4 5 6 walk").dig());
    }

    @Test
    void theVeinFloodsTouchingCellsUpToTheCap() {
        Set<String> ore = Set.of("0 0 0", "1 0 0", "2 0 0", "2 1 0", "5 5 5", "1 1 1");
        List<int[]> v = MineRules.vein(new int[]{0, 0, 0}, c -> ore.contains(c[0] + " " + c[1] + " " + c[2]), MineRules.AT_VEIN);
        assertEquals(4, v.size(), "faces only: 1 1 1 and 5 5 5 don't touch");
        assertArrayEquals(new int[]{0, 0, 0}, v.get(0));
        List<int[]> big = MineRules.vein(new int[]{0, 0, 0}, c -> true, MineRules.AT_VEIN);
        assertEquals(8, big.size());
        assertEquals(1, MineRules.vein(new int[]{9, 9, 9}, c -> false, 8).size(), "the target itself always (dug already)");
    }

    @Test
    void theEndsAreAMiningEnd() {
        assertEquals("done: mined 3 iron at 1 2 3 - got 3 raw_iron", MineRules.atDoneText(3, "iron", "1 2 3", " - got 3 raw_iron"));
        assertTrue(MineRules.atFailText("minecraft:iron_ore", "1 2 3", "no path", "").contains("couldn't get to the iron_ore at 1 2 3"));
    }
}
