package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.NeedChain.Spot;
import io.github.mojolowjo.entropybot.aa.brain.BatchWay;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.LineDeposits;
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
 * logs for its own tools. The holds stay whole and are read the same by the planner and the runner (the owner's rule: a -d
 * amount reaches the chest whole, once); review 3 took the run-start cap out (two readers by who asked, a hold that jumped
 * from capped to whole). Instead a get of a held item asks what the other orders' holds that are not whole still take
 * (LineDeposits.fills, World.fills) on top of its own need, so the logs past the holds are the run's when the cut ends.
 * Every chain is resolved through the planner worker's path (SyncPlanner: the snapshot, PlannerWorker.resolve, the live
 * check); the runner's readers (Goals.keptForRun, DryRun on its free bag) are asked about the very steps the planner priced.
 */
class KeptCraftsTest {
    @AfterEach
    void clear() {
        Only.set("off");
        BatchWay.clear();
    }

    private static List<StepNeeds> needs(Chain c) {
        List<StepNeeds> n = new ArrayList<>();
        for (int i = 0; i < c.steps().size(); i++) n.add(StepNeeds.of(c.steps().get(i)).withUses(i < c.uses().size() ? c.uses().get(i) : null));
        return n;
    }

    /** The runner's free bag (the count reader, StepHands.freeBag over AaRuntime.keptFromOthers): the bag less its hold. */
    private static Map<String, Integer> runnerFree(OrderLineSim s, Goals.Goal g) {
        Map<String, Integer> free = new HashMap<>();
        s.bag.forEach((k, v) -> free.put(k.contains(":") ? k : "minecraft:" + k, v));
        Goals.keptForRun(s.bb, g.id(), true, false).forEach((k, v) -> free.merge("minecraft:" + k, -v, Integer::sum));
        return free;
    }

    private static int chopped(Chain c) {
        int n = 0;
        for (Step x : c.steps()) if (x instanceof Step.ChopTree t) n += t.logs();
        return n;
    }

    private static int lastChop(Chain c) {
        int at = -1;
        for (int i = 0; i < c.steps().size(); i++) if (c.steps().get(i) instanceof Step.ChopTree) at = i;
        return at;
    }

    /** w044's line: oak_log 16 -d stopped with 7 of 16 ("it can still wake"), stick 4 -d running; two trees of 6 known. */
    private static OrderLineSim w044(Goals.Goal[] out) {
        ScenariosTest.toolWorld();
        OrderLineSim s = new OrderLineSim();
        s.seen.put("oak_log", List.of(ScenariosTest.tree(6, 6), ScenariosTest.tree(8, 6)));
        s.bag.put("oak_log", 7);
        s.bb.fact(BatchWay.PLANNER_FACT, s.sync);
        Goals.Goal oak = s.order("oak_log", 16, "b1", true);
        Goals.Goal stick = s.order("stick", 4, "b1", true);
        oak.parked = "w044: stopped, it can still wake";
        oak.park = new Goals.Park(Goals.ParkKind.PLACE, null, 0, null, null);
        s.tick();
        assertTrue(Goals.canWake(oak) && Goals.find(s.bb, "oak_log") == oak, "oak_log parked, can wake, among the goals: " + s.said);
        out[0] = oak;
        out[1] = stick;
        return s;
    }

