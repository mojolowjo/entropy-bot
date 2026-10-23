package io.github.mojolowjo.entropybot.craft;

import io.github.mojolowjo.entropybot.aa.brain.ItemWorth;
import io.github.mojolowjo.entropybot.aa.moves.Car;
import io.github.mojolowjo.entropybot.aa.moves.JobHands;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * station-memory (0.39.z14, owner 2026-10-22: "it should walk to the crafting table it placed and make it there"): the craft
 * step uses the known table wherever it is, through the hands' use (InteractProcess walks within reach first); a table gone
 * on arrival is forgotten with its line; none known anywhere is the only case that makes one.
 */
class StationVisitTest {
    static final String CRAFT = ItemWorth.CRAFT;

    /** The fork's hands: records each use, answers the status it is given. */
    static final class FakeHand implements JobHands.Hand {
        final List<int[]> used = new ArrayList<>();
        Car.Status status = Car.Status.running("walking within 4.5 m of crafting_table at 20 64 0");

        @Override public void start(List<int[]> dig, List<JobHands.Click> clicks, int timeoutTicks) { throw new IllegalStateException("no dig or click here"); }
        @Override public void startBuild(List<int[]> cells, List<String> items, int timeoutTicks, boolean replace) { throw new IllegalStateException("no build here"); }
        @Override public void startUse(int[] on) { used.add(on.clone()); }
        @Override public boolean reflexActive() { return false; }
        @Override public Car.Status status() { return status; }
        @Override public void cancel(String why) {}
        @Override public String verdict(String action, int[] cell) { return ""; }
        @Override public void look(double x, double y, double z) {}
    }

    /** The station memory: the known cells, what stands at them, what was forgotten. */
    static final class FakeMemory implements StationVisit.Memory {
        final List<int[]> known = new ArrayList<>();
        Boolean there = true;
        final List<String> forgot = new ArrayList<>();

        @Override public io.github.mojolowjo.entropybot.aa.StationFact nearest(String kind) { return known.isEmpty() ? new io.github.mojolowjo.entropybot.aa.StationFact.None("none known") : new io.github.mojolowjo.entropybot.aa.StationFact.Known(java.util.List.of(new io.github.mojolowjo.entropybot.aa.NeedChain.Spot(known.get(0)[0], known.get(0)[1], known.get(0)[2], 0, "minecraft:crafting_table"))); }
        int[] cell() { return known.isEmpty() ? null : known.get(0); }
        @Override public Boolean isStation(String kind, int[] at) { return there; }
        @Override public void forget(String kind, int[] at, String why) {
            forgot.add(why);
            known.removeIf(k -> Arrays.equals(k, at));
        }
    }

    static final int[] TABLE = {20, 64, 0};

    @Test
    void aLongWalkWaitsAsLongAsTheForkSaysRunning() {
        FakeHand hand = new FakeHand();
        long[] ms = {0};
        JobHands hands = new JobHands(hand, () -> ms[0]);
        FakeMemory mem = new FakeMemory();
        mem.known.add(TABLE);
        for (int i = 0; i < 20 * 600; i++) {   // 10 minutes of walking, no mod clock ends it
            ms[0] += 50;
            assertEquals(StationVisit.Kind.WAIT, StationVisit.tick(hands, mem, CRAFT, TABLE, "crafting table").kind());
        }
        assertEquals(1, hand.used.size(), "one use, never restarted by a clock");
        hand.status = Car.Status.failed("stuck 10 s short of the crafting_table at 20 64 0 (feet 0 64 0)");
        StationVisit.Out o = StationVisit.tick(hands, mem, CRAFT, TABLE, "crafting table");
        assertEquals(StationVisit.Kind.FAILED, o.kind());
        assertTrue(o.why().contains("stuck 10 s short of the crafting_table"), o.why());
    }

