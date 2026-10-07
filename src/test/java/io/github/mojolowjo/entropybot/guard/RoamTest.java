package io.github.mojolowjo.entropybot.guard;

import io.github.mojolowjo.entropybot.vocab.ExploreWords;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 0.23.1 (VOCABULARY 6b): the roaming permission of a running explore/find, and the base rule. */
class RoamTest {
    static final String OW = "minecraft:overworld";

    static GuardCore guard() {
        GuardCore g = new GuardCore();
        g.setMode(GuardCore.Mode.STRICT);
        g.setPolicy(Policy.parse("{\"areas\":[{\"name\":\"home\",\"dim\":\"" + OW + "\",\"x1\":0,\"z1\":0,\"x2\":31,\"z2\":31}],"
                + "\"protect\":[{\"name\":\"house\",\"dim\":\"" + OW + "\",\"x1\":290,\"y1\":60,\"z1\":0,\"x2\":299,\"y2\":80,\"z2\":9}]}"));
        return g;
    }

    @Test
    void roamingWalksAndBreaksNaturalBlocksNearTheBotOnly() {
        GuardCore g = guard();
        assertFalse(g.checkBoxes(OW, 300, 64, 300, "go").allowed(), "outside the areas: no roam, no walk");
        AtomicBoolean alive = new AtomicBoolean(true);
        int[] at = {300, 64, 300};
        List<int[]> bases = new ArrayList<>();
        g.setRoam(new GuardCore.Roam("explore", OW, at, bases, alive::get));
        assertTrue(g.checkBoxes(OW, 310, 64, 310, "go").allowed(), "within 24 of the bot");
        assertFalse(g.checkBoxes(OW, 330, 64, 300, "go").allowed(), "30 blocks off");
        // a break needs the roam lease (the clear engine takes one), only for a break box near the bot
        assertFalse(g.checkBoxes(OW, 305, 64, 305, "break").allowed(), "no lease yet");
        String id = g.lease("clear", "tree", new Box(null, OW, 305, 64, 305, 305, 70, 305), false, false);
        assertTrue(id.startsWith("L"), id);
        assertTrue(g.leases().get(id).roam);
        assertTrue(g.check(OW, 305, 66, 305, "break", GuardCore.BlockInfo.PLAIN).allowed());
        assertFalse(g.check(OW, 305, 66, 305, "break", GuardCore.BlockInfo.of(true, false, true)).allowed(), "built blocks: the floor");
        assertFalse(g.check(OW, 305, 66, 305, "break", GuardCore.BlockInfo.of(true, true, false)).allowed(), "containers: the floor");
        assertTrue(g.lease("clear", "far", new Box(null, OW, 340, 64, 300, 340, 64, 300), false, false).startsWith("error"), "a box past 24");
        assertTrue(g.lease("clear", "place", new Box(null, OW, 305, 64, 305, 305, 64, 305), true, false).startsWith("error"), "no place leases");
        // placement: torches only
        assertTrue(g.check(OW, 306, 64, 306, "place", GuardCore.BlockInfo.placing(true, true)).allowed(), "a torch");
        assertFalse(g.check(OW, 306, 64, 306, "place", GuardCore.BlockInfo.placing(true, false)).allowed(), "a sapling or a block");
        // the bot moves: the permission moves with it
        at[0] = 400;
        assertFalse(g.checkBoxes(OW, 310, 64, 310, "go").allowed());
        assertTrue(g.checkBoxes(OW, 410, 64, 300, "go").allowed());
        // a base it saw: 16 off
        bases.add(new int[]{410, 64, 300});
        assertFalse(g.checkBoxes(OW, 410, 64, 300, "go").allowed(), "near a base");
        assertTrue(g.checkBoxes(OW, 380, 64, 300, "go").allowed(), "away from it");
        // the job ends: nothing left
        alive.set(false);
        assertNull(g.roam());
        assertFalse(g.checkBoxes(OW, 380, 64, 300, "go").allowed());
    }

    @Test
    void protectBoxesAndTheNetherStillWin() {
        GuardCore g = guard();
        g.setRoam(new GuardCore.Roam("explore", OW, new int[]{295, 64, 5}, new ArrayList<>(), () -> true));
        assertFalse(g.checkBoxes(OW, 295, 64, 5, "break").allowed(), "a safe area");
        assertTrue(g.lease("clear", "x", new Box(null, OW, 295, 64, 5, 295, 64, 5), false, false).startsWith("error"));
        assertFalse(g.checkBoxes("minecraft:the_nether", 295, 64, 5, "go").allowed());
        g.endRoam("explore");
        assertNull(g.roam());
    }

    @Test
    void theBaseRuleAndTheReport() {
        assertTrue(ExploreWords.isBase(new ExploreWords.Scan(6, 0, 0)), "6 built blocks");
        assertFalse(ExploreWords.isBase(new ExploreWords.Scan(5, 0, 0)));
        assertTrue(ExploreWords.isBase(new ExploreWords.Scan(0, 1, 0)), "any container");
        Map<String, Integer> took = new LinkedHashMap<>();
        assertEquals("", ExploreWords.took(took));
        took.put("minecraft:oak_log", 12);
        took.put("minecraft:coal", 4);
        assertEquals("; took 12 oak_log, 4 coal", ExploreWords.took(took));
        assertFalse(ExploreWords.parse("north 5 gather off", 5).gather());
        assertEquals("north", ExploreWords.parse("north 5 gather off", 5).dir());
        assertTrue(ExploreWords.parse("north", 5).gather());
        assertFalse(ExploreWords.find("cave gather off", List.of(), id -> false).gather());
    }
}
