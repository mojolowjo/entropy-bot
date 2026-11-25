package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
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
 * logs for its own tools. The holds stay (the owner's rule: a -d amount reaches the chest whole).
 * <ul>
 * <li>w044 (deposit/w044.log 1221, 1277-1305): the torch's chain cut a tree for one log (the chop took 6), the parked oak_log
 * order (7 of 16) took all 6 into its hold and the dry run said "needs 1 of logs_that_burn; the bag will hold 0" - the runner
 * holds for the other orders no more than at the run's start. Built.</li>
 * <li>w068 / w053 / w098: the "cut more of the log first" way (a) is the one the planner already prices when a log source is
 * known (w068: the tree); with none known (w053 / w098) the planks in the world stay the way. Pinned here, not changed.</li>
 * </ul>
 * Every chain is resolved through the planner worker's path (SyncPlanner: the snapshot, PlannerWorker.resolve, the live
 * check), and the runner's readers (Goals.keptForRun with the start bag, DryRun on its free bag) are asked about the very
 * steps the planner priced, at the first plan and at a re-plan inside the run.
 */
class KeptCraftsTest {
    @AfterEach
    void clear() {
        Only.set("off");
        BatchWay.clear();
    }

    private static final String LOG = "minecraft:oak_log";

    private static List<StepNeeds> needs(Chain c) {
        List<StepNeeds> n = new ArrayList<>();
        for (int i = 0; i < c.steps().size(); i++) n.add(StepNeeds.of(c.steps().get(i)).withUses(i < c.uses().size() ? c.uses().get(i) : null));
        return n;
    }

    /** The runner's free bag (the count reader, StepHands.freeBag over AaRuntime.keptFromOthers): the bag less its hold. */
    private static Map<String, Integer> runnerFree(OrderLineSim s, Goals.Goal g, Map<String, Integer> start) {
        Map<String, Integer> free = new HashMap<>();
        s.bag.forEach((k, v) -> free.put(k.contains(":") ? k : "minecraft:" + k, v));
        Goals.keptForRun(s.bb, g.id(), true, false, start).forEach((k, v) -> free.merge("minecraft:" + k, -v, Integer::sum));
        return free;
    }

