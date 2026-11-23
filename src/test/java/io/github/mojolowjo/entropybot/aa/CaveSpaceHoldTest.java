package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.WorldFacts;
import io.github.mojolowjo.entropybot.aa.core.Inputs;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-planner-stick (worlds run 2, w060 04:46-04:52): "cave coal 1 at -8 92 -3 for 10 min" timed out after 600 s; the hold
 * named the entry -8,92,-3 (the opening's key), and the same cave record was entered five more times from other known
 * caves of it (-101 92 162, -173 96 95, ...) at "2.3 s" each, the bot inside it, while charcoal stood at 32.3 s. A caving's
 * end holds the cave SPACE (the record): every known cave of it is out for the family, from every spot, inside or not,
 * and the world fact does not lift by the bot's distance from where the step ended.
 */
class CaveSpaceHoldTest {
    @AfterEach
    void clear() {
        FactChange.clear();
        Tuning.reset();
    }

    static final String SPACE = "-8,92,-3";

    static CaveWay cave(int x, int y, int z, double dist, String space, boolean inside) {
        return new CaveWay(x, y, z, dist, "minecraft:coal_ore", "coal", Double.NaN, Double.NaN, CaveIndex.Size.LARGE, 8, List.of(),
                List.of(new int[]{x, y, z})).withSpace(space).withInside(inside);
    }

    /** The w060 list: the cave the bot stands in and the entry the step went in by are one record; a third cave is another space. */
    static CaveWay w060() {
        CaveWay inside = cave(-101, 92, 162, 7, SPACE, true), entered = cave(-8, 92, -3, 166, SPACE, false), other = cave(0, 67, 61, 42, null, false);
        return inside.withMore(List.of(entered, other));
    }

    @Test
    void aCavingsEnd_holdsTheWholeSpace_fromEverySpot_insideOrNot() {
        int[] entry = {-8, 92, -3};
        String held = NeedChain.sourceKey(new Step.Cave(-8, 92, -3, "coal", 1, 10));
        assertEquals(CaveWay.cavedKey("coal", entry), held, "the step watch's timeout holds the caving's own key");
        assertNotEquals(CaveWay.keyOf(entry), held, "never the opening's key");
        assertEquals("source " + held, FactChange.caveMinutesFact(entry, "coal", 0, 131, "10 min").key(), "the step's own ends hold the same key");

        CaveWay left = w060().without(Set.of(held)::contains);
        assertNotNull(left);
        List<CaveWay> all = left.all();
        assertEquals(3, all.size(), "every cave stays in the list, the held ones with their reason (rule 7): " + all);
        assertNotNull(NeedChain.caveOut(all.get(0)), "the cave the bot is inside is the same space: out");
        assertTrue(all.get(0).out().contains("the space is held for coal from every spot") && all.get(0).out().contains("entered at -8 92 -3"), all.get(0).out());
        assertNotNull(NeedChain.caveOut(all.get(1)), "the entered cave: out");
        assertNull(NeedChain.caveOut(all.get(2)), "another space is offered");
        assertEquals(List.of(0, 67, 61), List.of(all.get(2).x(), all.get(2).y(), all.get(2).z()));
    }

    /**
     * Review round 2: a caving stopped by its minutes or its price WITH ore mined (a large cave, 4 of 6 mined in the
     * round) holds the opening only, as before the job: the cave the bot stands in is caved on, never out for the family.
     */
    @Test
    void aStoppedCavingThatGaveOre_holdsItsOpeningOnly_theSpaceStaysOffered() {
        int[] entry = {-8, 92, -3};
        for (FactChange.Fact f : List.of(FactChange.caveMinutesFact(entry, "coal", 4, 131, "10 min"), FactChange.caveDearerFact(entry, "coal", 4, 131, "far over"))) {
            assertEquals("source " + CaveWay.keyOf(entry), f.key(), "ore mined: the opening's key");
            String held = FactChange.ownSourceKey(f);
            assertEquals(CaveWay.keyOf(entry), held);
            CaveWay left = w060().without(Set.of(held)::contains);
            assertNull(NeedChain.caveOut(left.all().get(0)), "the cave the bot is inside goes on: " + left.all());
        }
        assertEquals("source " + CaveWay.cavedKey("coal", entry), FactChange.caveDearerFact(entry, "coal", 0, 131, "far over").key(), "no ore: the space");
        assertEquals("source " + CaveWay.cavedKey("coal", entry), FactChange.caveEndedFact(entry, "coal", "DONE", "explored to its end").key(), "an end of the cave itself: the space");
    }

    @Test
    void anOpeningsHold_orAnotherFamilysCaving_leavesTheSpaceOffered() {
        // the lip of -8 92 -3 has no path (the opening's key): that opening only - the space is entered from where the bot stands
        CaveWay lip = w060().without(Set.of(CaveWay.keyOf(new int[]{-8, 92, -3}))::contains);
        assertEquals(2, lip.all().size(), "the cave whose one opening is held leaves the list: " + lip.all());
        assertNull(NeedChain.caveOut(lip.all().get(0)), "the same space from the inside is still a way");
        // iron's caving ended there: coal's caving of the space is another fact
        CaveWay iron = w060().without(Set.of(CaveWay.cavedKey("iron", new int[]{-8, 92, -3}))::contains);
        for (CaveWay k : iron.all()) assertNull(NeedChain.caveOut(k), "offered for coal: " + k);
        // a cave given no space is its own: its caving's end holds itself only
        CaveWay own = w060().without(Set.of(CaveWay.cavedKey("coal", new int[]{0, 67, 61}))::contains);
        assertNull(NeedChain.caveOut(own.all().get(0)));
        assertNotNull(NeedChain.caveOut(own.all().get(2)));
        // nothing but held spaces left: no cave way, as every cave held
        assertNull(cave(-8, 92, -3, 166, SPACE, false).withMore(List.of(cave(-101, 92, 162, 7, SPACE, true))).without(Set.of(CaveWay.cavedKey("coal", new int[]{-8, 92, -3}))::contains));
    }

    /** The world fact: a caving's end does not lift when the bot walks on (the space reaches past failures.moved); an opening's does. */
    @Test
    void theCavingsWorldFact_doesNotLiftByDistance_anOpeningsDoes() {
        WorldFacts w = new WorldFacts();
        Inputs in = new Inputs();
        in.pos = new int[]{-108, 111, 128};
        in.bag = Map.of();
        String caved = CaveWay.cavedKey("coal", new int[]{-8, 92, -3}), lip = CaveWay.keyOf(new int[]{-8, 92, -3});
        assertTrue(w.learned(caved, "timed out after 600 s", "oak_log", in));
        assertTrue(w.learned(lip, "the walk to the cave's lip failed", "oak_log", in));
        assertEquals(Set.of(caved, lip), w.heldFor(Set.of(), in, null).keySet(), "both held where they were learned");
        Inputs far = new Inputs();
        far.pos = new int[]{-187, 71, 84};                      // w060: where the next caving step ended, 100 blocks on
        far.bag = Map.of();
        assertEquals(Set.of(caved), w.heldFor(Set.of(), far, null).keySet(), "the caving's end still holds; the opening's lifted by distance");
        assertEquals(Set.of(caved), w.heldFor(Set.of(), in, far.pos, null).keySet(), "the same read at an anchor");
        String said = w.sayOnce("torch 1", "torch", caved, "timed out after 600 s");
        assertTrue(said.contains("held for this run") && said.contains("the cave space"), said);
    }
}
