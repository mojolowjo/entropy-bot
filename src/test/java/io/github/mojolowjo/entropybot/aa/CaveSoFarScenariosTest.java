package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.CaveWay;
import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.CaveYield;
import io.github.mojolowjo.entropybot.aa.brain.GenWhere;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.brain.Unit;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.cave.CaveWalk;
import io.github.mojolowjo.entropybot.cave.Passages;
import io.github.mojolowjo.entropybot.sense.CaveIndex;
import io.github.mojolowjo.entropybot.sense.CaveRecord;
import io.github.mojolowjo.entropybot.sense.Sightings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * sim (owner 2026-11-10: "Yes lets do those tests. Adjust the values slightly again to confirm after it succeeds for a
 * little bit wider testing"): the two cave pieces, each reproduced here first on the old code (a count partly read gave
 * no estimate: CaveYield.of "not known yet"; CaveWalk.next walked on 7 min into an 8.5 s price), then fixed.
 * <ol>
 * <li>count-so-far (run JBP-T1 23:10:48: 645,240 of 666,540 cells read, 97%, and "not known yet"): the estimate on the
 * part counted while the count runs - a floor that only rises, never "no source".</li>
 * <li>cave-guard (the same run: a walk priced 8.5 s ran 7 min for 0 ore): the step ends DEARER once it is
 * cave.dearerTimes its price inside with the need unmet; the cave's source fact, the order planned again.</li>
 * </ol>
 */
class CaveSoFarScenariosTest {
    static final String D = "minecraft:overworld";

    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** A 2 x 2 tube at {@code y}, {@code len} long, known as a cave, nothing counted yet. */
    static Sightings tube(int y, int len) {
        Set<Long> t = J6cCaveYieldTest.tube(0, y, len);
        Sightings s = new Sightings();
        assertNotNull(s.knowCave(D, new CaveIndex.Known(0, y, 0, CaveIndex.Size.SMALL, CaveIndex.Depth.STONE, List.of(Sightings.cell(0, y, 0))), 1));
        s.caveCells(D, 0, y, 0, t);
        return s;
    }

    static void read(Sightings s, int y, int len, int budget) { s.caveExpose(D, 0, y, 0, J6cCaveYieldTest.airOf(J6cCaveYieldTest.tube(0, y, len)), budget); }

    static CaveYield.Prior prior(Sightings s, int y, GenWhere.Fact where) {
        CaveRecord.Exposure ex = s.caveExposure(D, 0, y, 0);
        return CaveYield.of("raw_iron", where, ex.byY(), ex.blocks(), ex.open(), ex.done(), s.caveFacts(D, 0, y, 0).cellCount(), Unit.walkBps(), ex.looked());
    }

    static CaveRecord.Facts facts(int cells, int unseen) {
        return new CaveRecord.Facts(0, 40, 0, cells, unseen, (cells - unseen) / (double) cells, 0, false, Map.of(), 0, Double.NaN, 0, true, "x");
    }

    static Passages.Passage passage(int id, int fromFeet, int len, int unseen) {
        int[] e = {id * 10 + 20, 40, 0};
        return new Passages.Passage(id, -1, e, e, len, 1, fromFeet, List.of(), unseen, 0, Passages.EndKind.DEAD_END);
    }

    static final List<Passages.Passage> THREE = List.of(passage(0, 1, 2, 50), passage(1, 2, 2, 50), passage(2, 3, 2, 50));
    static final int[] FEET = {0, 40, 0};

    static CaveWalk.Next step(int mined, int need, double minutesIn, List<int[]> left, double planS) {
        return CaveWalk.next(mined, need, minutesIn, 10, facts(645_240, 640_000), THREE, left, FEET, 4.5, null, Set.of(), false, Set.of(), null, null, planS);
    }

    // ---- 1. count-so-far ----