    /**
     * w044 (line 1221: "oak_log 7 (order oak_log (kept for a deposit))"; 1277-1305: the cut gave 6, the torch's step "needs 1
     * of logs_that_burn; the bag will hold 0"): an order's run holds for another unmet -d order no more than at the run's
     * start - the logs its own cut brings in are its own, as the planner priced them. Was: all 13. Through the worker, at the
     * first plan and at a re-plan after the cut: the runner's dry run passes the planner's steps both times.
     */
    @Test
    void w044_theRunsOwnCut_isNotTakenByAnotherOrdersHold_firstPlanAndReplan() {
        ScenariosTest.toolWorld();
        OrderLineSim s = new OrderLineSim();
        s.seen.put("oak_log", List.of(ScenariosTest.tree(6, 6)));
        s.bag.put("oak_log", 7);
        s.bb.fact(BatchWay.PLANNER_FACT, s.sync);
        // w044's line (b1: cobblestone 32, oak_log 16, torch 4, all -d): oak_log stopped at 07:40:32 "the line's deposit waits
        // for it - it can still wake", 7 of 16 in the bag; the torch's run (here the stick's, the same log need) is the running one
        Goals.Goal oak = s.order("oak_log", 16, "b1", true);
        Goals.Goal stick = s.order("stick", 4, "b1", true);
        oak.parked = "w044: stopped, it can still wake";
        oak.park = new Goals.Park(Goals.ParkKind.PLACE, null, 0, null, null);
        s.tick();
        assertTrue(Goals.canWake(oak) && Goals.find(s.bb, "oak_log") == oak, "oak_log parked, can wake, among the goals: " + s.said);
        Map<String, Integer> start = Map.of(LOG, 7);
        // the runner's reader of its start bag, as AaRuntime.runStartBagOf: the stick's run is the running one
        s.bb.fact(Goals.RUN_START_FACT, (Goals.RunStart) id -> id.equals(stick.id()) ? start : null);
        assertEquals(7, Goals.keptForRun(s.bb, stick.id(), true, true, start).getOrDefault("oak_log", 0), "at the start: the 7 held");
        Chain ch = Goals.chain(s.bb, stick);
        assertTrue(ch != null && ch.ok() && !ch.explore(), ch == null ? "no chain" : ch.why());
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        assertTrue(ch.steps().stream().anyMatch(x -> x instanceof Step.ChopTree), "the kept 7 are not the stick's: a cut: " + ch.steps());
        // the run's own cut (the chop takes the whole tree: 6): the 7 stay held, the rest is the run's
        s.bag.put("oak_log", 13);
        s.tick();
        assertEquals(7, Goals.keptForRun(s.bb, stick.id(), true, true, start).getOrDefault("oak_log", 0), "the crafts' reader: the 6 cut are the run's (was 13)");
        assertEquals(7, Goals.keptForRun(s.bb, stick.id(), true, false, start).getOrDefault("oak_log", 0), "the count reader (the dry run's free bag): the same");
        assertEquals(13, Goals.keptForRun(s.bb, stick.id(), true, true, null).getOrDefault("oak_log", 0), "the start not known: held whole, as before");
        // the steps after the chop (planks, sticks) on the runner's free bag: the dry run passes them (it said "the bag will hold 0")
        int chop = -1;
        for (int i = 0; i < ch.steps().size(); i++) if (ch.steps().get(i) instanceof Step.ChopTree) chop = i;
        assertNull(DryRun.check(ch.steps(), chop + 1, needs(ch), runnerFree(s, stick, start)), "the runner lets the planner's crafts run");
        // a re-plan inside the run (bag 13): the planner takes the others' amounts off as the runner holds them (review 2:
        // the same cap through the runner's start bag), so it sees the 6 cut logs as free: no second cut, and every step runs
        Goals.Goal again = Goals.find(s.bb, stick.r.key());
        Chain re = Goals.chain(s.bb, again);
        assertTrue(re != null && re.ok() && !re.explore(), re == null ? "no chain" : re.why());
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        assertTrue(re.steps().stream().noneMatch(x -> x instanceof Step.ChopTree), "the re-plan uses the run's own 6 cut logs: " + re.steps());
        assertNull(DryRun.check(re.steps(), 0, needs(re), runnerFree(s, stick, start)), "the re-plan's steps run on the runner's free bag: " + re.steps());
        // the run is over (the reader answers null): the hold whole again, for the planner and the runner alike
        s.bb.fact(Goals.RUN_START_FACT, null);
        Chain after = Goals.chain(s.bb, Goals.find(s.bb, stick.r.key()));
        assertTrue(after != null && after.ok(), after == null ? "no chain" : after.why());
        assertTrue(after.steps().stream().anyMatch(x -> x instanceof Step.ChopTree), "no run: the 13 held, a cut again: " + after.steps());
    }

    /**
     * Review 2 (the cap was wider than w044 needs: a trip sibling met during the run held none of its met amount, and the
     * crafts could take the kept logs as "the alternative there is most of"): the line "stick 4, oak_log 16 -d, cobblestone 8 -d"
     * with no logs at the run's start. The trip's cut meets oak_log in the run; its hold is whole, so it is never cut to
     * the start bag - before its weighing (still among the goals) and after (ended in its open line) - for the runner's
     * crafts, its count reader, the planner's world (2 free of 18) and the placing reserves.
     */
    @Test
    void aSiblingMetDuringTheRun_isHeldWhole_runnerAndPlannerAlike() {
        ScenariosTest.toolWorld();
        OrderLineSim s = new OrderLineSim();
        s.seen.put("oak_log", List.of(ScenariosTest.tree(6, 6)));
        s.bb.fact(BatchWay.PLANNER_FACT, s.sync);
        Goals.Goal stick = s.order("stick", 4, "b1", false);
        s.order("oak_log", 16, "b1", true);
        s.order("cobblestone", 8, "b1", true);
        s.tick();
        Map<String, Integer> start = Map.of();
        s.bb.fact(Goals.RUN_START_FACT, (Goals.RunStart) id -> id.equals(stick.id()) ? start : null);
        // the trip's cut brought 18: oak_log is met (not yet weighed: still among the goals)
        s.bag.put("oak_log", 18);
        s.bb.in.bag = Map.copyOf(s.bag);
        assertEquals(16, Goals.keptForRun(s.bb, stick.id(), true, true, start).getOrDefault("oak_log", 0), "met, not weighed: held whole (was 0)");
        assertEquals(16, Goals.keptForRun(s.bb, stick.id(), true, false, start).getOrDefault("oak_log", 0), "the count reader: the same");
        s.tick();
        assertNull(Goals.find(s.bb, "oak_log"), "oak_log met and weighed: " + s.said);
        assertEquals(16, Goals.keptForRun(s.bb, stick.id(), true, true, start).getOrDefault("oak_log", 0), "weighed, its line open: held whole (was 0)");
        int reserved = 0;
        for (var k : Goals.keptPerForRun(s.bb, Goals.find(s.bb, stick.r.key()))) reserved += k.items().getOrDefault("oak_log", 0);
        assertEquals(16, reserved, "the placing reserves hold the 16 too");
        // the planner's world for a stick needing logs: 2 free (18 - 16), and the dry run on the runner's free bag agrees
        Goals.Goal st = Goals.find(s.bb, stick.r.key());
        Chain ch = Goals.chain(s.bb, st);
        assertTrue(ch != null && ch.ok() && !ch.explore(), ch == null ? "no chain" : ch.why());
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        assertNull(DryRun.check(ch.steps(), 0, needs(ch), runnerFree(s, st, start)), "the runner lets the planner's steps run: " + ch.steps());
    }

