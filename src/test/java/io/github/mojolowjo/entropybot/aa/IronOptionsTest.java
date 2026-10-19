package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import io.github.mojolowjo.entropybot.aa.IronOptions.Choice;
import io.github.mojolowjo.entropybot.aa.IronOptions.Facts;
import io.github.mojolowjo.entropybot.aa.IronOptions.Memory;
import io.github.mojolowjo.entropybot.aa.IronOptions.Ore;
import io.github.mojolowjo.entropybot.aa.IronOptions.Smelt;
import io.github.mojolowjo.entropybot.aa.IronOptions.Way;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** 0.36.a8 (MINING_PLAN 3.6, phase 4): the IRON rung's option scorer, its memory, the chain rewrite and the switch. */
class IronOptionsTest {
    static final String PLANNED = "mine iron_ore 3 dig then smelt raw_iron 3 then smelt collect all then craft iron_pickaxe 1";

    static Ore open(int x, int y, int z, double dist, boolean sky) { return new Ore(x, y, z, dist, true, true, sky, 1, false, null, false); }

    static Ore buried(int x, int y, int z, double dist, int depth, boolean wet) { return new Ore(x, y, z, dist, true, false, false, depth, wet, null, false); }

    static Facts facts(List<Ore> ores, int meY) {
        return new Facts(ores, meY, false, 0, true, -1, false, "no mine marked (place mine)", 10, false, false, 0);
    }

    @Test
    void anOpenFaceNearIsWalkedTo() {
        Choice c = IronOptions.choose(facts(List.of(open(10, 60, 0, 10, true), buried(5, 50, 0, 5, 12, false)), 60), new Memory(), 0);
        assertEquals(Way.WALK_KNOWN, c.way());
        assertEquals("10 60 0", c.target().key());
        assertEquals("mine iron 1 at 10 60 0 walk", c.command(3));
        assertTrue(c.why().contains("walk_known 10 60 0"), c.why());
        assertTrue(c.why().contains("dig_known 5 50 0"), c.why());
    }

    @Test
    void aBuriedOreIsDugToWhenTheWalkIsFar() {
        Choice c = IronOptions.choose(facts(List.of(open(80, 60, 0, 80, true), buried(5, 56, 0, 6, 5, false)), 60), new Memory(), 0);
        assertEquals(Way.DIG_KNOWN, c.way());
        assertEquals("mine iron 1 at 5 56 0 dig", c.command(3));
    }

    @Test
    void theWaterRuleAndTheFloorDropOptionsWithTheirReason() {
        Ore wet = buried(0, 50, 0, 10, 8, true);
        Ore denied = new Ore(3, 60, 3, 4, true, true, true, 1, false, "no resources taken in main", false);
        Ore floor = new Ore(4, 60, 4, 4, true, true, true, 1, false, "protect box", true);
        Choice c = IronOptions.choose(facts(List.of(wet, denied, floor), 60), new Memory(), 0);
        assertNull(c.way());
        assertTrue(c.why().startsWith("no way to iron"), c.why());
        assertTrue(c.why().contains("dig 0 50 0: digging down from in the water"), c.why());
        assertTrue(c.why().contains("3 60 3: no resources taken in main"), c.why());
        assertTrue(c.why().contains("4 60 4: protect box"), c.why());
        assertTrue(c.why().contains("strip: no mine marked"), c.why());
    }

    @Test
    void anOreAboveIsPillaredToWithEnoughBlocks() {
        Ore up = buried(2, 70, 0, 10, 1, false);        // 10 above me, buried from the top: depth 1 but the dig route is from above
        Facts f = new Facts(List.of(up), 60, false, 12, true, -1, false, null, -1, false, false, 0);
        Choice c = IronOptions.choose(f, new Memory(), 0);
        // dig: 10 + 3 + 10 = 23; pillar: 10 + 50 + 10 = 70: the dig wins, the pillar is the next row
        assertEquals(Way.DIG_KNOWN, c.way());
        assertTrue(c.why().contains("pillar_known 2 70 0"), c.why());
        Memory m = new Memory();
        m.failed(Way.DIG_KNOWN, up, "no path", 0);
        c = IronOptions.choose(f, m, 1);
        assertEquals(Way.PILLAR_KNOWN, c.way());
        assertEquals("goto 2 71 0 then mine iron 1 at 2 70 0 dig", c.command(3));
        Facts few = new Facts(List.of(up), 60, false, 4, true, -1, false, null, -1, false, false, 0);
        c = IronOptions.choose(few, m, 1);
        assertNull(c.way());
        assertTrue(c.why().contains("pillar 2 70 0: 4 of 10 blocks to place"), c.why());
    }

