package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.NeedChain.Chain;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.moves.FactChange;
import io.github.mojolowjo.entropybot.aa.moves.StationPlace;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import io.github.mojolowjo.entropybot.guard.BreakPolicy;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.guard.Policy;
import io.github.mojolowjo.entropybot.guard.RefusedBreaks;
import io.github.mojolowjo.entropybot.recorder.IncidentText;
import io.github.mojolowjo.entropybot.restore.RestoreRules;
import io.github.mojolowjo.entropybot.world.SurfaceHeight;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Merge review of job-d6 (2026-11-25), the tools, surface and guard findings 10, 11/16, 13, 15 and 17, each replayed on the
 * review's own sequence.
 */
class MergeReviewToolsTest {
    @AfterEach
    void clear() {
        TestData.clear();
        FactChange.clear();
        Tuning.reset();
    }

    // ---- 10: a roofed base read as under ground; the climb won over the chest walk still computing ----

    /**
     * The main chest in a roofed room: the feet 5 under the plank roof (rock 5, no sky), so Surface.under. The walk to the
     * chest has no answer on its first ask from these feet; the climb is priced by formula (5 x 3 + 10 walk-blocks) and the
     * walk up has an answer too. Before the fix the climb or the walk up won at once (a leg out of the room and back in).
     * Now the bot stands on the chest walk's answer, and once it lands the put is planned.
     */
    @Test
    void f10_aRoofedRoom_theWaysOutWaitForTheChestWalk() {
        NeedChain.Surface roof = new NeedChain.Surface(5, 0, 45, 0, 5);
        assertTrue(roof.under(), "a 5-deep roof reads as under ground (the older root, unchanged)");
        Chain waits = NeedChain.deposit(NeedChainDepositTest.OWED, NeedChainDepositTest.CHEST, NeedChainDepositTest.world(Map.of("minecraft:dirt", 64), roof,
                PathPrice.Answer.computing("queued: 1 in flight"), NeedChainDepositTest.priced(3), List.of()));
        assertFalse(waits.ok(), waits.why());
        assertTrue(waits.missing().startsWith(PathPrice.NOT_KNOWN), waits.missing());
        assertTrue(waits.steps().isEmpty(), "no leg out of the room while the chest walk computes: " + waits.steps());
        Chain climbOnly = NeedChain.deposit(NeedChainDepositTest.OWED, NeedChainDepositTest.CHEST, NeedChainDepositTest.world(Map.of("minecraft:dirt", 64), roof,
                PathPrice.Answer.computing("queued: 1 in flight"), PathPrice.Answer.unreachable("no path"), List.of()));
        assertTrue(climbOnly.steps().stream().noneMatch(s -> s instanceof Step.Climb), "the climb waits too: " + climbOnly.steps());
        Chain landed = NeedChain.deposit(NeedChainDepositTest.OWED, NeedChainDepositTest.CHEST, NeedChainDepositTest.world(Map.of("minecraft:dirt", 64), roof,
                NeedChainDepositTest.priced(2), NeedChainDepositTest.priced(3), List.of()));
        assertTrue(landed.ok(), landed.why());
        assertInstanceOf(Step.Deposit.class, landed.steps().get(landed.steps().size() - 1), landed.why());
    }

