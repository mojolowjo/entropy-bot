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
    void aDifferentRequestReplacesTheRunningOne() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.dig(new int[]{0, 0, 0}, 100, "a");
        h.click(new int[]{5, 6, 7}, "up", "minecraft:wheat_seeds", 100, "b", null);
        assertEquals(1, f.cancels);
        assertEquals(2, f.starts);
        assertTrue(h.busy(5, 6, 7));
        assertTrue(h.busy(5, 7, 7), "the cell beside the clicked face is the request's too");
    }

    @Test
    void aFailureNamesTheStageAndTheGuardVerdictOnce() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.click(new int[]{1, 64, 1}, "north", "minecraft:crafting_table", 100, "t", null);
        f.status = Car.Status.failed("click 1/1 on 1 64 1 north (grass_block): clicked 3 times with crafting_table, nothing changed");
        JobHands.Result r = h.click(new int[]{1, 64, 1}, "north", "minecraft:crafting_table", 100, "t", null);
        assertTrue(r.failed());
        assertTrue(r.why().startsWith("gave up at click 1 64 1 north minecraft:crafting_table: click 1/1"), r.why());
        assertTrue(r.why().contains("guard (place) at 1 64 0: the guard refused"), r.why());
        // the next call is a fresh try (the job counts its own tries)
        assertTrue(h.click(new int[]{1, 64, 1}, "north", "minecraft:crafting_table", 100, "t", null).running());
    }

    @Test
    void lostControlRestartsTwiceThenFails() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        h.dig(new int[]{0, 0, 0}, 100, "t");
        for (int i = 0; i < JobHands.RETRIES; i++) {
            f.status = Car.Status.failed("break 1/1: Baritone took control away (cancel or pause)");
            assertTrue(h.dig(new int[]{0, 0, 0}, 100, "t").running(), "retry " + i);
        }
        f.status = Car.Status.failed("break 1/1: Baritone took control away (cancel or pause)");
        assertTrue(h.dig(new int[]{0, 0, 0}, 100, "t").failed());
        assertEquals(1 + JobHands.RETRIES, f.starts);
    }

    @Test
    void tickSeesTheEndAndRunsOnDoneOnceAndKeepsTheResultForTheCaller() {
        Fake f = new Fake();
        JobHands h = new JobHands(f);
        int[] ran = {0};
        h.click(new int[]{2, 2, 2}, "up", "minecraft:furnace", 100, "t", () -> ran[0]++);
        f.status = Car.Status.done("clicked 2 2 2 up: beside air -> furnace");
        h.tick();
        assertEquals(1, ran[0]);
        assertTrue(h.idle());
        JobHands.Result r = h.click(new int[]{2, 2, 2}, "up", "minecraft:furnace", 100, "t", () -> ran[0]++);
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
    void stepThreeFilesAreOffTheMoversList() {
        for (String f : List.of("commands/ShelterJob.java", "commands/Clearing.java", "commands/RestoreRun.java", "commands/FarmPlanting.java"))
            assertFalse(MoversTest.CEILING.containsKey(f), f);
    }
}