    @Test
    void aKnownTableOutOfReachIsUsedFirstNeverANewOne() {
        FakeHand hand = new FakeHand();
        JobHands hands = new JobHands(hand);
        FakeMemory mem = new FakeMemory();
        mem.known.add(TABLE);
        // the craft verb's choice: a table known 20 m away is used, even with wood in the bag (never "craft a crafting_table")
        StationVisit.Out o = StationVisit.tick(hands, mem, CRAFT, mem.cell(), "crafting table");
        assertEquals(StationVisit.Kind.WAIT, o.kind(), o.why());
        assertEquals(1, hand.used.size(), "the step's first act is the use of the known table");
        assertArrayEquals(TABLE, hand.used.get(0));
        assertEquals(StationVisit.Kind.WAIT, StationVisit.tick(hands, mem, CRAFT, TABLE, "crafting table").kind(), "walking: still the same use");
        assertEquals(1, hand.used.size(), "one use, followed every tick, not restarted");
        hand.status = Car.Status.done("opened crafting_table at 20 64 0 (clicked 1, menu 0 -> 3)");
        StationVisit.Out opened = StationVisit.tick(hands, mem, CRAFT, TABLE, "crafting table");
        assertEquals(StationVisit.Kind.OPENED, opened.kind(), opened.why());
        assertTrue(mem.forgot.isEmpty());
    }

    @Test
    void aTableGoneOnArrivalIsForgottenWithTheReason() {
        FakeHand hand = new FakeHand();
        JobHands hands = new JobHands(hand);
        FakeMemory mem = new FakeMemory();
        mem.known.add(TABLE);
        StationVisit.tick(hands, mem, CRAFT, TABLE, "crafting table");
        hand.status = Car.Status.failed("no stand within reach: no standable cell within 4.5 m of air at 20 64 0 sees a face of it");
        mem.there = false;                                 // arrived: the block there is not the table any more
        StationVisit.Out o = StationVisit.tick(hands, mem, CRAFT, TABLE, "crafting table");
        assertEquals(StationVisit.Kind.GONE, o.kind(), o.why());
        assertEquals("the crafting table at 20 64 0 is gone", o.why());
        assertEquals(List.of("the crafting table at 20 64 0 is gone"), mem.forgot);
        assertNull(mem.cell(), "forgotten: the chain now knows no table and prices one (NeedChainTest)");
    }

    @Test
    void aStandingTableThatFailsIsKeptAndTheFailureSaysWhy() {
        FakeHand hand = new FakeHand();
        JobHands hands = new JobHands(hand);
        FakeMemory mem = new FakeMemory();
        mem.known.add(TABLE);
        StationVisit.tick(hands, mem, CRAFT, TABLE, "crafting table");
        hand.status = Car.Status.failed("no stand within reach: not reached in 30 s");
        StationVisit.Out o = StationVisit.tick(hands, mem, CRAFT, TABLE, "crafting table");
        assertEquals(StationVisit.Kind.FAILED, o.kind());
        assertTrue(o.why().contains("not reached in 30 s"), o.why());
        assertTrue(mem.forgot.isEmpty(), "never forgotten for distance or a failed walk");
        assertArrayEquals(TABLE, mem.cell());
        // can't tell (not loaded): kept as well
        mem.there = null;
        StationVisit.tick(hands, mem, CRAFT, TABLE, "crafting table");
        assertEquals(StationVisit.Kind.FAILED, StationVisit.tick(hands, mem, CRAFT, TABLE, "crafting table").kind());
        assertTrue(mem.forgot.isEmpty());
    }

    @Test
    void noTableKnownAnywhereIsTheOnlyCaseThatMakesOne() {
        FakeHand hand = new FakeHand();
        FakeMemory mem = new FakeMemory();
        StationVisit.Out o = StationVisit.tick(new JobHands(hand), mem, CRAFT, mem.cell(), "crafting table");
        assertEquals(StationVisit.Kind.NONE, o.kind());
        assertTrue(hand.used.isEmpty());
    }
}
