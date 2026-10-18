package io.github.mojolowjo.entropybot.aa;

import io.github.mojolowjo.entropybot.aa.core.Blackboard;
import io.github.mojolowjo.entropybot.aa.core.Clock;
import io.github.mojolowjo.entropybot.aa.core.FakePort;
import io.github.mojolowjo.entropybot.aa.core.Request;
import io.github.mojolowjo.entropybot.aa.core.Status;
import io.github.mojolowjo.entropybot.aa.core.TaskKind;
import io.github.mojolowjo.entropybot.aa.core.Tracer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** AA v0 P5 (AA_PLAN 9, 12): the ladder from canned inputs, danger pause/resume, the owner's preempt, the AI queue. */
class AaTreeTest {
    final Clock.Fake clock = new Clock.Fake(10_000_000);
    final FakePort port = new FakePort();
    final Tracer tracer = new Tracer();
    final Blackboard bb = new Blackboard(clock, port, tracer);
    final AaTree tree = AaTree.build(new AaScorer());

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
        bb.in.areaName = null;
        bb.in.areaKnown = true;
        bb.in.hungerDrains = false;
    }

    @Test
    void aFreshWorldGetsAnAreaFirst() {
        freshStart();
        bb.in.areaKnown = false;
        assertEquals(Status.RUNNING, tree.tick(bb));
        assertEquals(AaLadder.AREA_COMMAND, lastChain());
        port.done(1, "done");
        tree.tick(bb);
        bb.in.areaKnown = true;
        tree.tick(bb);
        assertEquals(AaLadder.BOOTSTRAP, lastChain());
    }

    /** One pickaxe of this tier, fresh. */
    void pick(int tier) {
        bb.in.pickTier = tier;
        bb.in.pickaxes = 1;
        bb.in.pickPct = 100;
    }

    String lastChain() { return port.started.get(port.started.size() - 1).chain(); }

    @Test
    void theLadderRunsTheFirstUnmetRungAndCommitsToIt() {
        freshStart();
        assertEquals(Status.RUNNING, tree.tick(bb));
        assertEquals(AaLadder.BOOTSTRAP, lastChain(), "no pickaxe at all: the from-nothing job");
        assertEquals("needs", tree.driver());
        assertEquals(TaskKind.TOOLS, bb.task().kind);
        // the bag changes meanwhile: no re-choice
        bb.in.torches = 0;
        pick(1);
        assertEquals(Status.RUNNING, tree.tick(bb));
        assertEquals(1, port.started.size());
        port.done(1, "done");
        assertEquals(Status.SUCCESS, tree.tick(bb));
        assertNull(bb.task());
        // a wooden pickaxe now: the tools rung is still unmet, with another goal (no settle for a different goal)
        assertEquals(Status.RUNNING, tree.tick(bb));
        assertEquals("goal stone tools", lastChain());
        port.done(2, "done");
        tree.tick(bb);
        pick(2);
        tree.tick(bb);
        assertEquals("goal table", lastChain());
        port.done(3, "done");
        tree.tick(bb);
        bb.in.tableKnown = true;
        tree.tick(bb);
        assertEquals("goal furnace", lastChain());
        port.done(4, "done");
        tree.tick(bb);
        bb.in.furnaceKnown = true;
        tree.tick(bb);
        assertEquals("goal torch 16", lastChain());
        port.done(5, "done");
        tree.tick(bb);
        bb.in.torches = 16;
        tree.tick(bb);
        assertEquals("goal shelter", lastChain(), "no area: a shelter, not a house");
        port.done(6, "done");
        assertEquals(Status.SUCCESS, tree.tick(bb));
        assertTrue(bb.flag(AaLadder.SHELTER_FACT), "the shelter is AA's own fact");
        tree.tick(bb);
        assertEquals("goal iron tools", lastChain(), "bed (no wool) and bag (no chests) are skipped with reasons");
        String reasons = String.join(" | ", reasons());
        assertTrue(reasons.contains("BASE_BED skipped: no wool for a bed (0/3"), reasons);
        assertTrue(reasons.contains("BAG met"), reasons);
        assertEquals(0, tracer.counts.switches, "no task replaced another");
        assertEquals(0, tracer.counts.pointless);
    }

    java.util.List<String> reasons() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Rung r : AaLadder.rungs(tree.ladder)) out.add(r.last() == Status.FAILURE ? r.reason() : r.id + " " + r.last());
        return out;
    }

    @Test
    void housePrefersAnAreaAndTheBagRungNeedsChests() {
        freshStart();
        pick(2);
        bb.in.tableKnown = bb.in.furnaceKnown = true;
        bb.in.torches = 16;
        bb.in.areaName = "main";
        tree.tick(bb);
        assertEquals("house main", lastChain());
        port.done(1, "done");
        tree.tick(bb);
        bb.in.baseKnown = true;
        bb.in.freeSlots = 2;
        tree.tick(bb);
        assertEquals("goal iron tools", lastChain(), "the bag rung is skipped without base chests");
        assertTrue(String.join("|", reasons()).contains("BAG skipped: no base chests scanned (scan base)"), reasons().toString());
        port.done(2, "done");
        tree.tick(bb);
        bb.in.baseChests = true;
        pick(3);
        tree.tick(bb);
        assertEquals("deposit", lastChain());
        port.done(3, "done");
        tree.tick(bb);
        bb.in.freeSlots = 8;
        clock.advance(Rung.SETTLE_MS + 1);
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
        assertEquals("goal wood 64", lastChain());
        port.done(1, "done");
        tree.tick(bb);
        bb.in.hungerDrains = true;
        tree.tick(bb);
        assertEquals("goal food 8", lastChain());
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
        assertEquals(AaLadder.BOOTSTRAP, bb.task().spec().chain(), "the same task goes on");
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
    void aGoalStillPlanningIsWaitedForThenSkipped() {
        freshStart();
        pick(1);
        port.resolveTo = null;
        assertEquals(Status.RUNNING, tree.tick(bb), "the slot waits on the rung that plans");
        assertEquals("needs>planning TOOLS: goal stone tools (0s)", tree.driver() + ">" + bb.taskText(clock.now()));
        assertEquals(0, port.started.size());
        Rung tools = AaLadder.rungs(tree.ladder).stream().filter(r -> r.id.equals("needs.tools")).findFirst().orElseThrow();
        clock.advance(Rung.PLAN_WAIT_MS + 1);
        assertEquals(Status.FAILURE, tools.tick(bb));
        assertTrue(tools.reason().contains("TOOLS skipped: no plan for goal stone tools"), tools.reason());
        assertEquals("no task", bb.taskText(clock.now()));
        port.resolveTo = "goal_stone_tools";
        tools.tick(bb);
        assertTrue(tools.reason().contains("TOOLS skipped: no plan"), "skipped for a while even though a plan exists now");
        clock.advance(2 * 60_000 + 1);
        assertEquals(Status.RUNNING, tools.tick(bb));
        assertEquals("goal_stone_tools", lastChain(), "the planner's routine is what runs");
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
        assertEquals("goal wood 64", lastChain(), "idle re-chooses at once (no cooldown on idle)");
        port.done(1, "done");
        tree.tick(bb);
        bb.in.wood = 40;          // wood now scores 60 - 31 = 29 (+15 inertia) vs stone 50
        tree.tick(bb);
        assertEquals("goal cobblestone 64", lastChain());
    }

    /** 0.36.3 (aa40b): in the quarry hole with no table, the ladder climbs first; at the surface it plans the table. */
    @Test
    void underGroundWithNoTableClimbsFirst() {
        freshStart();
        pick(2);
        bb.in.torches = 16;
        bb.in.underDepth = 9;
        tree.tick(bb);
        assertEquals(AaLadder.CLIMB, lastChain());
        port.done(1, "done");
        tree.tick(bb);
        bb.in.underDepth = 0;
        tree.tick(bb);
        assertEquals("goal table", lastChain());
    }

    /** 0.36.3: no reachable tree: the woodArea rung runs the area command the adapter found. */
    @Test
    void noReachableTreeMakesATreeArea() {
        freshStart();
        pick(3);
        bb.in.tableKnown = bb.in.furnaceKnown = bb.in.baseKnown = true;
        bb.in.torches = 16;
        bb.fact(AaLadder.TREE_AREA_FACT, "area 0 0 40 40 trees1 work neutral");
        tree.tick(bb);
        assertEquals("area 0 0 40 40 trees1 work neutral", lastChain());
    }

    /** 0.36.3 (Peaceful, no wool): a shelter is the base, the bed is skipped, IRON gets its turn. */
    @Test
    void noWoolStillReachesIron() {
        freshStart();
        pick(2);
        bb.in.tableKnown = bb.in.furnaceKnown = true;
        bb.in.torches = 16;
        AaLadder.noteShelter(bb);
        tree.tick(bb);
        assertEquals("goal iron tools", lastChain());
    }

    /** 0.36.2, the 40-minute run: a failed idle task cools down; the next idle option runs meanwhile. */
    @Test
    void aFailedIdleTaskCoolsDown() {
        freshStart();
        pick(3);
        bb.in.tableKnown = bb.in.furnaceKnown = bb.in.baseKnown = true;
        bb.in.torches = 16;
        tree.tick(bb);
        assertEquals("goal wood 64", lastChain());
        port.fail(1, "error: chopped nothing");
        tree.tick(bb);
        clock.advance(250);
        tree.tick(bb);
        assertNotEquals("goal wood 64", lastChain(), "no restart 0.25 s after the failure");
        assertEquals(2, port.started.size());
    }

    /** Live 2026-10-18: an earlier rung that starts planning while a lower rung's job runs must not cancel the job. */
    @Test
    void anEarlierRungPlanningNeverOutranksARunningJob() {
        freshStart();
        pick(2);
        bb.in.tableKnown = bb.in.furnaceKnown = true;
        tree.tick(bb);
        assertEquals("goal torch 16", lastChain());
        bb.in.furnaceKnown = false;          // the furnace rung becomes unmet (and would plan) while torches run
        port.resolveTo = null;
        for (int i = 0; i < 5; i++) assertEquals(Status.RUNNING, tree.tick(bb));
        assertEquals("goal torch 16", bb.task().spec().chain(), "the torches job still runs");
        assertEquals(0, port.cancelled.size());
        assertEquals(0, tracer.counts.switches);
        port.resolveTo = "*";
        port.done(1, "done");
        tree.tick(bb);
        tree.tick(bb);
        assertEquals("goal furnace", lastChain(), "then the furnace rung gets its turn");
    }

    @Test
    void mermaidAndNodeIds() {
        String m = tree.mermaid();
        assertTrue(m.startsWith("graph TD\n"));
        assertTrue(m.contains("root --> slot"), m);
        assertTrue(m.contains("needs --> needs_torches"), m);
        assertTrue(tree.nodes().size() > 25, "nodes: " + tree.nodes().size());
        freshStart();
        tree.tick(bb);
        String s = tracer.snapshot(clock.now(), true, tree.driver(), bb.taskJson(clock.now()), bb.in);
        assertTrue(s.contains("{\"id\":\"root\",\"s\":\"RUNNING\"}"), s);
        assertTrue(s.contains("{\"id\":\"danger\",\"s\":\"FAILURE\"}"), s);
        assertTrue(s.contains("{\"id\":\"needs.tools\",\"s\":\"RUNNING\"}"), s);
    }
}
