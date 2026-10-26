package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.commands.AskSweep;
import io.github.mojolowjo.entropybot.commands.PricedAnswers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 0.39.z76 J3-mod item 2 (rule 18 amended 2026-10-25 late evening: rank rough, walk exact): the known sources of one need are
 * ranked by Baritone's estimate (no search) and only the top pathCost.pricedTop (2) get a priced path; the rest are "ranked"
 * in aa why's dropped list, never a price; an unreachable top way frees its place; a ranked-out pending ask nobody asked again
 * is withdrawn (IPricedPath.cancel), and the fork's WITHDRAWN answer reads "path not known", never "no path". Hand numbers:
 * the fake walk is metres x 1.3 / 4.317 (FakePaths), a tree is 6 logs.
 */
class Z76RankRoughTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    /** A path provider that counts asks: every walk complete at the fake's seconds unless {@code answers} says otherwise. */
    static final class Counting implements PathPrice {
        final List<String> asked = new ArrayList<>();
        final Set<String> rankedOut = new HashSet<>();
        final Map<String, Answer> answers = new HashMap<>();
        final Map<String, Double> estimates = new HashMap<>();

        @Override public Answer price(int x, int y, int z, double dist) {
            String k = x + "," + y + "," + z;
            asked.add(k);
            return answers.getOrDefault(k, Answer.priced(new Walk(FakePaths.walkS(dist))));
        }

        @Override public double estimate(int x, int y, int z) { return estimates.getOrDefault(x + "," + y + "," + z, Double.NaN); }

        @Override public void rankedOut(int x, int y, int z) { rankedOut.add(x + "," + y + "," + z); }
    }

    static final List<Spot> FIVE = List.of(ScenariosTest.tree(10, 6), ScenariosTest.tree(20, 6), ScenariosTest.tree(30, 6), ScenariosTest.tree(40, 6), ScenariosTest.tree(50, 6));

    static World five(PathPrice pp) {
        return ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", FIVE)).withPath(pp);
    }

    /**
     * Five trees 10-50 m for 3 oak logs, no estimate known (the straight metres rank them): 2 walks asked (10 m, 20 m), the
     * 10 m tree cut; 30, 40 and 50 m are "ranked number 3/4/5 of 5" in the dropped list and told ranked out; the chain is
     * the same as with the 10 m tree alone (walk 3.0 s + its 6 logs).
     */
    @Test
    void fiveWaysTwoPriced() {
        ScenariosTest.world("wooden");
        Counting pp = new Counting();
        Chain c = NeedChain.resolve("oak_log", 3, five(pp));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(List.of("10,64,0", "20,64,0"), pp.asked, "two priced paths asked");
        assertEquals("cut 3 at 10 64 0", c.steps().get(0).text(), c.steps().toString());
        for (int d : new int[]{30, 40, 50}) {
            int n = d / 10;
            assertTrue(c.dropped().stream().anyMatch(x -> x.startsWith("tree " + d + " m: " + NeedChain.RANKED + " number " + n + " of 5 by Baritone's estimate (no search, not a price)")), c.dropped().toString());
            assertTrue(pp.rankedOut.contains(d + ",64,0"), pp.rankedOut.toString());
        }
        assertTrue(c.dropped().stream().anyMatch(x -> x.startsWith("tree 20 m: ") && x.contains("dearer than tree 10 m")), "the second is priced: " + c.dropped());
        Chain alone = NeedChain.resolve("oak_log", 3, ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(FIVE.get(0)))));
        assertEquals(alone.seconds(), c.seconds(), 1e-9, "the winner's chain is unchanged");
        assertEquals(alone.steps().stream().map(Step::text).toList(), c.steps().stream().map(Step::text).toList());
    }

    /** Baritone's estimate ranks: the 50 m tree's estimate is the lowest (2 s), then 10 m (3 s): those two are asked, 50 m first. */
    @Test
    void theEstimateRanksTheWays() {
        ScenariosTest.world("wooden");
        Counting pp = new Counting();
        double[] est = {3, 8, 9, 10, 2};
        for (int i = 0; i < FIVE.size(); i++) pp.estimates.put(FIVE.get(i).x() + ",64,0", est[i]);
        NeedChain.resolve("oak_log", 3, five(pp));
        assertEquals(List.of("50,64,0", "10,64,0"), pp.asked);
        // one estimate not known (rule 15): the straight metres for all
        Counting half = new Counting();
        half.estimates.put("50,64,0", 2.0);
        NeedChain.resolve("oak_log", 3, five(half));
        assertEquals(List.of("10,64,0", "20,64,0"), half.asked);
    }

    /** The 10 m tree unreachable: it frees its place - 10, 20 and 30 m asked, the 20 m tree cut, 40 and 50 m ranked. */
    @Test
    void anUnreachableWayFreesItsPlace() {
        ScenariosTest.world("wooden");
        Counting pp = new Counting();
        pp.answers.put("10,64,0", PathPrice.Answer.unreachable("no path: the complete search found none"));
        Chain c = NeedChain.resolve("oak_log", 3, five(pp));
        assertTrue(c.ok() && !c.explore(), c.why());
        assertEquals(List.of("10,64,0", "20,64,0", "30,64,0"), pp.asked);
        assertEquals("cut 3 at 20 64 0", c.steps().get(0).text());
        assertEquals(Set.of("40,64,0", "50,64,0"), pp.rankedOut);
    }

    /** Two ways computing use both places: the chain waits on them (path not known), the rest ranked - never asked meanwhile. */
    @Test
    void twoWaysComputingHoldThePlaces() {
        ScenariosTest.world("wooden");
        Counting pp = new Counting();
        pp.answers.put("10,64,0", PathPrice.Answer.computing(null));
        pp.answers.put("20,64,0", PathPrice.Answer.computing(null));
        Chain c = NeedChain.resolve("oak_log", 3, five(pp));
        assertEquals(List.of("10,64,0", "20,64,0"), pp.asked);
        assertTrue(c.waiting() || c.explore(), "waits on the two paths (or explores meanwhile): " + c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.ChopTree), "no ranked way is picked: " + c.steps());
    }

    /** pathCost.pricedTop 5: every way priced again (the owner's knob, aa tune). */
    @Test
    void theTopIsATuningEntry() {
        ScenariosTest.world("wooden");
        Tuning.set("pathCost.pricedTop", 5);
        Counting pp = new Counting();
        NeedChain.resolve("oak_log", 3, five(pp));
        assertEquals(5, pp.asked.size(), pp.asked.toString());
    }

    /** Known stations: the first 2 by the estimate less the bonus are asked (was pathCost.maxPending 4), the rest ranked out. */
    @Test
    void stationsAskTheTopTwo() {
        Counting pp = new Counting();
        List<Spot> st = List.of(new Spot(5, 64, 0, 5, "minecraft:crafting_table", 1), new Spot(9, 64, 0, 9, "minecraft:crafting_table", 1),
                new Spot(14, 64, 0, 14, "minecraft:crafting_table", 1), new Spot(20, 64, 0, 20, "minecraft:crafting_table", 1));
        Spot best = Nearby.byPath(st, pp);
        assertEquals(List.of("5,64,0", "9,64,0"), pp.asked);
        assertEquals(5, best.x());
        assertEquals(Set.of("14,64,0", "20,64,0"), pp.rankedOut);
    }

    /** Review: the two nearest stations unreachable free their places - the 3rd and 4th are asked, the 3rd (14 m) is the walk. */
    @Test
    void unreachableStationsFreeTheirPlaces() {
        Counting pp = new Counting();
        pp.answers.put("5,64,0", PathPrice.Answer.unreachable("no path: the complete search found none"));
        pp.answers.put("9,64,0", PathPrice.Answer.unreachable("no path: the complete search found none"));
        List<Spot> st = List.of(new Spot(5, 64, 0, 5, "minecraft:crafting_table", 1), new Spot(9, 64, 0, 9, "minecraft:crafting_table", 1),
                new Spot(14, 64, 0, 14, "minecraft:crafting_table", 1), new Spot(20, 64, 0, 20, "minecraft:crafting_table", 1),
                new Spot(30, 64, 0, 30, "minecraft:crafting_table", 1));
        Spot best = Nearby.byPath(st, pp);
        assertEquals(List.of("5,64,0", "9,64,0", "14,64,0", "20,64,0"), pp.asked);
        assertEquals(14, best.x());
        assertEquals(Set.of("30,64,0"), pp.rankedOut);
        // nothing priced yet: the first asked one not unreachable, never the unreachable nearest
        Counting none = new Counting();
        none.answers.put("5,64,0", PathPrice.Answer.unreachable("no path"));
        none.answers.put("9,64,0", PathPrice.Answer.computing(null));
        none.answers.put("14,64,0", PathPrice.Answer.computing(null));
        assertEquals(9, Nearby.byPath(st, none).x());
    }

    // ---- the withdraw (fork J3: IPricedPath.cancel, WITHDRAWN) ----

    /**
     * Review (two planners on staggered ticks): a goal asked in the window before this one is still "asked again" and stays;
     * not asked for two whole windows, a ranked-out ask goes.
     */
    @Test
    void anAskIsKeptForTwoSweepWindows() {
        AskSweep.Window w = new AskSweep.Window();
        List<AskSweep.Ask> pending = List.of(new AskSweep.Ask("30,64,0", new int[]{0, 64, 0}));
        int[] feet = {0, 64, 0};
        w.asked("30,64,0");                      // planner A asks it (window 1)
        assertEquals(List.of(), w.sweep(pending, feet, 16, null));
        w.rankedOut("30,64,0");                  // planner B ranks it out (window 2), A did not run this tick
        assertEquals(List.of(), w.sweep(pending, feet, 16, null), "asked one window before: kept");
        w.rankedOut("30,64,0");                  // window 3: B again, A not since window 1
        assertEquals(List.of("30,64,0"), w.sweep(pending, feet, 16, null), "two windows with no ask: withdrawn");
    }

    /** A dropped way's ask is withdrawn; asked again, it stays; the bot past failures.moved (16) from its start: withdrawn; never the running walk. */
    @Test
    void aDroppedWaysAskIsWithdrawn() {
        int[] start = {0, 64, 0};
        List<AskSweep.Ask> pending = List.of(new AskSweep.Ask("30,64,0", start), new AskSweep.Ask("40,64,0", start), new AskSweep.Ask("10,64,0", start));
        assertEquals(List.of("30,64,0"), AskSweep.due(pending, Set.of("40,64,0"), Set.of("30,64,0", "40,64,0"), new int[]{1, 64, 0}, 16, null),
                "30 ranked out and not asked again: withdrawn; 40 asked again: kept; 10 wanted: kept");
        assertEquals(List.of("30,64,0", "40,64,0", "10,64,0"), AskSweep.due(pending, Set.of(), Set.of(), new int[]{17, 64, 0}, 16, null),
                "17 blocks from their start, nobody asked: all withdrawn");
        assertEquals(List.of(), AskSweep.due(pending, Set.of(), Set.of(), new int[]{10, 64, 0}, 16, null), "10 blocks: kept");
        assertEquals(List.of("40,64,0", "10,64,0"), AskSweep.due(pending, Set.of(), Set.of(), new int[]{17, 64, 0}, 16, "30,64,0"), "the running walk never");
        assertEquals(List.of(), AskSweep.due(pending, Set.of(), Set.of(), null, 16, null), "the feet not known: no move rule (rule 15)");
    }

    /** The fork's WITHDRAWN is "path not known yet" (computing): the chain waits, the source is never dropped as unreachable. */
    @Test
    void withdrawnNeverDropsASource() {
        String w = baritone.api.pathing.calc.IPricedPath.WITHDRAWN;
        PathPrice.Answer a = PricedAnswers.notYet(w, baritone.api.pathing.calc.IPricedPath.QUEUED, baritone.api.pathing.calc.IPricedPath.RESEARCH_WAIT, w, 4);
        assertNotNull(a);
        assertEquals(PathPrice.Kind.COMPUTING, a.kind(), a.toString());
        NeedChain.Walked wk = NeedChain.walked(a);
        assertTrue(wk.fail().startsWith(PathPrice.NOT_KNOWN), wk.fail());
        ScenariosTest.world("wooden");
        Counting pp = new Counting();
        pp.answers.put("10,64,0", a);
        Chain c = NeedChain.resolve("oak_log", 3, ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(FIVE.get(0)))).withPath(pp));
        assertTrue(c.waiting() || c.explore(), c.why());
        assertTrue(c.dropped().stream().noneMatch(d -> d.contains("unreachable") || d.contains("no path:")), c.dropped().toString());
        assertTrue(c.why().contains(PathPrice.NOT_KNOWN), c.why());
    }
}