    @Test
    void aCountPartlyRead_isEstimatedOnThePartCounted() {
        Sightings s = tube(16, 2600);
        read(s, 16, 2600, 20_000);
        CaveRecord.Exposure ex = s.caveExposure(D, 0, 16, 0);
        assertFalse(ex.done(), "the count is still being read");
        assertTrue(ex.looked() > 0 && ex.looked() < 10_400, "some of its 10,400 cells looked round: " + ex.looked());
        CaveYield.Prior p = prior(s, 16, J6cCaveYieldTest.iron());
        assertTrue(p.known() && p.soFar(), p.why());
        assertTrue(p.why().startsWith(CaveYield.SO_FAR) && p.why().contains("at least this"), "said so far (rule 7): " + p.why());
        assertNull(CaveYield.noSource(p, "raw_iron", 0));
        read(s, 16, 2600, Integer.MAX_VALUE);
        CaveYield.Prior end = prior(s, 16, J6cCaveYieldTest.iron());
        assertTrue(end.known() && !end.soFar(), end.why());
        assertTrue(p.expected() > 0 && p.expected() < end.expected(), "a floor: " + p.expected() + " of " + end.expected());
    }

    /** Nothing counted yet is still "not known yet" (rule 15): the count-not-read rule holds until a block is counted. */
    @Test
    void nothingCountedYet_isStillNotKnown() {
        Sightings s = tube(16, 2600);
        CaveYield.Prior p = prior(s, 16, J6cCaveYieldTest.iron());
        assertFalse(p.known(), p.why());
        assertTrue(p.why().startsWith(CaveYield.NOT_YET), p.why());
    }

    /** Swept: tube lengths, Ys in and off the iron band, read budgets - the estimate so far never falls and never passes the end's. */
    @Test
    void sweptReads_theEstimateSoFarOnlyRisesToTheEnds() {
        List<String> wrong = new ArrayList<>();
        int cases = 0;
        for (int len : new int[]{3, 40, 700, 2600})
            for (int y : new int[]{-40, 0, 16, 50, 100, 200})
                for (int budget : new int[]{1, 7, 100, 999, 5000, 20_000}) {
                    cases++;
                    String at = "len " + len + " y " + y + " budget " + budget;
                    Sightings s = tube(y, len);
                    Sightings whole = tube(y, len);
                    read(whole, y, len, Integer.MAX_VALUE);
                    CaveYield.Prior end = prior(whole, y, J6cCaveYieldTest.iron());
                    if (!end.known() || end.soFar()) { wrong.add(at + ": the end not known: " + end.why()); continue; }
                    double last = 0;
                    for (int tick = 0; tick < 200; tick++) {
                        read(s, y, len, budget);
                        CaveRecord.Exposure ex = s.caveExposure(D, 0, y, 0);
                        CaveYield.Prior p = prior(s, y, J6cCaveYieldTest.iron());
                        if (ex.done()) {
                            if (p.soFar() || Math.abs(p.expected() - end.expected()) > 1e-9) wrong.add(at + ": done at " + p.expected() + ", the end " + end.expected());
                            break;
                        }
                        if (!p.known()) { if (ex.blocks() > 0 && ex.looked() > 0) wrong.add(at + ": blocks counted, no estimate: " + p.why()); continue; }
                        if (!p.soFar()) wrong.add(at + ": not done and not said so far");
                        if (p.expected() < last - 1e-9 || p.expected() > end.expected() + 1e-9) wrong.add(at + ": " + p.expected() + " after " + last + ", the end " + end.expected());
                        if (CaveYield.noSource(p, "raw_iron", 0) != null) wrong.add(at + ": 'no source' on a count still running");
                        last = p.expected();
                    }
                }
        assertTrue(wrong.isEmpty(), wrong.size() + " wrong in " + cases + " cases, the first: " + wrong.stream().limit(3).toList());
    }

    /** Off the band (y 200): so far it is not "no source"; counted to its end it is, with the game data's words. */
    @Test
    void offTheBand_noSourceOnlyOnceTheCountIsDone() {
        Sightings s = tube(200, 700);
        read(s, 200, 700, 500);
        assertNull(CaveYield.noSource(prior(s, 200, J6cCaveYieldTest.iron()), "raw_iron", 0), "the count runs: not judged");
        read(s, 200, 700, Integer.MAX_VALUE);
        assertNotNull(CaveYield.noSource(prior(s, 200, J6cCaveYieldTest.iron()), "raw_iron", 0), "done: no iron generates there");
    }

