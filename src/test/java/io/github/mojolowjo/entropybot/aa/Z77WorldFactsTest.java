package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.StripBand;
import io.github.mojolowjo.entropybot.aa.brain.Failures;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.OreBand;
import io.github.mojolowjo.entropybot.aa.brain.StripOrder;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.WorldFacts;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.Steps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z77 world (manager's ruling 2026-10-26 ~23:00; run CX on z77z, docs/runs/runCX-z77z): the pickaxe's strips met water
 * ahead (22:37:58 west at y 17 from the feet 9 17 1; 22:42:18 north at y 16 from 16 16 -23), the pickaxe order was met at
 * 22:42:19 and its held sources went with it, and the helmet ran both strips again at 22:43:18 / 22:43:21 (feet 10 17 -16) to
 * the same water. A source fact one order learned is the world's: it holds for every other order while the feet are within
 * failures.moved (16) of where it was learned. 0.39.z77 world 2 (manager 2026-10-26): a strip fact is about its tunnel key
 * (the fork resumes the saved tunnel from anywhere) and never lifts on the distance - only its cause gone, or a strip from
 * fresh ground at another spot (another key). Hand distances: 16 16 -23 -> 10 17 -16 = sqrt(36 + 1 + 49) = 9.3;
 * 9 17 1 -> 9 17 -8 = 9; 9 17 1 -> 10 17 -16 = sqrt(1 + 0 + 289) = 17.0 (CX 22:43:21: past failures.moved, still held).
 */
class Z77WorldFactsTest {

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    static final List<String> IRON = Z62RunSTest.IRON;

    /** A strip band at feet y {@code y}, {@code first} the first heading (the facing), every heading open. */
    static StripBand band(int y, String first) {
        List<OreBand.Feature> fs = Z43StripWayTest.iron();
        return new StripBand(IRON, -64, 384, y, OreBand.perSlab(fs, y), 9, 1, y, first, "minecraft:stone", StripOrder.headingOrder(null, first));
    }

    static Blackboard bb(StripBand b, Clock clock) {
        Blackboard bb = new Blackboard(clock, new FakePort(), new Tracer());
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> Z62RunSTest.world(Map.of("minecraft:stone_pickaxe", 1), b));
        return bb;
    }

    static String heading(Chain c) {
        assertNotNull(c);
        return c.steps().stream().filter(s -> s instanceof Step.Strip).map(s -> ((Step.Strip) s).heading()).findFirst().orElse(null);
    }

    /** The pickaxe order re-plans on its strip's water ahead (FactChange.strip, the fork's own end words as in CX). */
    static void waterAhead(Blackboard bb, Goals.Goal pickaxe, int y, String heading, String water) {
        Step.Strip st = new Step.Strip(IRON, Map.of("iron", 3), y, heading, 0);
        String why = "strip: water ahead at " + water + " (strip " + heading + " feet y " + y + ": 6 blocks, 0 clusters mined)";
        FactChange.strip(st.key(), why);
        Steps.Routine r = Goals.replan(bb, pickaxe, st, FactChange.take(), why);
        assertNotNull(r.steps(), r.why());
        assertTrue(Goals.droppedSources(pickaxe).containsKey(NeedChain.STRIP_KEY + st.key()), "the learner's own hold, as before");
    }

    /** CX 22:43:21 (the scenario): the met pickaxe's water-ahead heading at y 17 west is not re-tried by the helmet. */
    @Test
    void anotherOrdersWaterAheadHeadingIsNotTriedAgain() {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = bb(band(17, "west"), clock);
        bb.in.pos = new int[]{9, 17, 1};                                   // CX 22:37:58: the pickaxe's feet at the water
        Goals.Goal pickaxe = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, clock.now());
        assertEquals("west", heading(Goals.chain(bb, pickaxe)), "first: west (the facing)");
        waterAhead(bb, pickaxe, 17, "west", "8 17 0");
        // the pickaxe order is met and gone (CX 22:42:19); a later order of the run asks from 9 blocks on
        bb.in.pos = new int[]{9, 17, -8};
        Goals.Goal helmet = Goals.draft(DebugTreeTest.item("iron_ore"), 5, 0, clock.now());
        assertTrue(Goals.droppedSources(helmet).isEmpty(), "nothing of the helmet's own is held");
        Chain c = Goals.chain(bb, helmet);
        assertNotEquals("west", heading(c), "the strip west at y 17 is out for the helmet too: " + c.why());
        assertTrue(c.why().contains("water ahead at 8 17 0") && c.why().contains("(a world fact: minecraft:raw_iron learned it at 9 17 1)"), c.why());
        FakePort port = (FakePort) bb.port;
        long said = port.log.stream().filter(l -> l.contains("held as a world fact") && l.contains("|17|west") && l.contains("only when its cause is gone")).count();
        assertEquals(1, said, port.log.toString());
        assertTrue(port.log.stream().anyMatch(l -> l.startsWith("minecraft:iron_ore: strip ") && l.contains("water ahead at 8 17 0")
                && l.contains("minecraft:raw_iron learned it at 9 17 1")), port.log.toString());
        Goals.chain(bb, helmet);
        assertEquals(1, port.log.stream().filter(l -> l.contains("held as a world fact")).count(), "said once per order and key");
    }

    /** CX 22:43:21 exactly: the helmet at 10 17 -16, 17.0 blocks from where the y 17 west strip met water - still not re-tried. */
    @Test
    void theStripFactHoldsPastFailuresMoved() {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = bb(band(17, "west"), clock);
        bb.in.pos = new int[]{9, 17, 1};
        Goals.Goal pickaxe = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, clock.now());
        waterAhead(bb, pickaxe, 17, "west", "8 17 0");
        assertEquals(16.0, Failures.movedV());
        bb.in.pos = new int[]{10, 17, -16};                               // sqrt(1 + 289) = 17.03 > 16
        Goals.Goal helmet = Goals.draft(DebugTreeTest.item("iron_ore"), 5, 0, clock.now());
        Chain c = Goals.chain(bb, helmet);
        assertNotEquals("west", heading(c), "the tunnel key is the fact, not the feet: " + c.why());
        assertTrue(c.why().contains("(a world fact: minecraft:raw_iron learned it at 9 17 1)"), c.why());
    }

    /** The control: a strip from fresh ground at another spot (StripBand.freshAt: another key) is tried. */
    @Test
    void aFreshStartStripFromAnotherSpotIsTried() {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = bb(band(17, "west"), clock);
        bb.in.pos = new int[]{9, 17, 1};
        Goals.Goal pickaxe = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, clock.now());
        waterAhead(bb, pickaxe, 17, "west", "8 17 0");
        StripBand fresh = band(17, "west").freshAt(9, -40, 41, "west");     // fresh ground 41 blocks on
        assertNotEquals(band(17, "west").key(), fresh.key());
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> Z62RunSTest.world(Map.of("minecraft:stone_pickaxe", 1), fresh));
        Goals.Goal helmet = Goals.draft(DebugTreeTest.item("iron_ore"), 5, 0, clock.now());
        Chain c = Goals.chain(bb, helmet);
        Step.Strip st = (Step.Strip) c.steps().stream().filter(s -> s instanceof Step.Strip).findFirst().orElse(null);
        assertNotNull(st, c.why());
        assertEquals("west", st.heading(), c.why());
        assertNotNull(st.from(), "the fresh start's own tunnel: " + st.text());
    }
    /** CX 22:43:18: the north strip at y 16 (learned at 16 16 -23), the helmet at 10 17 -16 (9.3 blocks): held. */
    @Test
    void theCxNorthStripIsHeldFromTheHelmetsFeet() {
        ScenariosTest.world("wooden", "stone");
        Clock.Fake clock = new Clock.Fake(10_000_000);
        Blackboard bb = bb(band(16, "north"), clock);
        bb.in.pos = new int[]{16, 16, -23};
        Goals.Goal pickaxe = Goals.draft(DebugTreeTest.item("raw_iron"), 3, 0, clock.now());
        waterAhead(bb, pickaxe, 16, "north", "18 16 -23");
        bb.in.pos = new int[]{10, 17, -16};
        Goals.Goal helmet = Goals.draft(DebugTreeTest.item("iron_ore"), 5, 0, clock.now());
        Chain c = Goals.chain(bb, helmet);
        assertNotEquals("north", heading(c), c.why());
        assertTrue(c.why().contains("water ahead at 18 16 -23") && c.why().contains("(a world fact: minecraft:raw_iron learned it at 16 16 -23)"), c.why());
    }

    /** The store's own rules: what is shared, what lifts it. */
    @Test
    void sharedLiftedAndNotShared() {
        WorldFacts w = new WorldFacts();
        Inputs in = new Inputs();
        assertFalse(w.learned("collect raw_iron", "no progress", "goal.a", in), "no feet known: no world position to key it by");
        in.pos = new int[]{20, 28, 19};
        assertFalse(w.learned(NeedChain.CLIMB_KEY, "the climb up failed", "goal.a", in), "the climb's one more try is per order");
        in.bag = Map.of("cobblestone", 5);
        assertTrue(w.learned("collect raw_iron at 20 28 19", "no progress at 20 28 19", "a", in));     // run CS 19:33
        assertTrue(w.learned("collect torch", "i have no coal", "a", in));
        Set<String> own = Set.of("collect raw_iron at 20 28 19", "collect torch");
        assertEquals(Map.of(), w.heldFor(own, in), "the learner keeps its own holds (its own rules), not these");
        assertEquals(2, w.heldFor(Set.of(), in).size(), "any other order - aa want of the same item again too (same id, none of its own)");
        assertTrue(w.heldFor(Set.of(), in).get("collect raw_iron at 20 28 19").startsWith("no progress at 20 28 19 (a world fact: a learned it at 20 28 19)"));
        in.bag = Map.of("cobblestone", 5, "coal", 1);                       // the cause gone: the bag's total rose
        assertEquals(List.of("collect raw_iron at 20 28 19"), List.copyOf(w.heldFor(Set.of(), in).keySet()));
        in.pos = new int[]{20, 28, 36};                                     // 17 blocks off
        assertTrue(w.heldFor(Set.of(), in).isEmpty());
        String strip = NeedChain.STRIP_KEY + "minecraft:deepslate_iron_ore,minecraft:iron_ore|17|west", heading = NeedChain.headingKey(17, "west");
        in.pos = new int[]{9, 17, 1};
        assertTrue(w.learned(strip, "strip: water ahead at 8 17 0", "a", in));
        assertTrue(w.learned(heading, "blocked at its start", "a", in));
        in.pos = new int[]{9, 17, 200};                                     // far off: a strip fact never lifts on distance
        assertEquals(java.util.Set.of(strip, heading), w.heldFor(Set.of(), in).keySet());
        assertTrue(w.sayOnce("o", "helmet", strip, "x").contains("only when its cause is gone"));
        in.pos = null;
        in.bag = Map.of("cobblestone", 5);
        assertEquals(4, w.heldAt(in).size(), "feet not known: held (rule 15)");
    }
}
