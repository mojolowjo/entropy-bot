package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.aa.NeedChain;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.PlanNodes;
import io.github.mojolowjo.entropybot.aa.Playbook;
import io.github.mojolowjo.entropybot.aa.brain.StripOrder;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z78 strip (run DS 15:14:33-36, to-do/diag-strip-heading-hold.md): the playbook's joined strip (iron and coal ores in
 * one tunnel) met water ahead west at y 16; the hold was the joined ore key, which no strip way asked, so the re-plan struck
 * west again. Now every strip hold is its heading key (NeedChain.stripHold): the next plan strips north, said in the dropped
 * list; every heading held: no strip, with the holds.
 */
class Z78StripHeadingHoldTest {
    @BeforeEach
    void setUp() {
        D6WorkerTest.data();
        PlanNodes.forgetMine();
        FactChange.clear();
    }

    /** The tests' strip facts, every heading open, west first (the bot faced west after a tunnel, as in DS). */
    static World world() {
        Function<String, StripBand> st = word -> {
            StripBand b = D6WorkerTest.strip(word);
            return b == null ? null : new StripBand(b.ores(), b.minY(), b.maxY(), b.y(), b.perSlab(), b.x(), b.z(), b.feetY(), "west", b.host(),
                    StripOrder.headingOrder(null, "west"));
        };
        return new D6WorkerTest.Live().world().withStrip(st);
    }

    static Step.Strip strip(NeedChain.Legs l) {
        return (Step.Strip) l.chain().steps().stream().filter(s -> s instanceof Step.Strip).findFirst().orElse(null);
    }

    static NeedChain.Legs plan(World w) {
        Playbook pb = Playbook.derive(D6WorkerTest.KIT, Playbook.facts(w, null));
        assertTrue(pb.ok(), pb.why());
        return PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(pb, w));
    }

    @Test
    void waterAheadWestAtY16_holdsTheHeading_theNextPlanStripsNorth_saidInTheDropped() {
        World w = world();
        NeedChain.Legs first = plan(w);
        Step.Strip west = strip(first);
        assertNotNull(west, first.chain().why());
        assertEquals("west", west.heading(), "first: west (the facing)");
        assertEquals(16, west.y());
        // DS's joined four-ore key and this one alike: the hold is the heading (the ores are not in it)
        assertEquals(NeedChain.headingKey(16, "west"),
                NeedChain.stripHold("minecraft:coal_ore,minecraft:deepslate_coal_ore,minecraft:deepslate_iron_ore,minecraft:iron_ore|16|west"));
        assertEquals(NeedChain.headingKey(16, "west"), NeedChain.stripHold(west.key()));
        // the fork's end words of DS 15:14:33.561, as StripStep holds them
        FactChange.strip(west.key(), "water ahead at 4 16 3", "strip: water ahead at 4 16 3 (strip west feet y 16: 1 blocks)");
        FactChange.Fact f = FactChange.take();
        assertEquals("source " + NeedChain.headingKey(16, "west"), f.key(), "the heading key, the one strip hold");
        assertEquals(NeedChain.headingKey(16, "west"), FactChange.ownSourceKey(f));
        NeedChain.Legs again = plan(w.withoutSources(Map.of(FactChange.ownSourceKey(f), f.text())));
        Step.Strip north = strip(again);
        assertNotNull(north, again.chain().why());
        assertEquals("north", north.heading(), "the next heading in StripOrder's order (clockwise from west)");
        String why = again.chain().why();
        assertTrue(why.contains("strip west at y 16 held: ") && why.contains("water ahead at 4 16 3") && why.contains("; north next"), why);
    }

    @Test
    void everyHeadingHeld_noStrip_withTheHolds() {
        World w = world();
        Map<String, String> all = new HashMap<>();
        for (String h : StripOrder.HEADINGS) all.put(NeedChain.headingKey(16, h), "water ahead (test)");
        World held = w.withoutSources(all);
        // the band's strip way is left out (every heading held): the derivation says so with the holds (rule 7)
        Playbook pb = Playbook.derive(D6WorkerTest.KIT, Playbook.facts(held, null));
        assertFalse(pb.ok());
        assertTrue(pb.why().contains("every heading of its band is held: " + NeedChain.headingKey(16, "west") + ": water ahead (test)"), pb.why());
        // and the trip's own strip leg, given a derivation, drops every held heading with its reason
        Playbook open = Playbook.derive(D6WorkerTest.KIT, Playbook.facts(w, null));
        NeedChain.Legs l = PlanNodes.old(() -> NeedChain.resolvePlaybookLegs(open, held));
        assertNull(strip(l), l.chain().why());
        assertTrue(l.chain().why().contains("every heading is held"), l.chain().why());
    }
}