    /**
     * Send-back: once the chest walk is priced, the walk up to a door nearer than the chest competed on its leg alone (3 s
     * against the whole walk and put, 20 s) and won: a trip out and back in. A way out is ranked with the straight walk on
     * from its end now (the surface cell 0 45 0 to the chest at 100 70 0, 103 m), so the whole walk wins; a long tunnel
     * walk (200 s) still loses to the walk up and the walk over the top (NeedChainDepositTest.underGround_theWalkUpBeatsTheClimb).
     */
    @Test
    void f10_aRoofedRoomWithADoorNearerThanTheChest_theWholeWalkWins() {
        NeedChain.Surface roof = new NeedChain.Surface(5, 0, 45, 0, 5);
        Chain c = NeedChain.deposit(NeedChainDepositTest.OWED, NeedChainDepositTest.CHEST, NeedChainDepositTest.world(Map.of("minecraft:dirt", 64), roof,
                NeedChainDepositTest.priced(20), NeedChainDepositTest.priced(3), List.of()));
        assertTrue(c.ok(), c.why());
        assertInstanceOf(Step.ToStation.class, c.steps().get(0), c.why());
        assertInstanceOf(Step.Deposit.class, c.steps().get(c.steps().size() - 1), c.why());
        assertEquals(20, c.seconds(), 0.1, "the price is the walk's own: " + c.why());
        assertTrue(c.dropped().stream().anyMatch(d -> d.contains("walk up") && d.contains("a leg only, ranked")), c.dropped().toString());
        Chain tunnel = NeedChain.deposit(NeedChainDepositTest.OWED, NeedChainDepositTest.CHEST, NeedChainDepositTest.world(Map.of("minecraft:dirt", 64), roof,
                NeedChainDepositTest.priced(200), NeedChainDepositTest.priced(3), List.of()));
        assertEquals(List.of(new Step.ToSurface(0, 45, 0)), tunnel.steps(), tunnel.why());
        assertEquals(3, tunnel.seconds(), 0.1, "the leg's price is its own seconds: " + tunnel.why());
    }

    // ---- 11 and 16: the station place step read its own leaf-free ring ----

    static NeedChain.Surface at(int[] feet, boolean sky, int noLeaves, java.util.function.IntBinaryOperator ground) {
        return D14StationsTest.at(feet, sky, noLeaves, ground);
    }

    /**
     * A) the bot at the bottom of its own 1x1 shaft, 3 deep, open to the sky, the ground round it at 67: one fact (the
     * planner's Surface, built as Jobs.feetSurface builds it). Send-back: the request is the rim the surface leg targets,
     * within the place reach (the fork's own cell search on the shaft: baritone.process.StationShaftCellsTest).
     */
    @Test
    void f11_aShallowOpenShaft_thePlaceStepAndThePlannerAgree() {
        int[] feet = {0, 64, 0};
        NeedChain.Surface s = io.github.mojolowjo.entropybot.commands.Jobs.feetSurface(feet, 0, (x, z) -> x == 0 && z == 0 ? 64 : 67);
        assertTrue(s.under() && s.hole() && s.depth() == 3, "the planner, the deposit and the explore: under ground in a hole (" + s + ")");
        assertEquals(at(feet, true, 64, (x, z) -> x == 0 && z == 0 ? 64 : 67), s, "the live build and the planner's agree");
        StationPlace.Ask a = StationPlace.ask(feet, s, null);
        assertTrue(a.surface(), "the rim 3 up is within the place reach: " + a.why());
        assertTrue(a.why().contains("the rim of the hole"), a.why());
    }

    /** B) flat ground in a dark oak grove: the leaf-free ring reads the trunk tops, the ground under any tree is flat. */
    @Test
    void f16_aGroveOfTrunks_isTheSurfaceToBoth() {
        int[] feet = {0, 64, 0};
        NeedChain.Surface s = at(feet, false, 64, (x, z) -> 64);
        assertFalse(s.under(), s.toString());
        assertTrue(StationPlace.ask(feet, s, null).surface(), "the place step: the surface, as the planner");
        assertFalse(StationPlace.ask(feet, null, null).surface(), "not known: never claimed (rule 15)");
    }

    // ---- 13: a modded pickaxe in the bag reached the planner as minecraft:<path> ----