    @Test
    void caveAndStripAreTheFallbacksAndFailuresWeighAndBlock() {
        Facts f = new Facts(List.of(), 60, false, 0, true, 30, true, null, -1, false, false, 0);
        Memory m = new Memory();
        Choice c = IronOptions.choose(f, m, 0);
        assertEquals(Way.CAVE, c.way(), "30 + 120 < 200");
        assertEquals("mine cave iron 3", c.command(3));
        m.failed(Way.CAVE, null, "no known cave", 0);
        c = IronOptions.choose(f, m, 1);
        assertEquals(Way.STRIP, c.way(), "a failed option is blocked for a while");
        assertTrue(c.why().contains("cave: no known cave"), c.why());
        c = IronOptions.choose(f, m, IronOptions.RETRY_MS + 1);
        assertEquals(Way.STRIP, c.way(), "after the retry time the cave counts again, at twice the cost: 300 > 200");
        assertTrue(c.why().contains("cave 300 (a known cave 30 away, failed 1x)"), c.why());
    }

    @Test
    void smeltingOnTheSpotWhenTheCampFurnaceIsFarAndTheBagAllowsIt() {
        Memory m = new Memory();
        assertEquals(Smelt.CAMP, IronOptions.smeltWhere(new Facts(List.of(), 0, false, 0, true, -1, false, null, 20, true, true, 8), m, 0), "within 24: the camp");
        assertEquals(Smelt.HERE, IronOptions.smeltWhere(new Facts(List.of(), 0, false, 0, true, -1, false, null, 80, true, false, 0), m, 0), "far, a furnace in the bag");
        assertEquals(Smelt.CAMP, IronOptions.smeltWhere(new Facts(List.of(), 0, false, 0, true, -1, false, null, 80, false, true, 8), m, 0), "review: no furnace in the bag, the camp (a craft on the spot is not offered yet)");
        assertEquals(Smelt.HERE, IronOptions.smeltWhere(new Facts(List.of(), 0, false, 0, true, -1, false, null, -1, true, false, 0), m, 0), "no furnace known at all");
        m.smeltHereFailed("no free spot next to me", 0);
        assertEquals(Smelt.CAMP, IronOptions.smeltWhere(new Facts(List.of(), 0, false, 0, true, -1, false, null, 80, true, false, 0), m, 1), "here failed: the camp for a while");
    }

    @Test
    void theRewriteReplacesTheOreAndSmeltStepsOnly() {
        Choice c = IronOptions.choose(new Facts(List.of(open(10, 60, 0, 10, true)), 60, false, 0, true, -1, false, null, 80, true, false, 0), new Memory(), 0);
        assertEquals(Smelt.HERE, c.smelt());
        assertEquals("mine iron 1 at 10 60 0 walk", IronOptions.rewrite(IronOptions.split(PLANNED), c), "one ore of 3: the chain ends after it, the rung picks again");
        assertEquals("mine iron 1 at 10 60 0 walk then smelt raw_iron 1 here", IronOptions.rewrite(IronOptions.split("mine iron 1 then smelt raw_iron 1"), c), "one ore left: the rest stays");
        Choice none = new Choice(null, null, Smelt.CAMP, List.of(), List.of());
        assertEquals(PLANNED, IronOptions.rewrite(IronOptions.split(PLANNED), none), "nothing chosen: the planner's steps stay");
        assertTrue(IronOptions.hasMineStep(IronOptions.split("mine strip iron 3 then craft x 1")));
        assertFalse(IronOptions.hasMineStep(IronOptions.split("smelt raw_iron 3 then craft iron_pickaxe 1")));
    }

    @Test
    void whatCountsAsAReasonToSwitch() {
        assertTrue(IronOptions.switchable("stopped at step 1 (mine iron 3 dig): 3 iron ore known within 96 but none I could reach (tried 5)"));
        assertTrue(IronOptions.switchable("stopped at step 1 (mine iron 3 dig): all 2 nearest known iron are below me and I'm in the water"));
        assertTrue(IronOptions.switchable("no progress"));
        assertTrue(IronOptions.switchable("stopped at step 1 (goto 2 71 0): no path to 2 71 0"));
        assertFalse(IronOptions.switchable("stopped: your stop"));
        assertFalse(IronOptions.switchable("interrupted: low health or danger - aa paused the step"));
        assertFalse(IronOptions.switchable("stopped at step 4 (craft iron_pickaxe 1): missing 2 stick"));
        assertEquals("ore", IronOptions.failedPart("stopped at step 1 (mine iron 3 dig): x"));
        assertEquals("ore", IronOptions.failedPart("timeout"));
        assertEquals("smelt", IronOptions.failedPart("stopped at step 2 (smelt raw_iron 3 here): no free spot next to me"));
        assertEquals("other", IronOptions.failedPart("stopped at step 4 (craft iron_pickaxe 1): missing 2 stick"));
    }

    // ---- the rung with the picker ----

    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    Facts live = new Facts(List.of(open(10, 60, 0, 10, true), buried(5, 56, 0, 6, 5, false)), 60, false, 0, true, 40, false, "no mine marked", 10, false, false, 0);

    Rung ironRung() {
        bb.in.pickTier = 2;
        bb.in.pickaxes = 1;
        bb.fact(IronOptions.FACTS, (Supplier<Facts>) () -> live);
        port.resolveTo = PLANNED;
        return new Rung("needs.iron", io.github.mojolowjo.entropybot.aa.core.TaskKind.IRON, b -> b.in.pickTier < 3, b -> null, b -> "goal iron tools", new IronOptions.Picker());
    }

