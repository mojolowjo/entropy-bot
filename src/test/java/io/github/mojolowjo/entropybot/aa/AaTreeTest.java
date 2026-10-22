package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.Cooldown;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Request;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA v0 P5 (AA_PLAN 9, 12): the ladder from canned inputs, danger pause/resume, the owner's preempt, the AI queue. */
class AaTreeTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Tracer tracer = new Tracer();
    final Blackboard bb = new Blackboard(clock, port, tracer);
    final AaTree tree = AaTree.build(new AaScorer());

    /** one-planner: the needs' chains need the game's data and a world: a tree, stone, coal and iron ore known around. */
    @BeforeEach
    void world() {
        var d = io.github.mojolowjo.entropybot.aa.brain.TestData.planner();
        for (String m : java.util.List.of("wooden", "stone", "iron")) d.tag(m + "_pickaxe", "minecraft:pickaxes");
        d.install();
        java.util.Map<String, NeedChain.Spot> spots = java.util.Map.of("minecraft:oak_log", new NeedChain.Spot(10, 64, 0, 10, "minecraft:oak_log", 64),
                "minecraft:cobblestone", new NeedChain.Spot(0, 60, 16, 16, "minecraft:stone", 200), "minecraft:coal", new NeedChain.Spot(0, 40, 20, 20, "minecraft:coal_ore", 40),
                "minecraft:raw_iron", new NeedChain.Spot(0, 30, 20, 25, "minecraft:iron_ore", 40));
        bb.fact(NeedChain.WORLD_FACT, (java.util.function.Supplier<NeedChain.World>) () -> new NeedChain.World(bb.in.bag, bb.in.tableKnown, bb.in.furnaceKnown,
                item -> spots.containsKey(item) ? java.util.List.of(spots.get(item)) : java.util.List.of(), null, null, null, 0));
    }

    @AfterEach
    void clear() { io.github.mojolowjo.entropybot.aa.brain.TestData.clear(); }

    /** A fresh Peaceful start: nothing in the bag, nothing known. */
    void freshStart() {
        bb.in.pickTier = 0;
        bb.in.pickaxes = 0;
        bb.in.tableKnown = false;
        bb.in.furnaceKnown = false;
        bb.in.torches = 0;
        bb.in.baseKnown = false;
        bb.in.bedKnown = false;
        bb.in.baseChests = false;
        bb.in.freeSlots = 36;
        bb.in.wool = 0;
        bb.in.basePlace = null;
        bb.in.hungerDrains = false;
    }

    @Test
    void aFreshWorldStartsWorkAtOnce() {
        // AREAS (0.39.2): no area rung - the world is open, AA works where it stands
        freshStart();
        assertEquals(Status.RUNNING, tree.tick(bb));
        assertFalse(port.started.isEmpty(), "a task was started");
    }

    /** One pickaxe of this tier, fresh. */
    void pick(int tier) {
        bb.in.pickTier = tier;
        bb.in.pickaxes = 1;
        bb.in.pickPct = 100;
    }

    /** AA_ROUND2 4: every idle stock band met (a Peaceful base, worth 16, comes after them). */
    void stocked() {
        bb.in.wood = AaScorer.woodHi();
        bb.in.cobble = AaScorer.stoneHi();
        bb.in.coal = AaScorer.coalHi();
        bb.in.ironIngots = AaScorer.ironHi();
        bb.in.torches = AaScorer.torchHi();
    }

    String lastChain() { return port.started.get(port.started.size() - 1).chain(); }

    @Test
    void theLadderRunsTheFirstUnmetRungAndCommitsToIt() {
        freshStart();
        assertEquals(Status.RUNNING, tree.tick(bb));
        assertFalse(port.started.isEmpty(), "no table at all: the table need starts a task (there is no tools need: a pickaxe comes with a dig)");
        assertEquals("needs", tree.driver());
        assertEquals(TaskKind.TABLE_FURNACE, bb.task().kind);
        assertTrue(lastChain().endsWith("craft crafting_table 1"), lastChain());
        // the bag changes meanwhile: no re-choice
        bb.in.torches = 0;
        pick(1);
        assertEquals(Status.RUNNING, tree.tick(bb));
        assertEquals(1, port.started.size());
        port.done(1, "done");
        assertEquals(Status.SUCCESS, tree.tick(bb));
        assertNull(bb.task());
        bb.in.tableKnown = true;
        tree.tick(bb);
        assertTrue(lastChain().endsWith("craft furnace 1"), lastChain());
        port.done(2, "done");
        tree.tick(bb);
        bb.in.furnaceKnown = true;
        tree.tick(bb);
        assertTrue(lastChain().endsWith("craft torch 16"), lastChain());
        port.done(3, "done");
        tree.tick(bb);
        bb.in.torches = 16;
        tree.tick(bb);
        // AA_ROUND2 4: Peaceful (danger 0): a base is worth 16 (Worth.base), so iron (70) comes first
        assertTrue(lastChain().endsWith("craft iron_pickaxe 1"), "bed (no wool) and bag (no chests) are skipped with reasons: " + lastChain());
        port.done(4, "done");
        tree.tick(bb);
        pick(3);
        stocked();                                // Peaceful base (16): after the idle stock-ups
        tree.tick(bb);
        assertEquals("shelter", lastChain(), "no area: a shelter, not a house");
        port.done(5, "done");
        assertEquals(Status.SUCCESS, tree.tick(bb));
        assertTrue(bb.flag(AaLadder.SHELTER_FACT), "the shelter is AA's own fact");
        assertTrue(tree.brain.whyLines(20).stream().anyMatch(l -> l.startsWith("needs.bed PROGRESS 0") && l.contains("parked: no wool source")), "parked, not scored: " + tree.brain.whyLines(20));
        assertTrue(tree.brain.whyLines(20).stream().anyMatch(l -> l.startsWith("needs.bag UPKEEP -1")), "BRAIN B1: a met Need scores -1: " + tree.brain.whyLines(20));
        assertEquals(0, tracer.counts.switches, "no task replaced another");
        assertEquals(0, tracer.counts.pointless);
    }

    java.util.List<String> reasons() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Need r : Need.all(tree.ladder)) out.add(r.last() == Status.FAILURE ? r.reason() : r.id + " " + r.last());
        return out;
    }

    @Test
    void housePrefersAnAreaAndTheBagRungNeedsChests() {
        freshStart();
        pick(3);                                  // AA_ROUND2 4: Peaceful base (16) comes after iron
        stocked();
        bb.in.tableKnown = bb.in.furnaceKnown = true;
        bb.in.basePlace = "main";
        tree.tick(bb);
        assertEquals("house main", lastChain());
        port.done(1, "done");
        tree.tick(bb);
        bb.in.baseKnown = true;
        bb.in.freeSlots = 2;
        tree.tick(bb);
        assertTrue(String.join("|", reasons()).contains("BAG: no way can run"), reasons().toString());
        if (bb.task() != null) port.done(port.started.size(), "done");
        tree.tick(bb);
        bb.in.baseChests = true;
        clock.advance(75_000L + 1);
        tree.tick(bb);
        assertEquals("deposit", lastChain());
        port.done(port.started.size(), "done");
        tree.tick(bb);
        bb.in.freeSlots = 8;
        clock.advance(75_000L + 1);
        tree.tick(bb);
        assertEquals("deposit", lastChain(), "still under the leave threshold (10): the bag band stays on");
    }

    @Test
    void foodRungOnlyWhereHungerDrains() {
        freshStart();
        pick(3);
        bb.in.tableKnown = bb.in.furnaceKnown = true;
        bb.in.torches = 16;
        bb.in.baseKnown = true;
        bb.in.food = 5;
        tree.tick(bb);
        assertEquals("idle", tree.driver(), "Peaceful: no food rung");
        assertEquals("obtain 64 #minecraft:logs", lastChain());
        port.done(1, "done");
        tree.tick(bb);
        bb.in.hungerDrains = true;
        tree.tick(bb);
        assertEquals(TaskKind.FOOD, bb.task().kind, lastChain());
    }

    @Test
    void dangerPausesTheTaskAndResumesItAfterThreeSecondsCalm() {
        freshStart();
        tree.tick(bb);
        assertTrue(bb.task() != null && !bb.task().paused());
        bb.in.air = 50;
        tree.tick(bb);
        assertEquals("danger", tree.driver());
        assertEquals("drowning", tree.dangerWhy());
        assertTrue(bb.task().paused());
        assertEquals(1, port.paused);
        bb.in.air = 300;
        clock.advance(1000);
        tree.tick(bb);
        assertEquals("danger", tree.driver(), "calm for 1 s only");
        clock.advance(2100);
        tree.tick(bb);
        assertEquals("needs", tree.driver());
        assertFalse(bb.task().paused());
        assertEquals(1, port.resumed);
        assertEquals(0, tracer.counts.switches);
        assertEquals(lastChain(), bb.task().spec().chain(), "the same task goes on");
    }

    @Test
    void theOwnersOrderPreemptsNeedsAndIsAdopted() {
        freshStart();
        tree.tick(bb);
        assertTrue(tree.preemptForOwner(bb, "goto"));
        assertNull(bb.task());
        assertEquals(1, tracer.counts.preempts);
        assertEquals(1, tracer.counts.switches);
        assertEquals(0, tracer.counts.pointless, "the owner's order is a justified switch");
        assertTrue(port.cancelled.get(0).endsWith("preempted by owner: goto"), port.cancelled.toString());
        bb.in.otherJob = "travel (going to 1 2 3)";
        assertEquals(Status.RUNNING, tree.tick(bb));
        assertEquals("owner", tree.driver());
        assertEquals(1, port.started.size(), "nothing of AA's starts while the owner's job runs");
        assertFalse(tree.preemptForOwner(bb, "goto"), "an owner task is never preempted");
        bb.in.otherJob = null;
        assertEquals(Status.SUCCESS, tree.tick(bb));
        tree.tick(bb);
        assertEquals(2, port.started.size(), "then the ladder again");
    }

    @Test
    void anAiRequestQueuesBehindTheRunningTask() {
        freshStart();
        tree.tick(bb);
        bb.post(new Request("ai", TaskKind.AI, "cut 8", 1, clock.now(), 0, "the AI asked: cut 8"));
        tree.tick(bb);
        assertEquals("needs", tree.driver(), "the AI never preempts");
        port.done(1, "done");
        tree.tick(bb);
        tree.tick(bb);
        assertEquals("cut 8", lastChain());
        assertEquals("ai", tree.driver());
        assertEquals("ai", bb.task().source());
        String d = tracer.decisions().get(tracer.decisions().size() - 1);
        assertTrue(d.contains("\"branch\":\"ai\"") && d.contains("the AI asked: cut 8"), d);
    }

    /** 0.36.1 review 4: the owner's order preempts an AI task too; the AI's request goes back to the front of the board. */
    @Test
    void theOwnerPreemptsAnAiTaskAndItsRequestReturnsToTheBoard() {
        freshStart();
        pick(3);
        bb.in.tableKnown = bb.in.furnaceKnown = bb.in.baseKnown = true;
        bb.in.torches = 16;
        bb.post(new Request("ai", TaskKind.AI, "cut 8", 1, clock.now(), 0, "the AI asked: cut 8"));
        bb.post(new Request("ai", TaskKind.AI, "dig stone 16", 1, clock.now(), 0, "the AI asked: dig stone 16"));
        tree.tick(bb);
        assertEquals("ai", tree.driver());
        assertEquals("cut 8", bb.task().spec().chain());
        assertTrue(tree.preemptForOwner(bb, "come"));
        assertNull(bb.task());
        assertEquals("cut 8", bb.peek("ai").chain(), "the preempted request is first again");
        assertEquals(2, bb.requests().size());
        bb.in.otherJob = "travel";
        tree.tick(bb);
        assertEquals("owner", tree.driver());
        bb.in.otherJob = null;
        tree.tick(bb);
        tree.tick(bb);
        assertEquals("cut 8", bb.task().spec().chain(), "the AI's task runs again after the owner's");
        assertEquals(1, tracer.counts.preempts);
    }

    @Test
    void aRefusedStartCoolsDownAndIdleHasInertia() {
        freshStart();
        pick(3);
        bb.in.tableKnown = bb.in.furnaceKnown = bb.in.baseKnown = true;
        bb.in.torches = 16;
        port.refuse = "busy: a hunt runs";
        assertEquals(Status.FAILURE, tree.tick(bb));
        assertTrue(tree.root.reason().contains("busy: a hunt runs"), tree.root.reason());
        port.refuse = null;
        tree.tick(bb);
        assertEquals("obtain 64 #minecraft:stone_tool_materials", lastChain(), "S1: the refused option cools down 30 s, the next one runs at once");
        port.done(1, "done");
        tree.tick(bb);
        clock.advance(Cooldown.noRunMsV() + 1);
        tree.tick(bb);            // wood 100 vs stone 100 + 15 inertia
        assertEquals("obtain 64 #minecraft:stone_tool_materials", lastChain());
    }

    /** 0.36.4 (aa40d): short air alone holds the danger guard at most 30 s; then the task runs again. */
    /** Round 4 (owner): a fight or flee reflex is no pause any more; the danger desire prices it, the slot runs on. */
    @Test
    void hostilesNeverPauseTheSlotOnlyTheTrueReflexesDo() {
        freshStart();
        tree.tick(bb);
        bb.in.danger = 2;
        bb.in.dangerWhy = "fighting";
        tree.tick(bb);
        assertEquals("needs", tree.driver(), "a hit response runs under the slot");
        assertFalse(bb.task().paused());
        assertEquals("calm", tree.dangerWhy());
        bb.in.danger = 0;
        bb.in.creeperClose = true;
        tree.tick(bb);
        assertEquals("danger", tree.driver(), "the creeper sprint still pauses");
        assertEquals("creeper close", tree.dangerWhy());
        bb.in.creeperClose = false;
        clock.advance(3100);
        tree.tick(bb);
        assertEquals("needs", tree.driver());
    }

    @Test
    void shortAirAloneDoesNotHoldTheTaskForever() {
        freshStart();
        bb.in.health = 20;
        bb.in.air = 300;
        tree.tick(bb);
        assertEquals("needs", tree.driver());
        bb.in.air = 50;
        tree.tick(bb);
        assertEquals("danger", tree.driver());
        for (int i = 0; i < 40; i++) {
            clock.advance(1000);
            tree.tick(bb);
        }
        assertEquals("needs", tree.driver(), "the job resumes after 30 s + the calm time");
    }

    /** 0.36.3 (aa40b): in the quarry hole with no table, the ladder climbs first; at the surface it plans the table. */
    @Test
    void underGroundWithNoTableNeedsNoGate() {
        freshStart();
        pick(2);
        bb.in.torches = 16;
        bb.in.underDepth = 9;
        tree.tick(bb);
        // round 4A N: no surface gate; placing needs no open sky, a gather row prices its own climb (StationOptions)
        assertTrue(lastChain().endsWith("craft crafting_table 1"), lastChain());
    }

    /** 0.36.3: no reachable tree: the woodArea Need runs the area command the adapter found. */
    /** 0.36.3 (Peaceful, no wool): a shelter is the base, the bed is skipped, IRON gets its turn. */
    @Test
    void noWoolStillReachesIron() {
        freshStart();
        pick(2);
        bb.in.tableKnown = bb.in.furnaceKnown = true;
        bb.in.torches = 16;
        AaLadder.noteShelter(bb);
        tree.tick(bb);
        assertTrue(lastChain().endsWith("craft iron_pickaxe 1"), lastChain());
    }

    /** 0.36.2, the 40-minute run: a failed idle task cools down; the next idle option runs meanwhile. */
    @Test
    void aFailedIdleTaskCoolsDown() {
        freshStart();
        pick(3);
        bb.in.tableKnown = bb.in.furnaceKnown = bb.in.baseKnown = true;
        bb.in.torches = 16;
        tree.tick(bb);
        assertEquals("obtain 64 #minecraft:logs", lastChain());
        port.fail(1, "error: chopped nothing");
        tree.tick(bb);
        clock.advance(250);
        tree.tick(bb);
        assertNotEquals("obtain 64 #minecraft:logs", lastChain(), "no restart 0.25 s after the failure");
        assertEquals(2, port.started.size());
    }

    /** Live 2026-10-18: an earlier Need that starts planning while a lower rung's job runs must not cancel the job. */
    @Test
    void anEarlierRungPlanningNeverOutranksARunningJob() {
        freshStart();
        pick(2);
        bb.in.tableKnown = bb.in.furnaceKnown = true;
        tree.tick(bb);
        assertTrue(lastChain().endsWith("craft torch 16"), lastChain());
        bb.in.furnaceKnown = false;          // the furnace Need becomes unmet (and would plan) while torches run
        for (int i = 0; i < 5; i++) assertEquals(Status.RUNNING, tree.tick(bb));
        assertTrue(bb.task().spec().chain().endsWith("craft torch 16"), "the torches job still runs");
        assertEquals(0, port.cancelled.size());
        assertEquals(0, tracer.counts.switches);
        port.done(1, "done");
        bb.in.torches = 16;                  // round 4A N: no settle hole; the torches are made
        tree.tick(bb);
        tree.tick(bb);
        assertTrue(lastChain().endsWith("craft furnace 1"), "then the furnace Need gets its turn: " + lastChain());
    }

    @Test
    void mermaidAndNodeIds() {
        String m = tree.mermaid();
        assertTrue(m.startsWith("graph TD\n"));
        assertTrue(m.contains("root --> slot"), m);
        assertTrue(m.contains("brain --> needs_torches"), m);
        assertTrue(tree.nodes().size() > 25, "nodes: " + tree.nodes().size());
        freshStart();
        tree.tick(bb);
        String s = tracer.snapshot(clock.now(), true, tree.driver(), bb.taskJson(clock.now()), bb.in);
        assertTrue(s.contains("{\"id\":\"root\",\"s\":\"RUNNING\"}"), s);
        assertTrue(s.contains("{\"id\":\"danger\",\"s\":\"FAILURE\"}"), s);
        assertTrue(s.contains("{\"id\":\"needs.tableFurnace\",\"s\":\"RUNNING\"}"), s);
    }
}