    /** w068's facts as known then: the tree 66 m off (line 1009 "cut tree seen 66 m"), the stone 1 m ("dig cobblestone seen 1 m"). */
    private static Map<String, List<Spot>> w068Seen() {
        Map<String, List<Spot>> seen = new HashMap<>();
        seen.put("oak_log", List.of(ScenariosTest.tree(66, 6)));
        seen.put("cobblestone", List.of(new Spot(1, 63, 0, 1, "minecraft:stone", 40)));
        return seen;
    }

    /**
     * w068 through the worker: the log order met with 16 kept, the cobblestone order's tools come from cutting more of the
     * log first (way (a): the kept 16 are never used), and the runner's dry run passes the planner's steps.
     */
    @Test
    void w068_throughTheWorker_theToolsFromCuttingMore_theKeptLogsWhole() {
        ScenariosTest.toolWorld();
        OrderLineSim s = new OrderLineSim();
        s.seen.putAll(w068Seen());
        s.bag.put("oak_log", 16);
        s.bb.fact(BatchWay.PLANNER_FACT, s.sync);
        s.order("oak_log", 16, "b1", true);
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true);
        s.tick();
        assertNull(Goals.find(s.bb, "oak_log"), "the log order is met: " + s.said);
        Chain ch = Goals.chain(s.bb, cob);
        assertTrue(ch != null && ch.ok() && !ch.explore(), ch == null ? "no chain" : ch.why());
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        assertTrue(ch.steps().stream().anyMatch(x -> x instanceof Step.ChopTree), "cut more of the log first: " + ch.steps());
        Map<String, Integer> start = Map.of(LOG, 16);
        assertEquals(16, Goals.keptForRun(s.bb, cob.id(), true, true, start).getOrDefault("oak_log", 0), "the 16 held whole");
        assertNull(DryRun.check(ch.steps(), 0, needs(ch), runnerFree(s, cob, start)), "the runner's dry run passes the planner's steps");
    }

    /**
     * w053 (time/w053.log 1539) / w098 (1886): "#planks from #logs: no known source for #logs" with the log order's 16 kept -
     * through the worker the known planks stay the way (what the facts allowed then), the kept logs whole.
     */
    @Test
    void w053_w098_noLogSourceKnown_theKnownPlanksThroughTheWorker() {
        ScenariosTest.toolWorld();
        OrderLineSim s = new OrderLineSim();
        s.seen.put("oak_planks", List.of(new Spot(47, 37, 0, 47, "minecraft:oak_planks", 6)));       // w053: "dig oak_planks seen 47 m"
        s.bag.put("oak_log", 16);
        s.bb.fact(BatchWay.PLANNER_FACT, s.sync);
        s.order("oak_log", 16, "b1", true);
        Goals.Goal stick = s.order("stick", 4, "b1", true);
        s.tick();
        Chain ch = Goals.chain(s.bb, stick);
        assertNotNull(ch);
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        assertTrue(ch.ok(), ch.why());
        assertTrue(ch.why().contains("oak_planks"), "the known planks: " + ch.why());
        assertEquals(16, Goals.keptForRun(s.bb, stick.id(), true, true, Map.of(LOG, 16)).getOrDefault("oak_log", 0), "the 16 held whole");
    }
}