    static final String SO_FAR = CaveYield.SO_FAR + "410.0 iron_ore ore expected in the 168994 air-exposed blocks counted round 645240 of its 666540 cells (still being read: at least this)";

    static CaveWay soFar(double expected, double orePerCell, int n, int unseen) {
        return CaveNotReadScenariosTest.inside(expected, SO_FAR, CaveWalk.Density.of(y -> orePerCell, 1000, 1000, 0, 0), CaveNotReadScenariosTest.passages(n, unseen));
    }

    /** The planner: a cave on its count so far is PRICED - a rich one near wins, a poor one loses to a seen ore (run JBP's cave, honestly). */
    @Test
    void aCaveOnItsCountSoFar_isPriced_andWinsOrLosesOnItsPrice() {
        Chain rich = NeedChain.resolve("raw_iron", 1, CaveNotReadScenariosTest.world(120, soFar(410, 0.05, 3, 50)));
        assertTrue(rich.ok() && CaveNotReadScenariosTest.caves(rich), "rich and near beats an ore 120 m off: " + rich.why() + " | " + rich.dropped());
        Step.Cave k = (Step.Cave) rich.steps().stream().filter(s -> s instanceof Step.Cave).findFirst().orElseThrow();
        assertTrue(k.planS() > 0, "the step carries its price for the guard: " + k.planS());
        Chain poor = NeedChain.resolve("raw_iron", 1, CaveNotReadScenariosTest.world(30, soFar(410, 0.00002, 3, 50)));
        assertTrue(poor.ok() && !CaveNotReadScenariosTest.caves(poor), "0.003 ore along its passages loses to an ore 30 m off: " + poor.why());
    }

    /** Swept: the planner caves only when the cave's own line is the cheaper; every cave step carries its price. */
    @Test
    void sweptPrices_theCaveIsTakenOnlyRichAndNear() {
        List<String> wrong = new ArrayList<>();
        int caved = 0, cases = 0;
        for (double d : new double[]{0.00001, 0.0001, 0.001, 0.01, 0.05, 0.3})
            for (int n : new int[]{1, 3, 10, 110})
                for (int unseen : new int[]{5, 50, 500})
                    for (double oreM : new double[]{3, 30, 120, 400}) {
                        cases++;
                        String at = d + " ore a cell, " + n + " passages x " + unseen + ", ore " + oreM + " m";
                        Chain c = NeedChain.resolve("raw_iron", 1, CaveNotReadScenariosTest.world(oreM, soFar(410, d, n, unseen)));
                        if (!c.ok()) { wrong.add(at + ": " + c.why()); continue; }
                        boolean caves = CaveNotReadScenariosTest.caves(c);
                        if (caves) {
                            caved++;
                            Step.Cave k = (Step.Cave) c.steps().stream().filter(s -> s instanceof Step.Cave).findFirst().orElseThrow();
                            if (!(k.planS() > 0)) wrong.add(at + ": a caving step with no price");
                            if (n * unseen * d < 0.01) wrong.add(at + ": caves on " + n * unseen * d + " ore expected along all its passages");
                        }
                        if (!caves && oreM >= 400 && d >= 0.05 && unseen >= 50) wrong.add(at + ": a rich cave at the feet lost to an ore 400 m off: " + c.why());
                    }
        assertTrue(wrong.isEmpty(), wrong.size() + " wrong in " + cases + " cases, the first: " + wrong.stream().limit(3).toList());
        assertTrue(caved > 10 && caved < cases - 10, "both answers happen: " + caved + " of " + cases);
    }

    // ---- 2. cave-guard ----