    @Test
    void f13_aModdedPickaxeInTheBag_isAPickaxeToThePlanner() {
        String mod = "leafscopperbackport:copper_pickaxe";
        String key = Thing.path(mod);
        assertEquals(mod, key, "the sensed bag keeps a modded item's namespace");
        assertEquals("stone_pickaxe", Thing.path("minecraft:stone_pickaxe"), "a minecraft item by its path, as before");
        PlanBudgetTest.data();
        NeedChain.World w = PlanBudgetTest.world(Map.of(key, 1), PlanBudgetTest.seen(), 0);
        assertTrue(w.bag().containsKey(mod), w.bag().toString());
        Chain c = NeedChain.resolve("cobblestone", 4, w);
        assertTrue(c.ok(), c.why());
        assertTrue(c.steps().stream().noneMatch(s -> s instanceof Step.Craft cr && cr.item().endsWith("_pickaxe")),
                "the copper pickaxe digs stone: no pickaxe made first: " + c.steps());
        // an order for a modded item counts the bag by the same key
        assertEquals(List.of(mod), new Thing.Result("x", "leafscopperbackport:copper_pickaxe", false, List.of(mod), List.of(Thing.path(mod)), 0, null, null, null).counts());
    }

    /**
     * Send-back: the readers of the sensed bag that still stripped the namespace. The bag as SenseRuntime keys it (Thing.path:
     * a modded item by its full id). A modded junk item was counted 0 and never dropped; the owner building with a modded
     * block in the bot's bag got a fetch of minecraft:&lt;path&gt; instead of a give.
     */
    @Test
    void f13_theBagsOtherReaders_readAModdedItemByItsOneKey() {
        Map<String, Integer> bag = Map.of(Thing.path("modx:rotten_bark"), 10, Thing.path("modx:slate_bricks"), 50, "oak_planks", 2);
        io.github.mojolowjo.entropybot.aa.core.Inputs in = new io.github.mojolowjo.entropybot.aa.core.Inputs();
        in.bag = bag;
        var facts = io.github.mojolowjo.entropybot.commands.NeedFacts.bag(in, bag, List.of("modx:rotten_bark"), 0);
        assertEquals(1, facts.junkSlots(), "the modded junk is counted: " + facts.junkDrops());
        assertEquals(List.of(new Step.DropJunk("modx:rotten_bark", 10)), facts.junkDrops());
        in.ownerOnline = true;
        in.ownerDist = 4;
        in.ownerAct = "building";
        in.ownerPlaced = "modx:slate_bricks";
        var p = io.github.mojolowjo.entropybot.aa.brain.Help.pick(in, 20);
        assertNotNull(p);
        assertEquals("give", p.what(), p.steps().toString());
        assertEquals(new Step.Give("modx:slate_bricks", 32), p.steps().get(0));
        assertTrue(io.github.mojolowjo.entropybot.aa.core.Inputs.has(bag, "modx:slate_bricks"));
        assertFalse(io.github.mojolowjo.entropybot.aa.core.Inputs.has(bag, "othermod:slate_bricks"), "another mod's item of the same path is another item");
    }

    // ---- 15: the travel click refused a modded world-gen stone the path was priced through ----

    @Test
    void f15_aModdedStone_thePathAndTheClickGiveOneAnswer() {
        TestData d = TestData.planner();
        d.block("modx:slate", 1.5, 0);
        d.stone.add("modx:slate");
        d.tag("modx:slate", "minecraft:base_stone_overworld");
        d.install();
        BreakPolicy.Snapshot snap = BreakPolicy.Snapshot.of(Policy.EMPTY, true);
        BreakPolicy.BlockFacts slate = new BreakPolicy.BlockFacts(true, "modx:slate", false, false, BreakPolicy.Fluid.NONE, BreakPolicy.Fluid.NONE,
                BreakPolicy.Fluid.NONE, BreakPolicy.Fluid.NONE, false, false, false, BreakPolicy.Structure.NO, false, 10);
        BreakPolicy.Verdict path = BreakPolicy.judge(snap, new BreakPolicy.Ask(BreakPolicy.Action.BREAK, BreakPolicy.Intent.TRAVEL, "minecraft:overworld", 0, 30, 0, slate, BreakPolicy.Where.DRY));
        assertTrue(path.allowed(), "Baritone's path cost prices it: " + path);
        assertTrue(GuardCore.travelClickLets(path, true, "modx:slate"), "the click lets the same break through (no namespace rule)");
        assertTrue(RestoreRules.natural("minecraft:stone"));
        assertFalse(RestoreRules.natural("modx:planks_of_mine"), "a modded block with no terrain tag or data stays out");
        assertFalse(GuardCore.travelClickLets(path, false, "modx:slate"), "no job: the fence holds");
    }

