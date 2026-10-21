package io.github.mojolowjo.entropybot.aa.moves;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Backbone step 3: JobHands on a fake Baritone hand (MovesTest pattern). */
class JobHandsTest {
    static class Fake implements JobHands.Hand {
        final List<String> log = new ArrayList<>();
        Car.Status status = Car.Status.running("dig 1/1");
        int starts, cancels;
        double[] looked;

        @Override public void start(List<int[]> dig, List<JobHands.Click> clicks, int timeoutTicks) {
            starts++;
            log.add("start dig " + dig.size() + " click " + clicks.size() + " t " + timeoutTicks);
            status = Car.Status.running("started");
        }

        int builds;
        List<String> builtItems;

        boolean reflex, lastReplace;

        @Override public boolean reflexActive() { return reflex; }

        @Override public void startBuild(List<int[]> cells, List<String> items, int timeoutTicks, boolean replace) {
            lastReplace = replace;
            starts++;
            builds++;
            builtItems = items;
            log.add("build " + cells.size() + " t " + timeoutTicks);
            status = Car.Status.running("building");
        }

        @Override public Car.Status status() { return status; }

        @Override public void cancel(String why) { cancels++; log.add("cancel " + why); }

        @Override public String verdict(String action, int[] cell) { return "; guard (" + action + ") at " + cell[0] + " " + cell[1] + " " + cell[2] + ": the guard refused: protect box"; }

        @Override public void look(double x, double y, double z) { looked = new double[]{x, y, z}; }
    }

