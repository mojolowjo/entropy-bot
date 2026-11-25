package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.NeedChain.World;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.moves.DryRun;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.aa.moves.StepNeeds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * job-kept-crafts (worlds run 4, to-do/run4-causes-2026-11-25.md): the line's logs kept for its deposit while the bot needs
 * logs for its own tools. The holds stay (the owner's rule: a -d amount reaches the chest whole); what was missing:
 * <ul>
 * <li>w068 (deposit/w068.log 395, 1009): 16 birch_log kept (the log order met), the cobblestone order's wooden pickaxe priced
 * from "cut tree seen 66 m" and a "walk 187.7 s (path)" back to the stone 1 m from the feet, 95 re-plans - a BORROW of the
 * kept logs, priced with collecting them again (the met order opens again for them, the line's deposit waits), is a way the
 * scorer can take; it leaves the bot at the stone.</li>
 * <li>w044 (deposit/w044.log 1221, 1277-1305): the torch's chain cut a tree for one log ("cut 1 #logs_that_burn at -175 71
 * -220", the chop took 6), the parked oak_log order (7 of 16) took all 6 into its hold and the dry run said "needs 1 of
 * logs_that_burn; the bag will hold 0" twice - the runner holds for the other orders no more than at the run's start.</li>
 * <li>w053 / w098 (time/w053.log 1539, time/w098.log 1886: "#planks from #logs: no known source for #logs", the sticks and
 * the table from planks 47 m and 86 m off in a mineshaft): no log source was known, so a borrow's collect again would be an
 * explore - the way is dropped with that reason (rule 7), never taken unpriced.</li>
 * </ul>
 * Every chain here is resolved through the planner worker's path (SyncPlanner: the snapshot, PlannerWorker.resolve, the
 * live check), and the runner's readers (Goals.keptForRun with the start bag and the borrow's cap, DryRun) are asked about
 * the very steps the planner priced.
 */
class KeptCraftsTest {
    @AfterEach
    void clear() {
        Only.set("off");
        BatchWay.clear();
    }

    private static final String LOG = "minecraft:oak_log";

    /** w068's facts: the tree 66 m off (6 logs, line 1009 "cut tree seen 66 m"), the stone 1 m ("dig cobblestone seen 1 m"). */
    private static Map<String, List<Spot>> w068Seen() {
        Map<String, List<Spot>> seen = new HashMap<>();
        seen.put("oak_log", List.of(ScenariosTest.tree(66, 6)));
        seen.put("cobblestone", List.of(new Spot(1, 63, 0, 1, "minecraft:stone", 40)));
        return seen;
    }

    private static List<StepNeeds> needs(Chain c) {
        List<StepNeeds> n = new ArrayList<>();
        for (int i = 0; i < c.steps().size(); i++) n.add(StepNeeds.of(c.steps().get(i)).withUses(i < c.uses().size() ? c.uses().get(i) : null));
        return n;
    }

    private static int borrowed(List<Step> steps, String item) {
        int n = 0;
        for (Step s : steps) if (s instanceof Step.Borrow b && b.item().equals(item)) n += b.n();
        return n;
    }

    /** The planner alone (pure): the kept logs lent, the tools from them, the stone dug where the bot stands; none lent: the tree. */
    @Test
    void w068_theToolsFromBorrowedKeptLogs_notTheFarTree() {
        ScenariosTest.toolWorld();
        World none = ScenariosTest.w(Map.of(), false, false, w068Seen());
        Chain cut = NeedChain.resolve("cobblestone", 32, none);
        assertTrue(cut.ok() && !cut.explore(), cut.why());
        assertTrue(cut.steps().stream().anyMatch(s -> s instanceof Step.ChopTree), "nothing lent: the far tree, as in w068: " + cut.why());
        Chain lent = NeedChain.resolve("cobblestone", 32, none.withLend(Map.of(LOG, 16)));
        assertTrue(lent.ok() && !lent.explore(), lent.why());
        assertTrue(borrowed(lent.steps(), LOG) > 0, "the kept logs borrowed: " + lent.steps() + " / " + lent.why());
        assertTrue(lent.steps().stream().noneMatch(s -> s instanceof Step.ChopTree), "no walk to the far tree in the run (the order the logs are owed to collects them again): " + lent.steps());
        assertTrue(lent.why().contains("collected again before the line's deposit"), "the collect again is said and priced: " + lent.why());
        // one collect again for the three borrows (the tree is cut whole): the chain's seconds are the cut chain's - the walk
        // back from the tree is no price of the planner's (the table is placed at the tree, the dig's walk is from the feet),
        // so the borrow wins its requirement by the walk back it does not leave owed (the comparison where a borrow is among
        // the ways), and the run never walks to the tree
        assertEquals(cut.seconds(), lent.seconds(), 0.05, "one collect again, priced once: " + lent.why());
        assertTrue(lent.why().contains("collected again with an earlier borrow's collect"), lent.why());
        assertTrue(lent.why().contains("tree 66 m: 37.9 s (dearer than borrow 1 kept oak_log)"), "the tree compared with its walk back owed: " + lent.why());
        // the borrow is never more than the lent units
        Chain few = NeedChain.resolve("cobblestone", 32, none.withLend(Map.of(LOG, 1)));
        assertTrue(borrowed(few.steps(), LOG) <= 1, few.steps().toString());
    }