    /**
     * w044 (deposit/w044.log 1221, 1277-1305: "oak_log 7 (order oak_log (kept for a deposit))", the cut gave 6, the torch's
     * step "needs 1 of logs_that_burn; the bag will hold 0"): the stick's cut asks its one log AND the 9 the parked order's
     * hold still takes (it fills first), so it cuts both trees; after the cuts the hold is whole (16) and the rest is the
     * run's - the runner's dry run passes the planner's steps. Was: a cut for 1, all of it held.
     */
    @Test
    void w044_theCutAsksTheOtherHoldsFillOnTop_thenTheRunnersCraftsRun() {
        Goals.Goal[] o = new Goals.Goal[2];
        OrderLineSim s = w044(o);
        Goals.Goal stick = o[1];
        assertEquals(Map.of("oak_log", 9), Goals.fillsFromOthers(s.bb, stick), "the parked order's hold takes 9 more before any log is free");
        Chain ch = Goals.chain(s.bb, stick);
        assertTrue(ch != null && ch.ok() && !ch.explore(), ch == null ? "no chain" : ch.why());
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        assertTrue(chopped(ch) >= 10, "the cut asks 1 + 9 (was 1): " + ch.steps());
        assertTrue(ch.why().contains("more for the other orders' -d holds of oak_log"), "said in the way's words (rule 7): " + ch.why());
        // the cuts end: 7 + 12; the hold is whole (16), 3 are the run's, the planner's crafts after the cut run
        s.bag.put("oak_log", 19);
        s.tick();
        assertEquals(16, Goals.keptForRun(s.bb, stick.id(), true, true).getOrDefault("oak_log", 0), "the crafts' reader: the hold whole");
        assertEquals(16, Goals.keptForRun(s.bb, stick.id(), true, false).getOrDefault("oak_log", 0), "the count reader: the same");
        assertNull(DryRun.check(ch.steps(), lastChop(ch) + 1, needs(ch), runnerFree(s, stick)), "the runner lets the planner's crafts run");
        // a re-plan inside the run: the planner's world is the runner's free bag (3), no fill left, no cut again
        Chain re = Goals.chain(s.bb, Goals.find(s.bb, stick.r.key()));
        assertTrue(re != null && re.ok() && !re.explore(), re == null ? "no chain" : re.why());
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        assertTrue(re.steps().stream().noneMatch(x -> x instanceof Step.ChopTree), "the re-plan uses the 3 free: " + re.steps());
        assertNull(DryRun.check(re.steps(), 0, needs(re), runnerFree(s, stick)), "the re-plan's steps run on the runner's free bag: " + re.steps());
    }

    /**
     * Review 3 point 3 (the hold jumped from capped to whole): half way, after one tree (7 + 6 = 13), the planner and the
     * runner read the same hold (13, not whole) and the re-plan asks what the hold still takes (3) on top of its own log.
     */
    @Test
    void w044_halfWay_onePlannerAndRunnerHold_theReplanAsksTheRest() {
        Goals.Goal[] o = new Goals.Goal[2];
        OrderLineSim s = w044(o);
        Goals.Goal stick = o[1];
        s.bag.put("oak_log", 13);
        s.seen.put("oak_log", List.of(ScenariosTest.tree(8, 6)));
        s.tick();
        assertEquals(13, Goals.keptForRun(s.bb, stick.id(), true, true).getOrDefault("oak_log", 0), "the crafts' reader: all 13 held, not whole");
        assertEquals(13, Goals.keptFromOthers(s.bb, stick).getOrDefault("oak_log", 0), "the planner's reader: the same 13");
        assertEquals(Map.of("oak_log", 3), Goals.fillsFromOthers(s.bb, stick));
        Chain re = Goals.chain(s.bb, Goals.find(s.bb, stick.r.key()));
        assertTrue(re != null && re.ok() && !re.explore(), re == null ? "no chain" : re.why());
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        assertTrue(chopped(re) >= 4, "1 + the 3 the hold still takes: " + re.steps());
    }