    @Test
    void sameRequestFollowsAndStartsOnce() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        assertTrue(h.dig(new int[]{1, 2, 3}, 200, "test").running());
        f.status = Car.Status.running("break 1/1 at 1 2 3 (stone)");
        JobHands.Result r = h.dig(new int[]{1, 2, 3}, 200, "test");
        assertTrue(r.running());
        assertEquals("break 1/1 at 1 2 3 (stone)", r.why());
        assertEquals(1, f.starts);
        assertTrue(h.busy(1, 2, 3));
        assertFalse(h.busy(1, 3, 3));
    }

    @Test
    void doneOnceThenANewRequestStartsAgain() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.dig(new int[]{0, 0, 0}, 100, "t");
        f.status = Car.Status.done("dug 0 0 0");
        assertTrue(h.dig(new int[]{0, 0, 0}, 100, "t").done());
        assertTrue(h.idle());
        assertFalse(h.busy(0, 0, 0));
        assertTrue(h.dig(new int[]{0, 0, 0}, 100, "t").running());
        assertEquals(2, f.starts);
    }

    @Test
    void review3_aSecondCallerWaitsWhileBusyAndNeverCancels() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.dig(new int[]{0, 0, 0}, 100, "a");
        JobHands.Result r = h.click(new int[]{5, 6, 7}, "minecraft:wheat_seeds", 100, "b", null);
        assertTrue(r.running());
        assertTrue(r.why().startsWith("waiting: my hands are busy with dig 0 0 0"), r.why());
        assertEquals(0, f.cancels);
        assertEquals(1, f.starts);
        f.status = Car.Status.done("dug");
        h.tick();
        assertTrue(h.click(new int[]{5, 6, 7}, "minecraft:wheat_seeds", 100, "b", null).running());
        assertEquals(2, f.starts, "a finished request makes room for the next");
        assertTrue(h.busy(5, 7, 7), "every cell beside the clicked block is the request's too (the fork picks the face)");
    }
    @Test
    void aFailureNamesTheStageAndTheGuardVerdictOnce() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.build(new int[]{1, 64, 1}, "minecraft:crafting_table", 100, "t", null);
        f.status = Car.Status.failed("click 1/1 on 1 64 1 north (grass_block): clicked 3 times with crafting_table, nothing changed");
        JobHands.Result r = h.build(new int[]{1, 64, 1}, "minecraft:crafting_table", 100, "t", null);
        assertTrue(r.failed());
        assertTrue(r.why().startsWith("gave up at build 1 64 1 minecraft:crafting_table: click 1/1"), r.why());
        assertTrue(r.why().contains("guard (place) at 1 64 1: the guard refused"), r.why());
        // the next call is a fresh try (the job counts its own tries)
        assertTrue(h.build(new int[]{1, 64, 1}, "minecraft:crafting_table", 100, "t", null).running());
    }

    @Test
    void lostControlRestartsTwiceThenFails() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.dig(new int[]{0, 0, 0}, 100, "t");
        for (int i = 0; i < JobHands.retriesMax(); i++) {
            f.status = Car.Status.lost("break 1/1: Baritone took control away (cancel or pause)");
            assertTrue(h.dig(new int[]{0, 0, 0}, 100, "t").running(), "retry " + i);
        }
        f.status = Car.Status.lost("break 1/1: Baritone took control away (cancel or pause)");
        assertTrue(h.dig(new int[]{0, 0, 0}, 100, "t").failed());
        assertEquals(1 + JobHands.retriesMax(), f.starts);
    }

    @Test
    void tickSeesTheEndAndRunsOnDoneOnceAndKeepsTheResultForTheCaller() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        int[] ran = {0};
        h.build(new int[]{2, 2, 2}, "minecraft:furnace", 100, "t", () -> ran[0]++);
        f.status = Car.Status.done("clicked 2 2 2 up: beside air -> furnace");
        h.tick();
        assertEquals(1, ran[0]);
        assertTrue(h.idle());
        JobHands.Result r = h.build(new int[]{2, 2, 2}, "minecraft:furnace", 100, "t", () -> ran[0]++);
        assertTrue(r.done(), "the caller still gets the DONE tick() saw");
        assertEquals(1, ran[0]);
        assertEquals(1, f.starts);
    }

    @Test
    void stopCancelsAndAThrowingHandIsAFailureNotAThrow() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.dig(new int[]{0, 0, 0}, 100, "t");
        h.stop("job ended");
        assertTrue(h.idle());
        assertEquals(1, f.cancels);
        JobHands bad = new JobHands(new Fake() {
            @Override public void start(List<int[]> dig, List<JobHands.Click> clicks, int timeoutTicks) { throw new IllegalStateException("boom"); }
        });
        JobHands.Result r = bad.dig(new int[]{0, 0, 0}, 100, "t");
        assertTrue(r.failed());
        assertTrue(r.why().contains("boom"));
        h.look(1, 2, 3);
        assertArrayEquals(new double[]{1, 2, 3}, f.looked);
    }

    @Test
    void review2_aDangerMovePreemptsWithAReasonAndNoDoneIsRead() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        int[] ran = {0};
        h.build(new int[]{2, 2, 2}, "minecraft:crafting_table", 100, "t", () -> ran[0]++);
        h.preempt("danger move burrow");
        assertEquals(1, f.cancels);
        f.status = Car.Status.done("the burrow's own DONE");      // the other run's end must not count
        h.tick();
        JobHands.Result r = h.build(new int[]{2, 2, 2}, "minecraft:crafting_table", 100, "t", () -> ran[0]++);
        assertTrue(r.failed());
        assertTrue(r.why().contains("preempted by danger move burrow"), r.why());
        assertEquals(0, ran[0], "no station note for a block never placed");
        assertEquals("gave up at build 2 2 2 minecraft:crafting_table: preempted by danger move burrow", h.lastFailure(2, 2, 2));
    }

    @Test
    void review5_aResultIsReadOnceThenTheSameCellStartsAFreshRun() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.dig(new int[]{9, 9, 9}, 100, "t");
        f.status = Car.Status.done("dug");
        h.tick();
        assertTrue(h.dig(new int[]{9, 9, 9}, 100, "t").done());
        // gravel fell back in: the retry clicks again (no cached DONE)
        assertTrue(h.dig(new int[]{9, 9, 9}, 100, "t").running());
        assertEquals(2, f.starts);
    }

    @Test
    void review4_stopEndsTheRunAndLeavesNothingBusy() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.dig(new int[]{1, 1, 1}, 100, "t");
        h.stop("left the world");
        assertEquals(1, f.cancels);
        assertFalse(h.busy(1, 1, 1));
        h.tick();
        assertEquals(1, f.starts, "nothing resumes");
    }

    @Test
    void stepThreeFilesAreOffTheMoversList() {
        for (String f : List.of("commands/ShelterJob.java", "commands/Clearing.java", "commands/RestoreRun.java", "commands/FarmPlanting.java"))
            assertFalse(MoversTest.CEILING.containsKey(f), f);
    }

    // ---- builder placements (0.39.q1) ----

    @Test
    void aPlacementIsABuilderLegWithNoFaceAndItsItem() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        assertTrue(h.build(new int[]{3, 64, 3}, "minecraft:crafting_table", 1200, "t", null).running());
        assertEquals(1, f.builds);
        assertEquals(List.of("minecraft:crafting_table"), f.builtItems);
        assertTrue(h.busy(3, 64, 3));
        f.status = Car.Status.done("place crafting_table: built");
        assertTrue(h.build(new int[]{3, 64, 3}, "minecraft:crafting_table", 1200, "t", null).done());
        assertEquals(1, f.starts, "followed, not restarted");
    }

    @Test
    void aMissingItemIsAClearFailureNotAStall() {
        Fake f = new Fake() {
            @Override public void startBuild(List<int[]> cells, List<String> items, int timeoutTicks, boolean replace) {
                super.startBuild(cells, items, timeoutTicks, replace);
                status = Car.Status.failed("stage items: need 1 torch, the bag has 0");
            }
        };
        JobHands h = new JobHands(f);
        h.build(new int[]{1, 1, 1}, "minecraft:torch", 1200, "t", null);
        JobHands.Result r = h.build(new int[]{1, 1, 1}, "minecraft:torch", 1200, "t", null);
        assertTrue(r.failed());
        assertTrue(r.why().contains("stage items: need 1 torch, the bag has 0"), r.why());
        assertTrue(r.why().contains("guard (place) at 1 1 1"), r.why());
    }

    @Test
    void aBuildLegLosingControlRestartsThenFails() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.build(new int[]{0, 0, 0}, "minecraft:furnace", 1200, "t", null);
        for (int i = 0; i < JobHands.retriesMax(); i++) {
            f.status = Car.Status.lost("stage build: place furnace: another Baritone process took control away");
            assertTrue(h.build(new int[]{0, 0, 0}, "minecraft:furnace", 1200, "t", null).running(), "retry " + i);
        }
        f.status = Car.Status.lost("stage build: place furnace: another Baritone process took control away");
        assertTrue(h.build(new int[]{0, 0, 0}, "minecraft:furnace", 1200, "t", null).failed());
        assertEquals(1 + JobHands.retriesMax(), f.builds);
    }

    @Test
    void aThrowingDoneStepFailsTheRequestWithItsReason() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.build(new int[]{4, 4, 4}, "minecraft:chest", 1200, "t", () -> { throw new IllegalStateException("ledger full"); });
        f.status = Car.Status.done("built");
        JobHands.Result r = h.build(new int[]{4, 4, 4}, "minecraft:chest", 1200, "t", null);
        assertTrue(r.failed());
        assertTrue(r.why().contains("ledger full"), r.why());
    }

    @Test
    void aFewBlockShellIsOneLeg() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        assertTrue(h.build(List.of(new int[]{0, 1, 0}, new int[]{0, 2, 0}), List.of("minecraft:dirt", "minecraft:dirt"), 1200, "t", null, false).running());
        assertEquals(1, f.builds);
        assertTrue(h.busy(0, 2, 0));
    }

    // ---- review round (0.39.q2) ----

    @Test
    void aTextThatMerelySaysTookControlAwayIsAFailureNotARetry() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.dig(new int[]{0, 0, 0}, 100, "t");
        f.status = Car.Status.failed("break 1/1: the guard said something about took control away");
        assertTrue(h.dig(new int[]{0, 0, 0}, 100, "t").failed(), "only the fork's typed end restarts");
        assertEquals(1, f.starts);
    }

    @Test
    void aReflexPauseResumesWithoutUsingARetryUntilTwiceTheLimit() {
        Fake f = new Fake();
        long[] now = {0};
        JobHands h = new JobHands(f, () -> now[0]);
        h.build(new int[]{0, 0, 0}, "minecraft:furnace", 100, "t", null);
        f.reflex = true;
        for (int i = 0; i < 5; i++) {
            f.status = Car.Status.lost("stage build: another Baritone process took control away");
            JobHands.Result r = h.build(new int[]{0, 0, 0}, "minecraft:furnace", 100, "t", null);
            assertTrue(r.running() && r.why().startsWith("paused by a reflex"), r.why());
        }
        now[0] = 2 * 100 * 50 + 1;      // past twice the leg's limit: the retries count again, then it fails
        for (int i = 0; i < JobHands.retriesMax(); i++) {
            f.status = Car.Status.lost("stage build: another Baritone process took control away");
            assertTrue(h.build(new int[]{0, 0, 0}, "minecraft:furnace", 100, "t", null).running());
        }
        f.status = Car.Status.lost("stage build: another Baritone process took control away");
        assertTrue(h.build(new int[]{0, 0, 0}, "minecraft:furnace", 100, "t", null).failed());
    }

    @Test
    void theVerdictCellIsTheOneTheForkReports() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.click(new int[]{5, 6, 7}, "minecraft:wheat_seeds", 100, "b", null);
        f.status = Car.Status.failed("click 1/1 on 5 6 7 north: nothing changed", new int[]{5, 6, 6});
        JobHands.Result r = h.click(new int[]{5, 6, 7}, "minecraft:wheat_seeds", 100, "b", null);
        assertTrue(r.why().contains("guard (place) at 5 6 6"), r.why());
    }

    @Test
    void aPlacementNeverAsksToReplaceUnlessTheJobDoes() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.build(new int[]{1, 1, 1}, "minecraft:dirt", 100, "t", null);
        assertFalse(f.lastReplace);
        h.stop("next");
        h.build(List.of(new int[]{2, 2, 2}), List.of("minecraft:dirt"), 100, "t", null, true);
        assertTrue(f.lastReplace);
    }
}