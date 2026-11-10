package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.CaveWalk;
import io.github.mojolowjo.entropybot.cave.Passages;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * sim (owner 2026-11-09: "reproduce in the simulator, fix, prove"; "slightly adjusting some values for possible other
 * errors"): run JBP-T1 23:10:48-23:19 - the torch order took "cave coal 1 at -56 108 -9 for 10 min" and caved 7 min for
 * nothing. The planner had priced that cave at 8.5 s: "known cave 12 m: inside it + the walk in the passages: 1 ore along
 * the 3 nearest of 110 open passage(s): 7 s (density not known: no placement fact and no ore seen, the fallback
 * cave.orePerCellNotKnown 0.01) + 1 ore broken 1.2 s -> 8.5 s (world-gen not known yet (not known yet: the cave's cells
 * and air-exposed blocks are still being read (645240 cells, 168994 air-exposed blocks so far)), explored 0%)". The cave's
 * count was still being READ; the fallback density is for an ore no placement places. An unknown still to come is no price
 * (rule 15; CaveYield: "the way waits, said").
 *
 * <p>The same need in the tests' data: raw_iron with a seen ore some metres off (the priced other way, as the charcoal
 * was) and the bot inside a cave whose count is not done. The values round the run's own are swept.
 */
class CaveNotReadScenariosTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** The run's words for the count not done (CaveYield.of). */
    static final String STILL_READ = "not known yet: the cave's cells and air-exposed blocks are still being read (645240 cells, 168994 air-exposed blocks so far)";

    static Passages.Passage passage(int id, int fromFeet, int len, int unseen) {
        int[] e = {id * 10, 108, 0};
        return new Passages.Passage(id, -1, e, e, len, 1, fromFeet, List.of(), unseen, 0, Passages.EndKind.DEAD_END);
    }

    static List<Passages.Passage> passages(int n, int unseen) {
        List<Passages.Passage> ps = new ArrayList<>();
        for (int i = 0; i < n; i++) ps.add(passage(i, 1 + i, 2, unseen));
        return ps;
    }

    /** The bot inside a cave at the feet; {@code expected} NaN + {@code basis} + no density: its count is still being read. */
    static CaveWay inside(double expected, String basis, CaveWalk.Density d, List<Passages.Passage> ps) {
        CaveWay cw = new CaveWay(8, 62, 1, 12, "minecraft:iron_ore", "iron", expected, 0.0, CaveIndex.Size.LARGE, -1, List.of(), List.of(new int[]{8, 62, 1}))
                .withPassages(ps).withInside(true).withBasis(basis);
        return d == null ? cw : cw.withDensity(d);
    }

    /** The iron world (J6bEhTest.iron) on the surface, with an iron ore seen {@code oreM} metres off and the cave {@code cw}. */
    static NeedChain.World world(double oreM, CaveWay cw) {
        ScenariosTest.world("wooden", "stone");
        var over = new NeedChain.Surface(0, 8, 62, 1, 0, true);
        return ScenariosTest.w(Map.of("minecraft:stone_pickaxe", 1), false, false, Map.of("raw_iron", List.of(new Spot((int) oreM + 8, 62, 1, oreM, "minecraft:iron_ore", 3))))
                .withMobs(x -> List.of(), Z77ExploreTest.book()).withStrip(word -> null)
                .withWhere(new NeedChain.WhereFacts(Z77ExploreTest::where, () -> over, () -> new int[]{8, 62, 1}))
                .withCave(w -> w.contains("raw_iron") || w.contains("iron_ore") ? cw : null);
    }

    static boolean caves(Chain c) { return c.steps().stream().anyMatch(s -> s instanceof Step.Cave); }

    /** The run's own numbers: 3 short passages of many unseen cells, the count not done, an ore seen 30 m off. */
    @Test
    void aCaveStillBeingReadIsNotPricedByTheFallback() {
        Chain c = NeedChain.resolve("raw_iron", 1, world(30, inside(Double.NaN, STILL_READ, null, passages(3, 50))));
        assertTrue(c.ok(), c.why());
        assertFalse(caves(c), "no caving step on a count not done: " + c.why());
        assertTrue(c.steps().stream().anyMatch(s -> s instanceof Step.Collect), "the seen ore is the way: " + c.steps());
        assertTrue(c.dropped().stream().anyMatch(d -> d.contains("known cave") && d.contains("still being read")), "the cave said, with its reason (rule 7): " + c.dropped());
    }

    /** The values round the run's, swept: however many passages, unseen cells, fallback density or metres to the seen ore. */
    @Test
    void sweptValues_neverACavingStepWhileItsCountIsRead() {
        List<String> wrong = new ArrayList<>();
        for (int n : new int[]{1, 3, 10, 110})
            for (int unseen : new int[]{1, 5, 50, 500, 5000})
                for (double fallback : new double[]{0.0001, 0.001, 0.01, 0.1, 1})
                    for (double oreM : new double[]{3, 30, 120, 400}) {
                        Tuning.reset();
                        Tuning.set("cave.orePerCellNotKnown", fallback);
                        Chain c = NeedChain.resolve("raw_iron", 1, world(oreM, inside(Double.NaN, STILL_READ, null, passages(n, unseen))));
                        if (!c.ok() || caves(c)) wrong.add(n + " passages x " + unseen + " unseen, fallback " + fallback + ", ore " + oreM + " m: " + (c.ok() ? "caves" : c.why()));
                    }
        assertTrue(wrong.isEmpty(), wrong.size() + " of 400 wrong, the first: " + wrong.stream().limit(3).toList());
    }

    /** Not vacuous: the same cave with its count DONE (game data: a density, an estimate) is priced, and wins when it is cheap. */
    @Test
    void theSameCaveWithItsCountDoneIsPricedAndCanWin() {
        var rich = CaveWalk.Density.of(y -> 0.05, 1000, 1000, 0, 0);           // 0.05 ore a cell: 150 cells hold 7.5
        Chain c = NeedChain.resolve("raw_iron", 1, world(120, inside(7.5, "game data: 7.5 iron ore expected", rich, passages(3, 50))));
        assertTrue(c.ok(), c.why());
        assertTrue(caves(c), "a known, cheap cave beats an ore 120 m off: " + c.why() + " | " + c.dropped());
    }

    /** An ore no placement places (no world-gen data at all) keeps the fallback, said: that is what it is for (job-price 2026-11-08). */
    @Test
    void noPlacementFactAtAllKeepsTheFallbackSaid() {
        Chain c = NeedChain.resolve("raw_iron", 1, world(400, inside(Double.NaN, "no world-gen data for iron_ore (test)", null, passages(3, 50))));
        assertTrue(c.ok(), c.why());
        assertTrue(caves(c), "priced by the fallback: " + c.why() + " | " + c.dropped());
        assertTrue(c.why().contains("cave.orePerCellNotKnown"), "and it says so: " + c.why());
    }
}
