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

/** 0.36.a8 (MINING_PLAN 3.6, phase 4), one-planner: the per-ore scorer the owner's mine verb prices with, its memory and the switch. */
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
        assertEquals("mine iron 1 at 10 60 0 walk", c.command("iron", 3));
        assertTrue(c.why().contains("walk_known 10 60 0"), c.why());
        assertTrue(c.why().contains("dig_known 5 50 0"), c.why());
    }

    @Test
    void aBuriedOreIsDugToWhenTheWalkIsFar() {
        Choice c = IronOptions.choose(facts(List.of(open(80, 60, 0, 80, true), buried(5, 56, 0, 6, 5, false)), 60), new Memory(), 0);
        assertEquals(Way.DIG_KNOWN, c.way());
        assertEquals("mine iron 1 at 5 56 0 dig", c.command("iron", 3));
    }

    /** Owner's rule 2026-10-19 (live r2b: iron 17-18 deep never picked): depth is a cost, not a limit. */
    @Test
    void aDeepOreIsACostlierDigNotADrop() {
        Choice c = IronOptions.choose(facts(List.of(buried(5, 40, 0, 6, 18, false)), 60), new Memory(), 0);
        assertEquals(Way.DIG_KNOWN, c.way(), c.why());
        assertEquals("mine iron 1 at 5 40 0 dig", c.command("iron", 3));
        assertTrue(c.why().contains("past 16"), c.why());
        assertEquals(16 * IronOptions.digBlockV() + 2 * IronOptions.digDeepBlock(), IronOptions.digCost(18), 1e-9);
        // a shallower ore a little farther still wins over the deep one near
        Choice two = IronOptions.choose(facts(List.of(buried(5, 40, 0, 6, 18, false), buried(20, 55, 0, 20, 6, false)), 60), new Memory(), 0);
        assertEquals("20 55 0", two.target().key(), two.why());
        Choice past = IronOptions.choose(facts(List.of(buried(5, 10, 0, 6, IronOptions.digHard() + 1, false)), 60), new Memory(), 0);
        assertNull(past.way());
        assertTrue(past.why().contains("the dig verb reaches " + IronOptions.digHard()), past.why());
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
        assertEquals("goto 2 71 0 placing > mine iron 1 at 2 70 0 dig", c.command("iron", 3));
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
        assertEquals("mine cave iron 3", c.command("iron", 3));
        m.failed(Way.CAVE, null, "no known cave", 0);
        c = IronOptions.choose(f, m, 1);
        assertEquals(Way.STRIP, c.way(), "a failed option is blocked for a while");
        assertTrue(c.why().contains("cave: no known cave"), c.why());
        c = IronOptions.choose(f, m, IronOptions.retryMs() + 1);
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
}
