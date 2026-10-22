package io.github.mojolowjo.entropybot.aa;

import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.aa.brain.Brain;
import io.github.mojolowjo.entropybot.aa.brain.Goals;
import io.github.mojolowjo.entropybot.aa.brain.Only;
import io.github.mojolowjo.entropybot.aa.brain.TestData;
import io.github.mojolowjo.entropybot.aa.brain.Thing;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * orders-tier (0.39.z9, owner 2026-10-22 "AA only takes requests for tasks"): {@code aa on} = orders and reflexes; with no
 * order the brain starts nothing; an order that cannot progress is parked with its reason, said once, never retried.
 */
class OrdersTierTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Blackboard bb = new Blackboard(clock, port, new Tracer());

    @AfterEach
    void clear() {
        Only.set("off");
        TestData.clear();
        Tuning.reset();
    }

    /** Needs that would run with the full set: no torches, a wood stock-up wanted. */
    private void needsWanted() {
        bb.in.pickTier = 3;
        bb.in.pickaxes = 1;
        bb.in.pickPct = 100;
        bb.in.tableKnown = bb.in.furnaceKnown = bb.in.baseKnown = bb.in.tableInBag = true;
        bb.in.torches = 0;
        bb.in.coal = 32;
        bb.in.ironIngots = 40;
        bb.in.wood = 10;
    }

    @Test
    void ordersOnlyWithNoOrderTheBrainPicksNothingFor100Ticks() {
        needsWanted();
        Only.set(Only.ORDERS);
        AaTree tree = AaTree.build(new AaScorer());
        for (int i = 0; i < 100; i++) {
            tree.tick(bb);
            assertNull(tree.brain.runningDesire(), "tick " + i + ": " + tree.brain.whyLines(10));
            clock.advance(1000);
        }
        assertTrue(port.started.isEmpty(), "no job, no idle wait: " + port.started);
        assertTrue(tree.brain.lastScored().stream().filter(s -> !s.d().id.equals(AaDesires.DANGER_ID)).allMatch(s -> s.score() <= 0),
                "every need, want and the idle set at 0: " + tree.brain.whyLines(30));
    }

    @Test
    void theSameWorldWithNeedsOnStartsWork() {          // the test above is not vacuous
        needsWanted();
        Only.set(Only.NEEDS);
        AaTree tree = AaTree.build(new AaScorer());
        for (int i = 0; i < 20; i++) {
            tree.tick(bb);
            clock.advance(1000);
        }
        assertFalse(port.started.isEmpty(), tree.brain.whyLines(10).toString());
    }

    @Test
    void theSwitchDefaultsToOrdersAndSavesNeedsOnPerWorld() {
        JsonObject sw = new JsonObject();
        assertFalse(AaSwitch.hasMode(sw));
        assertEquals(Only.ORDERS, AaSwitch.mode(sw), "no saved choice = orders only");
        assertEquals("orders only", Only.set(AaSwitch.mode(sw)) == null ? null : Only.modeText());
        AaSwitch.only(sw, Only.NEEDS);
        assertNull(Only.set(AaSwitch.mode(sw)), "needs on = the full set");
        assertEquals("needs on", Only.modeText());
        Only.set("torches");
        assertEquals("only: torches", Only.modeText());
        assertFalse(AaSwitch.ai(sw), "the AI translator is off until ai on");
        AaSwitch.ai(sw, true);
        assertTrue(AaSwitch.ai(sw));
    }

    @Test
    void ordersOnlyKeepsTheOwnersGoalsAndSilencesDanger() {
        // 0.39.z11 (owner 2026-10-22 "turn off the danger orders for now"): danger is silent in orders mode too; the
        // true reflexes live outside the brain
        Only.set(Only.ORDERS);
        assertEquals(0, Only.apply(AaDesires.DANGER_ID, io.github.mojolowjo.entropybot.aa.brain.Desire.Kind.UPKEEP, 70), 1e-9);
        assertEquals(70, Only.apply("goal.x", io.github.mojolowjo.entropybot.aa.brain.Desire.Kind.OWNER, 70), 1e-9);
        assertEquals(0, Only.apply("needs.food", io.github.mojolowjo.entropybot.aa.brain.Desire.Kind.UPKEEP, 80), 1e-9);
        assertEquals(0, Only.apply(AaDesires.IDLE_ID, io.github.mojolowjo.entropybot.aa.brain.Desire.Kind.UPKEEP, 5), 1e-9);
        assertEquals(0, Only.apply("night.sleep", io.github.mojolowjo.entropybot.aa.brain.Desire.Kind.UPKEEP, 30), 1e-9);
        assertEquals(100, Only.apply("needs.food", io.github.mojolowjo.entropybot.aa.brain.Desire.Kind.UPKEEP, 100), 1e-9, "the owner's aa do");
    }

    @Test
    void anOrderThatFailsIsParkedOnceWithItsReasonAndNeverRetried() {
        Only.set(Only.ORDERS);
        ScenariosTest.toolWorld();
        var r = Thing.resolve("stone tools", ToolsChainsTest.CATALOG);
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> ScenariosTest.w(Map.of("minecraft:cobblestone", 12, "minecraft:stick", 8), true, false, Map.of()));
        bb.in.bag = Map.of("minecraft:cobblestone", 12, "minecraft:stick", 8);
        Goals.Goal g = Goals.add(bb, r, 0, 0, clock.now());
        Brain br = new Brain("brain", "t", AaDesires.ownerGoal(g));
        br.tick(bb);
        assertEquals(1, port.started.size(), "the order runs: " + br.whyLines(5));
        port.fail(1, "craft stone_axe: no crafting table in reach");
        br.tick(bb);
        assertEquals("craft stone_axe: no crafting table in reach", g.parked);
        for (int i = 0; i < 100; i++) {            // 500 s: no hold loop, no retry every 5 s
            clock.advance(5000);
            br.tick(bb);
        }
        assertEquals(1, port.started.size(), "never started again: " + port.started);
        assertEquals(1, port.told.size(), "said once in the whisper: " + port.told);
        assertTrue(port.told.get(0).contains("stopped: craft stone_axe: no crafting table in reach"), port.told.get(0));
        assertEquals(1, port.log.stream().filter(l -> l.startsWith("order ")).count(), "said once in the log: " + port.log);
        assertTrue(Goals.line(bb, g, "score 0").contains("parked: craft stone_axe"), "aa goals shows it");
        // aa want again: a fresh goal (the old holds forgotten) runs
        Goals.Goal again = Goals.add(bb, r, 0, 0, clock.now());
        assertNull(again.parked);
        br.failures.forget(again.id());
        br.add(AaDesires.ownerGoal(again));
        br.tick(bb);
        assertEquals(2, port.started.size(), "aa want again runs it: " + br.whyLines(5));
        // aa drop clears it
        Goals.of(bb).remove(again);
        assertTrue(br.remove(again.id(), "aa drop"));
        assertTrue(Goals.of(bb).isEmpty());
    }

    @Test
    void aBusySlotIsNoReasonToPark() {
        Only.set(Only.ORDERS);
        ScenariosTest.toolWorld();
        var r = Thing.resolve("stone tools", ToolsChainsTest.CATALOG);
        bb.fact(NeedChain.WORLD_FACT, (Supplier<NeedChain.World>) () -> ScenariosTest.w(Map.of("minecraft:cobblestone", 12, "minecraft:stick", 8), true, false, Map.of()));
        bb.in.bag = Map.of("minecraft:cobblestone", 12, "minecraft:stick", 8);
        Goals.Goal g = Goals.add(bb, r, 0, 0, clock.now());
        Brain br = new Brain("brain", "t", AaDesires.ownerGoal(g));
        port.refuse = "busy: the owner's job";
        br.tick(bb);
        assertNull(g.parked);
        assertTrue(port.told.isEmpty());
    }
}