    /**
     * Review 3 point 1 (the cap was by who asked: a trip sibling priced the other order's hold uncapped while the running
     * order's ask was capped): with the cap gone, every order of the trip and the runner read one hold - the stick and its
     * sibling cobblestone (same batch, open) take the parked oak_log order's 7 off alike, and see the same 9 still to fill.
     */
    @Test
    void aTripSibling_readsTheSameHoldAsTheRunningOrder_andTheRunner() {
        Goals.Goal[] o = new Goals.Goal[2];
        OrderLineSim s = w044(o);
        Goals.Goal stick = o[1];
        Goals.Goal cob = s.order("cobblestone", 8, "b1", true);
        s.tick();
        assertEquals(Goals.keptFromOthers(s.bb, stick), Goals.keptFromOthers(s.bb, cob), "the planner's hold: one answer in the trip");
        assertEquals(Goals.fillsFromOthers(s.bb, stick), Goals.fillsFromOthers(s.bb, cob), "what fills first: one answer in the trip");
        assertEquals(7, Goals.keptForRun(s.bb, stick.id(), true, false).getOrDefault("oak_log", 0), "the runner's count reader for the stick's run");
        assertEquals(7, Goals.keptForRun(s.bb, cob.id(), true, false).getOrDefault("oak_log", 0), "and for the sibling's: the same");
        assertEquals(0, Goals.free(s.bb, stick).getOrDefault("oak_log", 0), "the met count's bag (Goals.free, BatchWay.open): the same 7 off");
        assertEquals(0, Goals.free(s.bb, cob).getOrDefault("oak_log", 0), "and the sibling's");
    }

    /** LineDeposits.fills, pure: what the holds read from the bag still take of each item as it grows. */
    @Test
    void fills_theHoldsNotWhole_byItem() {
        OrderLineSim s = new OrderLineSim();
        Goals.Goal oak = s.order("oak_log", 16, "b1", true);
        s.order("cobblestone", 32, "b1", true);
        LineDeposits ld = (LineDeposits) s.bb.fact(LineDeposits.FACT);
        assertEquals(Map.of("cobblestone", 32, "oak_log", 9), ld.fills(Map.of("oak_log", 7), Goals.of(s.bb), null, null));
        assertEquals(Map.of("cobblestone", 32), ld.fills(Map.of("oak_log", 20), Goals.of(s.bb), null, null), "a whole hold takes no more");
        assertEquals(Map.of(), ld.fills(Map.of("oak_log", 20, "cobblestone", 40), Goals.of(s.bb), null, null));
        assertEquals(Map.of("cobblestone", 32), ld.fills(Map.of("oak_log", 7), Goals.of(s.bb), oak, null), "self's own is not another's hold");
    }

    /** The mineshaft planks w068 and w098 priced (lines 955 / 4192: y -12 to -35), 47 m off under 30 blocks of stone. */
    private static final Spot MINESHAFT_PLANKS = new Spot(47, 30, 0, 47, "minecraft:oak_planks", 6, null, 0, false, 30, "minecraft:stone");

    /** w068's stone 1 m ("dig cobblestone seen 1 m") and the planks it priced instead of logs, 47 m under ground. */
    private static OrderLineSim w068(Spot tree) {
        ScenariosTest.toolWorld();
        OrderLineSim s = new OrderLineSim();
        Map<String, List<Spot>> seen = new HashMap<>();
        seen.put("oak_log", List.of(tree));
        seen.put("cobblestone", List.of(new Spot(1, 63, 0, 1, "minecraft:stone", 40)));
        seen.put("oak_planks", List.of(MINESHAFT_PLANKS));
        s.seen.putAll(seen);
        s.bag.put("oak_log", 16);
        s.bb.fact(BatchWay.PLANNER_FACT, s.sync);
        return s;
    }

