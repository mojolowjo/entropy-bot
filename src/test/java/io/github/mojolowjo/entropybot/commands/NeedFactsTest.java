package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.StationOptions;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND4A O: the facts come from the filled Inputs and its bag (no new scans). */
class NeedFactsTest {
    @Test
    void stationBaseAndBagReadTheInputs() {
        Inputs in = new Inputs();
        in.planks = 3;
        in.wood = 2;
        in.cobble = 5;
        in.tableKnown = true;
        in.night = true;
        in.underGround = true;
        in.underDepth = 7;
        in.bedKnown = true;
        in.bag = Map.of("chest", 1, "dirt", 70, "cobblestone", 5);
        var t = NeedFacts.station(StationOptions.Kind.TABLE, in, in.bag);
        assertTrue(t.known());
        assertTrue(t.under() && t.depth() == 7);
        assertTrue(NeedFacts.station(StationOptions.Kind.CHEST, in, in.bag).inBag());
        assertEquals(5 + 3 + 70, NeedFacts.base(in, in.bag, null, false).blocks());
        assertEquals(3, NeedFacts.bag(in, in.bag, List.of("minecraft:dirt", "minecraft:cobblestone"), 1).junkSlots());
        assertEquals("drop dirt 70 > drop cobblestone 5", io.github.mojolowjo.entropybot.aa.moves.Step.label(NeedFacts.bag(in, in.bag, List.of("minecraft:dirt", "minecraft:cobblestone"), 1).junkDrops()));
        assertNull(NeedFacts.station(StationOptions.Kind.TABLE, in, in.bag).knownAt(), "a near table has no cell: the row is dropped, never a walk to nowhere");
        assertTrue(NeedFacts.base(in, in.bag, null, false).danger() > 0, "the night danger from Costs");
    }
}
