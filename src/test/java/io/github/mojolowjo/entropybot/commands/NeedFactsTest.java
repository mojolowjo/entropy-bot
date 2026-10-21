package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.BedOptions;
import io.github.mojolowjo.entropybot.aa.StationOptions;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AA_ROUND4A O: the facts come from the filled Inputs and its bag (no new scans). */
class NeedFactsTest {
    @Test
    void stationBaseBagAndBedReadTheInputs() {
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
        var t = NeedFacts.station(StationOptions.Kind.TABLE, in, in.bag, null);
        assertTrue(t.known());
        assertEquals(2, t.logs());
        assertTrue(NeedFacts.station(StationOptions.Kind.CHEST, in, in.bag, null).inBag());
        assertEquals(5 + 3 + 70, NeedFacts.base(in, in.bag).blocks());
        assertEquals(3, NeedFacts.bag(in, in.bag, List.of("minecraft:dirt", "minecraft:cobblestone"), 1).junkSlots());
        BedOptions.BedFacts b = NeedFacts.bed(new BedOptions.BedFacts(0, 0, null, null), in);
        assertTrue(b.bedKnown() && b.night() && b.under());
        assertEquals(7, b.depth());
        assertNull(NeedFacts.bed(null, in), "no bed facts read: none (the Picker says so)");
    }
}