    /**
     * w068 (line 395: 16 birch_log kept, the log order met; "picks none"; line 1009 "cut tree seen 66 m", lines 955 / 4192
     * mineshaft planks): the cobblestone order's pickaxe. With a tree of the kind near (12 m) the cut of more logs is the
     * way and the kept 16 stay whole; with only the 66 m tree the cut is still among the priced ways (taken or in the losers).
     */
    @Test
    void w068_cutMoreOfTheLog_priced_andWinsWhenNear() {
        OrderLineSim s = w068(ScenariosTest.tree(12, 6));
        s.order("oak_log", 16, "b1", true);
        Goals.Goal cob = s.order("cobblestone", 32, "b1", true);
        s.tick();
        assertNull(Goals.find(s.bb, "oak_log"), "the log order is met: " + s.said);
        Chain ch = Goals.chain(s.bb, cob);
        assertTrue(ch != null && ch.ok() && !ch.explore(), ch == null ? "no chain" : ch.why());
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        assertTrue(ch.steps().stream().anyMatch(x -> x instanceof Step.ChopTree), "cut more of the log, 12 m: " + ch.steps() + " / " + ch.why());
        assertTrue(ch.steps().stream().noneMatch(x -> x instanceof Step.Collect k && k.item().contains("planks")), "not the planks 47 m down: " + ch.steps());
        assertEquals(16, Goals.keptForRun(s.bb, cob.id(), true, true).getOrDefault("oak_log", 0), "the 16 held whole");
        assertNull(DryRun.check(ch.steps(), 0, needs(ch), runnerFree(s, cob)), "the runner's dry run passes the planner's steps");

        OrderLineSim far = w068(ScenariosTest.tree(66, 6));
        far.order("oak_log", 16, "b1", true);
        Goals.Goal cob2 = far.order("cobblestone", 32, "b1", true);
        far.tick();
        Chain c2 = Goals.chain(far.bb, cob2);
        assertTrue(c2 != null && c2.ok(), c2 == null ? "no chain" : c2.why());
        assertTrue(far.sync.drops.isEmpty(), "planner drops: " + far.sync.drops);
        boolean cut = c2.steps().stream().anyMatch(x -> x instanceof Step.ChopTree) || c2.dropped().stream().anyMatch(d -> d.contains("tree 66 m"));
        assertTrue(cut, "the 66 m cut is priced (taken, or among the losers): " + c2.why() + " / " + c2.dropped());
    }

    /**
     * w098 (time/w098.log 1886: "#planks from #logs: no known source for #logs" with the log order's 16 kept; "a cut was
     * priced and dropped before the logs were complete"): the log order still gathering (10 of 16, parked), a tree of 8
     * known 20 m off and planks 47 m down. The cut asks the stick's log and the 6 the hold still takes, wins over the planks,
     * and its logs past the hold are the run's (it seemed that the old cut for 1 came in held, so its crafts were refused
     * and the cut tree was gone for the next plan).
     */
    @Test
    void w098_theCutBeforeTheLogsAreComplete_asksTheHoldsRest_andWins() {
        ScenariosTest.toolWorld();
        OrderLineSim s = new OrderLineSim();
        s.seen.put("oak_log", List.of(new Spot(20, 64, 0, 20, "minecraft:oak_log", 8)));
        s.seen.put("oak_planks", List.of(MINESHAFT_PLANKS));
        s.bag.put("oak_log", 10);
        s.bb.fact(BatchWay.PLANNER_FACT, s.sync);
        Goals.Goal oak = s.order("oak_log", 16, "b1", true);
        Goals.Goal stick = s.order("stick", 4, "b1", true);
        oak.parked = "w098: gathering, stopped";
        oak.park = new Goals.Park(Goals.ParkKind.PLACE, null, 0, null, null);
        s.tick();
        Chain ch = Goals.chain(s.bb, stick);
        assertTrue(ch != null && ch.ok() && !ch.explore(), ch == null ? "no chain" : ch.why());
        assertTrue(s.sync.drops.isEmpty(), "planner drops: " + s.sync.drops);
        assertTrue(chopped(ch) >= 7, "1 + the 6 the hold still takes: " + ch.steps());
        assertTrue(ch.steps().stream().noneMatch(x -> x instanceof Step.Collect k && k.item().contains("planks")), "the near cut, not the planks: " + ch.steps());
        s.bag.put("oak_log", 18);
        s.tick();
        assertNull(DryRun.check(ch.steps(), lastChop(ch) + 1, needs(ch), runnerFree(s, stick)), "after the cut the runner lets the crafts run");
    }
}