    @Test
    void aFailedWaySwitchesToTheNextAtOnceWithoutACooldown() {
        Rung r = ironRung();
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("mine iron 1 at 10 60 0 walk", port.started.get(0).chain());
        port.fail(1, "stopped at step 1 (mine iron 3): 2 iron ore known within 96 but none I could reach (tried 5)");
        assertEquals(Status.RUNNING, r.tick(bb), "the next option starts in the same tick");
        assertEquals(2, port.started.size());
        assertEquals("mine iron 1 at 5 56 0 dig", port.started.get(1).chain());
        assertTrue(r.options().contains("dig_known 5 56 0"), r.options());
        assertTrue(String.join("|", port.log).contains("switching: dig_known 5 56 0"), port.log.toString());
        // the dig fails too: the cave is next
        port.fail(2, "stopped at step 1 (mine iron 3 dig): couldn't get to the known iron at 5 56 0");
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("mine cave iron 3 then smelt raw_iron 3 then smelt collect all then craft iron_pickaxe 1", port.started.get(2).chain());
        // the cave fails: nothing left (strip has no mine) -> the rung fails with the options line, and cools down
        port.fail(3, "stopped at step 1 (mine cave iron 3): no known cave");
        assertEquals(Status.FAILURE, r.tick(bb));
        assertTrue(r.reason().contains("options: no way to iron"), r.reason());
        assertTrue(r.reason().contains("walk 10 60 0:"), r.reason());
        assertEquals(3, port.started.size());
        clock.advance(1000);
        assertEquals(Status.FAILURE, r.tick(bb), "cooling down");
        assertTrue(r.reason().startsWith("cooling down"), r.reason());
    }

    @Test
    void theOwnersStopAndACraftFailureEndTheRungAsBefore() {
        Rung r = ironRung();
        r.tick(bb);
        port.fail(1, "stopped at step 4 (craft iron_pickaxe 1): missing 2 stick");
        assertEquals(Status.FAILURE, r.tick(bb));
        assertEquals(1, port.started.size(), "no switch for a craft failure");
        assertTrue(r.reason().contains("missing 2 stick"), r.reason());
    }

    @Test
    void withoutFactsThePlannersChainRunsAsItIs() {
        Rung r = ironRung();
        bb.fact(IronOptions.FACTS, null);
        r.tick(bb);
        assertEquals(PLANNED, port.started.get(0).chain());
        assertTrue(r.options().contains("no iron facts"), r.options());
    }

    /** Round 2 item 5: the walk names its ore, so a failed walk blocks that ore only: the next open ore is the next option. */
    @Test
    void aWalkFailureBlocksThatOreOnly() {
        Rung r = ironRung();
        live = new Facts(List.of(open(10, 60, 0, 10, true), open(12, 60, 0, 12, true), open(14, 60, 0, 14, true)), 60, false, 0, true, -1, false, null, 10, false, false, 0);
        r.tick(bb);
        assertEquals("mine iron 1 at 10 60 0 walk", port.started.get(0).chain());
        port.fail(1, "stopped at step 1 (mine iron 1 at 10 60 0 walk): couldn't get to the iron_ore at 10 60 0 (no path)");
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("mine iron 1 at 12 60 0 walk", port.started.get(1).chain());
    }
    @Test
    void switchesRunThroughEveryWayThenTheRungFails() {
        Rung r = ironRung();
        // walk, dig, pillar, cave, strip: five ways, four switches (MAX_SWITCHES), then the rung fails
        live = new Facts(List.of(open(10, 60, 0, 10, true), buried(5, 56, 0, 6, 5, false), buried(2, 70, 0, 10, 1, false)), 60, false, 12, true, 30, true, null, 10, false, false, 0);
        r.tick(bb);
        for (long id = 1; id <= IronOptions.MAX_SWITCHES; id++) {
            port.fail(id, "stopped at step 1 (" + IronOptions.split(port.started.get((int) id - 1).chain()).get(0) + "): no path to the ore");
            assertEquals(Status.RUNNING, r.tick(bb), "switch " + id);
        }
        assertEquals(IronOptions.MAX_SWITCHES + 1, port.started.size());
        port.fail(IronOptions.MAX_SWITCHES + 1, "stopped at step 1 (mine strip iron 3): no path to the ore");
        assertEquals(Status.FAILURE, r.tick(bb), "nothing left, and the cap is reached");
    }

    /** Review: smelting on the spot failed: the ore is mined already, the chain goes on at the camp without the ore step. */
    @Test
    void aSmeltHereFailureGoesOnWithoutTheOreStep() {
        Rung r = ironRung();
        live = new Facts(List.of(open(10, 60, 0, 10, true)), 60, false, 0, true, -1, false, null, 80, true, false, 0);
        r.tick(bb);
        assertEquals("mine iron 1 at 10 60 0 walk", port.started.get(0).chain());
        port.fail(1, "stopped at step 2 (smelt raw_iron 3 here): no free spot next to me for mine");
        assertEquals(Status.RUNNING, r.tick(bb));
        assertEquals("smelt raw_iron 3 then smelt collect all then craft iron_pickaxe 1", port.started.get(1).chain());
    }
}