    // ---- 17: a one-cell refusal loop never reached the recorder's refusal streak ----

    @Test
    void f17_aOneCellRefusalLoop_stillFiresTheRecordersStreak() {
        RefusedBreaks r = new RefusedBreaks();
        IncidentText.GuardStreak streak = new IncidentText.GuardStreak(5, 60_000L, 5 * 60_000L);
        int events = 0, lines = 0;
        boolean fired = false;
        for (int i = 0; i < 8115; i++) {                     // w072: 8,115 refusals of one rail
            long now = 1000L + i * 50L;                        // one a tick
            boolean ring;
            if (r.note("minecraft:overworld", -25, -32, -123, "minecraft:rail", "structure block outside my areas")) { ring = true; lines++; }
            else ring = r.echo("minecraft:overworld", -25, -32, -123);
            if (ring) { events++; if (streak.refused(now)) fired = true; }
        }
        assertEquals(1, lines, "the log line is still said once for the cell");
        assertTrue(fired, "the streak fires on the hot cell");
        assertTrue(events < 20, "a few ring events, not thousands: " + events);
        assertTrue(r.take().contains("8115 times"));
    }

    /**
     * Send-back: the guard's decision (RefusedBreaks.say), its event (GuardCore.refusalEvent, as Guard.refuse pushes it), the
     * recorder's ring and its scan (IncidentText.GuardStreak.scan, as FlightRecorder.guardScan runs it each second). A slow
     * loop, one refused click every 4 s, fires the streak within a minute; after the cell's count is said (take), the first
     * repeat is heard again.
     */
    @Test
    void f17_aSlowLoop_throughTheRingAndTheRecordersScan() {
        RefusedBreaks r = new RefusedBreaks();
        io.github.mojolowjo.entropybot.events.EventRing ring = new io.github.mojolowjo.entropybot.events.EventRing();
        IncidentText.GuardStreak streak = new IncidentText.GuardStreak(IncidentText.GuardStreak.limit(), 60_000L, 5 * 60_000L);      // as FlightRecorder
        GuardCore.Verdict v = new GuardCore.Verdict(false, "structure block outside my areas", true, false);
        long seq = ring.lastSeq(), firedAt = -1;
        for (int i = 0; i < 20 && firedAt < 0; i++) {
            long now = 1000L + i * 4000L;
            RefusedBreaks.Say say = r.say("minecraft:overworld", -25, -32, -123, "minecraft:rail", v.reason());
            if (say != RefusedBreaks.Say.COUNT) GuardCore.refusalEvent(ring, "break", -25, -32, -123, v, "refused break at -25 -32 -123: " + v.reason(), say);
            String fired = IncidentText.GuardStreak.scan(streak, ring.since(seq, io.github.mojolowjo.entropybot.events.EventRing.CAPACITY), now);
            seq = ring.lastSeq();
            if (fired != null) firedAt = now - 1000L;              // since the loop's first click
        }
        assertTrue(firedAt >= 0 && firedAt <= 60_000L, "the streak fires within a minute: " + firedAt);
        assertNotNull(r.take());
        assertEquals(RefusedBreaks.Say.ECHO, r.say("minecraft:overworld", -25, -32, -123, "minecraft:rail", v.reason()), "the first repeat after the saying is heard");
    }
}