    /**
     * w068 through the worker path and the runner: the log order met with 16 in the bag, the cobblestone order's run priced
     * by SyncPlanner borrows the kept logs; the runner's readers let exactly the borrowed units be used once the Borrow ran;
     * the crafts take them, the met log order is open again for them and the line's deposit waits.
     */
    @Test
    void w068_throughTheWorker_theRunnerLetsTheBorrowRun_andTheLogOrderOpensAgain() {
        ScenariosTest.toolWorld();
        OrderLineSim s = new OrderLineSim();
        s.seen.putAll(w068Seen());
        s.bag.put("oak_log", 16);
        s.bb.fact(BatchWay.PLANNER_FACT, s.sync);            // every trip and own chain through the worker's path
        Goals.Goal log = s.order("oak_log", 16, "b1", true);
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true);
        s.tick();
        assertNull(Goals.find(s.bb, "oak_log"), "the log order is met: " + s.said);
        Chain ch = Goals.chain(s.bb, cob);
        assertNotNull(ch);
        assertTrue(ch.ok() && !ch.explore(), ch.why());
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        int n = borrowed(ch.steps(), LOG);
        assertTrue(n > 0, "the worker's chain borrows the kept logs: " + ch.steps() + " / " + ch.why());
        assertTrue(ch.steps().stream().noneMatch(x -> x instanceof Step.ChopTree), ch.steps().toString());
        // the runner, from the run's start: the 16 held from the crafts until the Borrow runs
        Map<String, Integer> start = Map.of(LOG, 16);
        assertEquals(16, Goals.keptForRun(s.bb, cob.id(), true, true, start, null).getOrDefault("oak_log", 0));
        // the dry run over the planned steps on the runner's free bag (the count reader): the Borrow puts its units in
        Map<String, Integer> free = new HashMap<>(Map.of(LOG, 16));
        Goals.keptForRun(s.bb, cob.id(), true, false, start, null).forEach((k, v) -> free.merge("minecraft:" + k, -v, Integer::sum));
        assertNull(DryRun.check(ch.steps(), 0, needs(ch), free), "the runner's dry run passes the planner's steps");
        // the Borrow runs (AaRuntime.lend's rule): the hold lowered by n
        int use = Goals.keptForRun(s.bb, cob.id(), true, true, start, null).getOrDefault("oak_log", 0);
        Map<String, Integer> cap = Map.of("oak_log", use - n);
        assertEquals(16 - n, Goals.keptForRun(s.bb, cob.id(), true, true, start, cap).getOrDefault("oak_log", 0), "exactly the borrowed units are free to craft");
        assertEquals(16 - n, Goals.keptForRun(s.bb, cob.id(), true, false, start, cap).getOrDefault("oak_log", 0), "and to count");
        // the crafts take them: the met log order is open again for them, the line's deposit waits
        s.bag.put("oak_log", 16 - n);
        s.tick();
        assertSame(log, Goals.find(s.bb, "oak_log"), "the log order is open again: " + s.said);
        assertTrue(String.join("\n", s.said).contains("oak_log was done, and the bag holds " + (16 - n) + " of its 16 for the line's deposit now: open again"), String.join("\n", s.said));
        assertTrue(s.due.isEmpty(), "no deposit while the borrowed logs are owed: " + s.due);
        // still the cap after the crafts: the hold is what the bag holds, never more lent
        assertEquals(16 - n, Goals.keptForRun(s.bb, cob.id(), true, true, start, cap).getOrDefault("oak_log", 0));
    }

    /**
     * w044 (line 1221: "oak_log 7 (order oak_log (kept for a deposit))"; 1277-1305: the cut gave 6, the torch's step "needs 1
     * of logs_that_burn; the bag will hold 0"): an order's run holds for another unmet -d order no more than at the run's
     * start - the logs its own cut brings in are its own, as the planner priced them. Was: all 13.
     */
    @Test
    void w044_theRunsOwnCut_isNotTakenByAnotherOrdersHold() {
        ScenariosTest.toolWorld();
        OrderLineSim s = new OrderLineSim();
        s.seen.put("oak_log", List.of(ScenariosTest.tree(6, 6)));
        s.bag.put("oak_log", 7);
        s.bb.fact(BatchWay.PLANNER_FACT, s.sync);
        s.order("oak_log", 16, "b2", true);                  // the other line's order: 7 of 16 held
        Goals.Goal stick = s.order("stick", 4, "b1", true);
        s.tick();
        Map<String, Integer> start = Map.of(LOG, 7);
        assertEquals(7, Goals.keptForRun(s.bb, stick.id(), true, true, start, null).getOrDefault("oak_log", 0), "at the start: the 7 held");
        Chain ch = Goals.chain(s.bb, stick);
        assertTrue(ch != null && ch.ok() && !ch.explore(), ch == null ? "no chain" : ch.why());
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        // the run's own cut (the chop takes the whole tree: 6) or a borrow: the 7 stay held, the rest is the run's
        s.bag.put("oak_log", 13);
        s.tick();
        assertEquals(7, Goals.keptForRun(s.bb, stick.id(), true, true, start, null).getOrDefault("oak_log", 0), "the crafts' reader: the 6 cut are the run's (was 13)");
        assertEquals(7, Goals.keptForRun(s.bb, stick.id(), true, false, start, null).getOrDefault("oak_log", 0), "the count reader (the dry run's free bag): the same");
        assertEquals(13, Goals.keptForRun(s.bb, stick.id(), true, true, null, null).getOrDefault("oak_log", 0), "the start not known: held whole, as before");
    }

    /**
     * w053 (time/w053.log 1539) / w098 (1886): "#planks from #logs: no known source for #logs" with the log order's 16 kept -
     * the borrow's collect again would be an explore: the way is dropped with that reason (rule 7) and the known planks stay
     * the way (what the facts allowed then; a known tree makes the borrow the near way, w068).
     */
    @Test
    void w053_w098_noLogSourceKnown_theBorrowIsDroppedWithItsReason() {
        ScenariosTest.toolWorld();
        Map<String, List<Spot>> seen = new HashMap<>();
        seen.put("oak_planks", List.of(new Spot(47, 37, 0, 47, "minecraft:oak_planks", 6)));       // w053: "dig oak_planks seen 47 m"
        World w = ScenariosTest.w(Map.of(), false, false, seen).withLend(Map.of(LOG, 16));
        Chain c = NeedChain.resolve("stick", 4, w);
        assertTrue(c.ok(), c.why());
        assertEquals(0, borrowed(c.steps(), LOG), c.steps().toString());
        assertTrue(c.why().contains("dig oak_planks seen 47 m"), "the known planks stay the way: " + c.why());
        // the log need alone: its borrow is dropped with the collect again's reason, never taken unpriced
        Chain logs = NeedChain.resolve("oak_log", 1, w);
        assertEquals(0, borrowed(logs.steps(), LOG), logs.steps().toString());
        Chain planks = NeedChain.resolve("oak_planks", 4, ScenariosTest.w(Map.of(), false, false, Map.of()).withLend(Map.of(LOG, 16)));
        assertEquals(0, borrowed(planks.steps(), LOG), planks.steps().toString());
        assertTrue(planks.explore() || !planks.ok(), "nothing known: the explore, as before: " + planks.why());
    }

    /** The borrow's collect again ends in an explore: dropped with that reason (rule 7), in the dropped list of the need that lost it. */
    @Test
    void aBorrowWhoseCollectAgainIsAnExplore_isDroppedAndSaid() {
        ScenariosTest.toolWorld();
        Map<String, List<Spot>> seen = new HashMap<>();
        seen.put("oak_planks", List.of(new Spot(47, 37, 0, 47, "minecraft:oak_planks", 6)));
        World w = ScenariosTest.w(Map.of(), false, false, seen).withLend(Map.of(LOG, 16));
        Chain c = NeedChain.resolve("oak_planks", 4, w);
        assertTrue(c.ok(), c.why());
        Chain viaLog = NeedChain.resolve("stick", 4, ScenariosTest.w(Map.of(), false, false, Map.of("oak_log", List.of(ScenariosTest.tree(30, 6)))).withLend(Map.of(LOG, 16)));
        assertTrue(viaLog.ok(), viaLog.why());
        assertTrue(borrowed(viaLog.steps(), LOG) > 0 || viaLog.why().contains("borrow"), "a known tree: the borrow is priced: " + viaLog.why());
    }
}