    @Test
    void aWalkFarOverItsPrice_endsDearer() {
        var next = step(0, 1, 7.0, List.of(), 8.5);
        assertInstanceOf(CaveWalk.Stop.class, next, "7 min inside on an 8.5 s price");
        CaveWalk.Stop s = (CaveWalk.Stop) next;
        assertEquals(CaveWalk.End.DEARER, s.end());
        assertTrue(s.why().contains("priced 9 s inside for 1 ore") && s.why().contains("420 s inside and 0 mined") && s.why().contains("cave.dearerTimes 5"), "said with its numbers (rule 7): " + s.why());
        assertInstanceOf(CaveWalk.Goto.class, step(0, 1, 0.5, List.of(), 8.5), "30 s inside is under 5 x 8.5 s: walks on");
        assertInstanceOf(CaveWalk.Goto.class, step(0, 1, 7.0, List.of(), 0), "no price given: no guard (the minutes end it)");
    }

    /** A seen ore is never left for the guard: it is mined or walked to first, however far over the price the walk is. */
    @Test
    void aSeenOreIsMinedFirst_howeverFarOverThePrice() {
        assertInstanceOf(CaveWalk.Mine.class, step(0, 1, 7.0, List.of(new int[]{1, 41, 0}), 8.5), "in reach: mined");
        var far = step(0, 1, 7.0, List.of(new int[]{60, 40, 1}), 8.5);
        assertTrue(far instanceof CaveWalk.Goto g && g.ore(), "farther in: walked to: " + far);
    }

    /** Swept: prices, times, minutes inside, ore mined - DEARER exactly when the seconds inside pass times x the price, the need unmet. */
    @Test
    void sweptGuard_endsExactlyOverTimesThePrice() {
        List<String> wrong = new ArrayList<>();
        int cases = 0, ended = 0;
        for (double planS : new double[]{0, 1, 8.5, 60, 119, 300, 2000})
            for (double times : new double[]{0, 1.5, 3, 5, 8})
                for (double min : new double[]{0, 0.2, 1, 2.9, 7, 9.9, 10, 12})
                    for (int mined : new int[]{0, 1, 3})
                        for (int need : new int[]{1, 3}) {
                            cases++;
                            Tuning.reset();
                            Tuning.set("cave.dearerTimes", times);
                            var next = step(mined, need, min, List.of(), planS);
                            String at = "price " + planS + " s x " + times + ", " + min + " min, " + mined + " of " + need;
                            CaveWalk.End want = mined >= need ? CaveWalk.End.NEED_MET : min >= 10 ? CaveWalk.End.MINUTES
                                    : planS > 0 && times > 0 && min * 60 > planS * times ? CaveWalk.End.DEARER : null;
                            CaveWalk.End got = next instanceof CaveWalk.Stop s ? s.end() : null;
                            if (got == CaveWalk.End.DEARER) ended++;
                            if (want != got) wrong.add(at + ": " + got + ", wanted " + want);
                        }
        assertTrue(wrong.isEmpty(), wrong.size() + " wrong in " + cases + " cases, the first: " + wrong.stream().limit(3).toList());
        assertTrue(ended > 50, "the guard fired: " + ended + " of " + cases);
    }

    /** The end is the cave's source fact (as its minutes): the order holds the cave and is planned again; the price is not the step's identity. */
    @Test
    void theDearerEnd_isTheCavesSourceFact() {
        int[] entry = {-56, 108, -9};
        FactChange.caveDearer(entry, 0, 110, "priced 9 s inside for 1 ore; 420 s inside and 0 mined");
        FactChange.Fact f = FactChange.take();
        assertEquals("source " + CaveWay.keyOf(entry), f.key());
        assertFalse(f.price(), "a source fact, never a price the order stops on");
        assertTrue(f.text().contains("far over its price, 0 ore mined, 110 passages left"), f.text());
        assertEquals(new Step.Cave(1, 2, 3, "coal", 1, 10, 8.5), new Step.Cave(1, 2, 3, "coal", 1, 10, 300), "the same step at another price");
        assertEquals(new Step.Cave(1, 2, 3, "coal", 1, 10, 8.5).text(), new Step.Cave(1, 2, 3, "coal", 1, 10).text());
    }
}
